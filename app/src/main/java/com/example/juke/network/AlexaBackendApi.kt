package com.example.juke.network

import com.example.juke.models.Track
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.net.URLEncoder
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Audio endpoints for phone playback. They authorize with the build's API key (the web session
 * does not cover them), and keep the server's shared queue in step with the phone so playback
 * can move to the Echo and back.
 *
 * - `GET  /get_stream/?video_id=`  → `{"audio_url": "{PUBLIC_BASE_URL}/proxy/?video_id=…&key=…"}`
 * - `GET  /get_radio/?video_id=`   → `{"playlist": [...]}`
 * - `GET  /queue_tracks/?after=&limit=`, `GET /next_track/?after=`
 * - `POST /api/app/queue/`
 */
object AlexaBackendApi {

    fun isConfigured(): Boolean = Backend.audioBaseUrl.isNotEmpty() && Backend.apiKey.isNotEmpty()

    // ---------- Wire models (thumbnail is polymorphic: string | {url} | null) ----------

    @Serializable
    data class BackendTrack(
        val title: String = "",
        val artist: String = "",
        @SerialName("video_id") val videoId: String = "",
        val thumbnail: JsonElement? = null,
        @SerialName("duration_ms") val durationMs: Long = 0L
    )

    @Serializable
    data class StreamPayload(
        @SerialName("audio_url") val audioUrl: String = ""
    )

    @Serializable
    data class GetRadioResponse(
        val playlist: List<BackendTrack> = emptyList()
    )

    @Serializable
    data class NextTrackResponse(
        val track: BackendTrack? = null
    )

    @Serializable
    data class QueueTracksResponse(val tracks: List<BackendTrack> = emptyList())

    @Serializable
    data class QueueUpdate(
        val action: String,
        val after: String,
        val tracks: List<BackendTrack>,
        val playing: Boolean? = null,
        @SerialName("position_ms") val positionMs: Long? = null,
        @SerialName("queue_index") val queueIndex: Int? = null,
        @SerialName("output_owner") val outputOwner: String? = null,
        @SerialName("output_token") val outputToken: String? = null,
        val buffering: Boolean = false
    )

