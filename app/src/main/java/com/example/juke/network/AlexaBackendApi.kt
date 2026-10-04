package com.example.juke.network

import android.util.Log
import com.example.juke.BuildConfig
import com.example.juke.models.Track
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * Backend API for the self-hosted YouTube Music Alexa skill server.
 *
 * Second music source behind the Settings toggle (Spotify keeps working as today).
 * Reuses [ApiClient.httpClient] (OkHttp engine on beta); auth via `?key=` so app +
 * Alexa-device paths stay identical.
 *
 * Verified contract against `flask-server/server.py`:
 * - `GET {BASE}/find_stream_list/?query=<q>&filter=songs&key=<key>`
 * - `GET {BASE}/get_stream/?video_id=<id>&key=<key>`
 * - `GET {BASE}/get_radio/?video_id=<id>&key=<key>`
 * - `GET {BASE}/next_track/?after=<video_id>&key=<key>`
 */
object AlexaBackendApi {

    private const val TAG = "AlexaBackendApi"

    fun baseUrl(): String = BuildConfig.ALEXA_BASE_URL.trim().trimEnd('/')

    fun apiKey(): String = BuildConfig.ALEXA_API_KEY.trim()

    fun isConfigured(): Boolean = baseUrl().isNotEmpty() && apiKey().isNotEmpty()

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
    data class SongInfo(
        val metadata: BackendTrack? = null,
        val stream: StreamPayload? = null
    )

    @Serializable
    data class FindStreamListResponse(
        @SerialName("song_info") val songInfo: SongInfo? = null,
        val playlist: List<BackendTrack> = emptyList()
    )

    @Serializable
    data class GetRadioResponse(
        val playlist: List<BackendTrack> = emptyList()
    )

    @Serializable
    data class NextTrackResponse(
        val track: BackendTrack? = null
    )

    fun thumbnailUrl(raw: JsonElement?): String? {
        if (raw == null) return null
        return try {
            // Plain string
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

    fun BackendTrack.toTrack(audioUrl: String? = null, resolvedAudioUrl: String? = null): Track {
        val streamUrl = resolvedAudioUrl ?: audioUrl ?: ""
        return Track(
            uuid = UUID.randomUUID().toString(),
            title = title.ifBlank { "Unknown title" },
            artist = artist.ifBlank { "Unknown artist" },
            thumbnailUri = thumbnailUrl(thumbnail),
            durationSec = (durationMs / 1000).toInt().coerceAtLeast(0),
            localUri = streamUrl.ifBlank { null },
            ytVideoId = videoId.ifBlank { null },
            isStream = true
        )
    }

    private fun requireConfigured() {
        check(isConfigured()) {
            "Alexa backend not configured: set ALEXA_BASE_URL / ALEXA_API_KEY in local.properties"
        }
    }

    // ---------- Endpoints ----------

    /**
     * Search. `filter` defaults to `songs`; param is `query` (NOT `q`).
     * Returns full playlist; first item carries the playable stream.
     */
    suspend fun search(query: String, filter: String = "songs"): List<Track> {
        requireConfigured()
        val response: HttpResponse = ApiClient.httpClient.get("${baseUrl()}/find_stream_list/") {
            parameter("query", query)
            parameter("filter", filter)
            parameter("key", apiKey())
        }
        if (response.status.value !in 200..299) {
            val body = runCatching { response.bodyAsText() }.getOrDefault("")
            throw Exception("Backend search failed (${response.status.value}): $body")
        }
        val parsed: FindStreamListResponse = response.body()
        val seedAudio = parsed.songInfo?.stream?.audioUrl
        val seedMeta = parsed.songInfo?.metadata
        val out = mutableListOf<Track>()
        if (seedMeta != null && seedMeta.videoId.isNotBlank()) {
            out += seedMeta.toTrack(audioUrl = seedAudio)
        }
        // Remaining playlist items have no stream yet; resolve lazily at play time
        // via get_stream so search stays fast. Keep items with a video_id only.
        parsed.playlist.forEach { item ->
            if (item.videoId.isNotBlank() && out.none { it.ytVideoId == item.videoId }) {
                // Skip duplicating the seed (already has stream URL attached)
                if (seedMeta?.videoId == item.videoId) return@forEach
                out += item.toTrack(audioUrl = null)
            }
        }
        Log.d(TAG, "search '$query' -> ${out.size} tracks (seed stream=${!seedAudio.isNullOrBlank()})")
        return out
    }

    /** Resolve a playable `/proxy/...` audio URL for a video_id. */
    suspend fun getStreamUrl(videoId: String): String {
        requireConfigured()
        val response: HttpResponse = ApiClient.httpClient.get("${baseUrl()}/get_stream/") {
            parameter("video_id", videoId)
            parameter("key", apiKey())
        }
        if (response.status.value !in 200..299) {
            val body = runCatching { response.bodyAsText() }.getOrDefault("")
            throw Exception("get_stream failed (${response.status.value}): $body")
        }
        val parsed: StreamPayload = response.body()
        if (parsed.audioUrl.isBlank()) throw Exception("get_stream returned empty audio_url")
        return parsed.audioUrl
    }

    /** Radio/autoplay continuation seeded from one video. Stream URLs resolved lazily. */
    suspend fun getRadio(videoId: String): List<BackendTrack> {
        requireConfigured()
        val response: HttpResponse = ApiClient.httpClient.get("${baseUrl()}/get_radio/") {
            parameter("video_id", videoId)
            parameter("key", apiKey())
        }
        if (response.status.value !in 200..299) {
            val body = runCatching { response.bodyAsText() }.getOrDefault("")
            throw Exception("get_radio failed (${response.status.value}): $body")
        }
        val parsed: GetRadioResponse = response.body()
        return parsed.playlist.filter { it.videoId.isNotBlank() }
    }

    /** Authoritative next-up track from the server's live queue (`after` is required). */
    suspend fun nextTrack(afterVideoId: String): BackendTrack? {
        requireConfigured()
        val response: HttpResponse = ApiClient.httpClient.get("${baseUrl()}/next_track/") {
            parameter("after", afterVideoId)
            parameter("key", apiKey())
        }
        if (response.status.value !in 200..299) return null
        val parsed: NextTrackResponse = response.body()
        return parsed.track?.takeIf { it.videoId.isNotBlank() }
    }
}