    fun thumbnailUrl(raw: JsonElement?): String? {
        if (raw == null) return null
        return try {
            raw.jsonPrimitive.let {
                if (it.isString) it.content.takeIf { s -> s.isNotBlank() } else null
            }
        } catch (_: Exception) {
            try {
                raw.jsonObject["url"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun requireConfigured() {
        check(isConfigured()) { "Phone playback is not configured: this build has no server API key" }
    }

    /**
     * Device audio never changes Echo now-playing state and is not cancelled by an Echo skip.
     * The existing alias is retained for queue callers; it resolves to the independent audio route.
     */
    /** Device audio is independent of Echo playback state and download cancellation. */
    fun audioUrl(videoId: String): String =
        deviceAudioUrl(Backend.audioBaseUrl, videoId)

    fun proxyUrl(videoId: String): String = audioUrl(videoId)

    /** The audio route itself prepares the file; no separate legacy stream lookup is required. */
    suspend fun getStreamUrl(videoId: String): String {
        requireConfigured()
        return audioUrl(videoId)
    }

    /** Radio/autoplay continuation seeded from one video. Stream URLs resolved lazily. */
    suspend fun getRadio(videoId: String): List<BackendTrack> {
        requireConfigured()
        val response: HttpResponse = ApiClient.httpClient.get("${Backend.audioBaseUrl}/get_radio/") {
            header("X-Api-Key", Backend.apiKey)
            parameter("video_id", videoId)
            parameter("update_queue", 0)
        }
        if (response.status.value !in 200..299) {
            val body = runCatching { response.bodyAsText() }.getOrDefault("")
            throw Exception("get_radio failed (${response.status.value}): ${body.take(200)}")
        }
        val parsed: GetRadioResponse = response.body()
        return parsed.playlist.filter { it.videoId.isNotBlank() }
    }

    suspend fun queueTracks(afterVideoId: String, limit: Int): List<BackendTrack> {
        requireConfigured()
        val response = ApiClient.httpClient.get("${Backend.audioBaseUrl}/queue_tracks/") {
            header("X-Api-Key", Backend.apiKey)
            parameter("after", afterVideoId)
            parameter("limit", limit)
        }
        check(response.status.value in 200..299) { "Queue refresh failed (${response.status.value})" }
        return response.body<QueueTracksResponse>().tracks
    }

    suspend fun updateQueue(
        action: String, afterVideoId: String, tracks: List<BackendTrack>,
        playing: Boolean? = null, positionMs: Long? = null, queueIndex: Int? = null, buffering: Boolean = false
    ) {
        requireConfigured()
        publishPhoneQueue(ApiClient.httpClient, Backend.audioBaseUrl, Backend.apiKey,
            QueueUpdate(action, afterVideoId, tracks, playing, positionMs, queueIndex,
                com.example.juke.services.PhonePlaybackOwnership.ownerId,
                com.example.juke.services.PhonePlaybackOwnership.token, buffering))
    }

    suspend fun phoneQueueSnapshot(): kotlinx.serialization.json.JsonObject {
        requireConfigured()
        return readPhoneQueue(ApiClient.httpClient, Backend.audioBaseUrl, Backend.apiKey)
    }

    suspend fun phoneOutputStatus(): com.example.juke.services.SharedPlaybackOutput {
        requireConfigured()
        return com.example.juke.services.sharedPlaybackOutput(outputRequest(ApiClient.httpClient, Backend.audioBaseUrl, Backend.apiKey))
    }

    suspend fun phoneOutputRequest(action: String, owner: String, token: String = "", serial: String = ""): com.example.juke.services.SharedPlaybackOutput {
        requireConfigured()
        val body = kotlinx.serialization.json.buildJsonObject {
            put("action", JsonPrimitive(action)); put("output_owner", JsonPrimitive(owner))
            put("output_token", JsonPrimitive(token)); put("serial", JsonPrimitive(serial))
        }
        return com.example.juke.services.sharedPlaybackOutput(outputRequest(ApiClient.httpClient, Backend.audioBaseUrl, Backend.apiKey, body))
    }

    fun backendTrack(track: Track): BackendTrack = BackendTrack(
        title = track.title,
        artist = track.artist,
        videoId = requireNotNull(track.ytVideoId),
        thumbnail = track.thumbnailUri?.let { JsonPrimitive(it) } ?: JsonNull,
        durationMs = track.durationSec.toLong() * 1000
    )

    /** Authoritative next-up track from the server's live queue (`after` is required). */
    suspend fun nextTrack(afterVideoId: String): BackendTrack? {
        requireConfigured()
        val response: HttpResponse = ApiClient.httpClient.get("${Backend.audioBaseUrl}/next_track/") {
            header("X-Api-Key", Backend.apiKey)
            parameter("after", afterVideoId)
        }
        check(response.status.value in 200..299) { "Next-track lookup failed (${response.status.value})" }
        val parsed: NextTrackResponse = response.body()
        return parsed.track?.takeIf { it.videoId.isNotBlank() }
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
}

/**
 * Map a backend item into the app [Track] model: `video_id` → `ytVideoId`,
 * `audio_url` → `localUri` with `isStream = true`, `thumbnail` →
 * `thumbnailUri`, `duration_ms / 1000` → `durationSec`, fresh random `uuid`.
 */
fun AlexaBackendApi.BackendTrack.toAppTrack(audioUrl: String? = null): Track {
    return Track(
        uuid = UUID.randomUUID().toString(),
        title = title.ifBlank { "Unknown title" },
        artist = artist.ifBlank { "Unknown artist" },
        thumbnailUri = AlexaBackendApi.thumbnailUrl(thumbnail),
        durationSec = (durationMs / 1000).toInt().coerceAtLeast(0),
        localUri = audioUrl?.ifBlank { null },
        ytVideoId = videoId.ifBlank { null },
        isStream = true
    )
}

/** Keep queue writes on the same authenticated server as radio, queue reads and phone audio. */
internal suspend fun publishPhoneQueue(
    client: io.ktor.client.HttpClient, audioServer: String, apiKey: String, update: AlexaBackendApi.QueueUpdate
) {
    val response = client.post(audioServer.trimEnd('/') + "/api/app/queue/") {
        header("X-Api-Key", apiKey)
        contentType(ContentType.Application.Json)
        setBody(kotlinx.serialization.json.Json.encodeToString(AlexaBackendApi.QueueUpdate.serializer(), update))
    }
    if (response.status.value !in 200..299) {
        throw BackendHttpException(response.status.value, "Phone queue update failed (${response.status.value}). Check the audio server and its API key.")
    }
}

internal suspend fun readPhoneQueue(
    client: io.ktor.client.HttpClient, audioServer: String, apiKey: String
): kotlinx.serialization.json.JsonObject {
    val response = client.get(audioServer.trimEnd('/') + "/alexa/now_playing/") {
        header("X-Api-Key", apiKey)
        parameter("serial", "phone")
    }
    if (response.status.value !in 200..299) {
        throw BackendHttpException(response.status.value, "Phone queue read failed (${response.status.value}).")
    }
    return kotlinx.serialization.json.Json.parseToJsonElement(response.bodyAsText()).jsonObject
}

internal suspend fun outputRequest(client: io.ktor.client.HttpClient, server: String, apiKey: String,
    body: kotlinx.serialization.json.JsonObject? = null): kotlinx.serialization.json.JsonObject {
    val url = server.trimEnd('/') + "/api/app/output/"
    val response = if (body == null) client.get(url) { header("X-Api-Key", apiKey) }
        else client.post(url) { header("X-Api-Key", apiKey); contentType(ContentType.Application.Json); setBody(body.toString()) }
    if (response.status.value !in 200..299) throw BackendHttpException(response.status.value,
        if (response.status.value == 409) "Playback output changed. Refresh and retry." else "Couldn't synchronize playback output (${response.status.value}).")
    return kotlinx.serialization.json.Json.parseToJsonElement(response.bodyAsText()).jsonObject
}
