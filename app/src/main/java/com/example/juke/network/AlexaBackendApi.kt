package com.example.juke.network

import android.util.Log
import com.example.juke.BuildConfig
import com.example.juke.models.Track
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
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

    @Serializable
    data class HomeFeedResponse(
        val schemaVersion: Int = 0,
        val shelves: List<HomeShelf> = emptyList()
    )

    @Serializable
    data class HomeShelf(
        val id: String = "",
        val title: String = "",
        val items: List<HomeItem> = emptyList()
    )

    @Serializable
    data class HomeTarget(val kind: String = "", val id: String = "")

    @Serializable
    data class HomeArtist(val name: String = "")

    @Serializable
    data class HomeItem(
        val title: String = "",
        val subtitle: String = "",
        val image: String? = null,
        val videoId: String? = null,
        val target: HomeTarget? = null,
        val artists: List<HomeArtist> = emptyList()
    )

    data class BackendShelf(val id: String, val title: String, val tracks: List<Track>)

    /** Phase 2b supports playable tracks; collection targets stay out of Spotify navigation. */
    fun homeTrackShelves(feed: HomeFeedResponse): List<BackendShelf> {
        require(feed.schemaVersion == 2) { "Unsupported backend home schema: ${feed.schemaVersion}" }
        return feed.shelves.mapNotNull { shelf ->
            val tracks = shelf.items.mapNotNull { item ->
                val target = item.target
                if (target?.kind != "track") return@mapNotNull null
                val videoId = item.videoId?.takeIf { it.isNotBlank() }
                    ?: target.id.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Track(
                    uuid = UUID.randomUUID().toString(),
                    title = item.title.ifBlank { "Unknown title" },
                    artist = item.artists.joinToString(", ") { it.name }.ifBlank { item.subtitle },
                    thumbnailUri = item.image?.takeIf { it.isNotBlank() },
                    durationSec = 0,
                    ytVideoId = videoId,
                    isStream = true
                )
            }.distinctBy { it.ytVideoId }
            tracks.takeIf { it.isNotEmpty() }?.let { BackendShelf(shelf.id, shelf.title, it) }
        }
    }

    suspend fun getHome(refresh: Boolean = false): List<BackendShelf> {
        requireConfigured()
        val response: HttpResponse = ApiClient.httpClient.get("${baseUrl()}/api/home/") {
            parameter("key", apiKey())
            if (refresh) parameter("refresh", 1)
        }
        check(response.status.value in 200..299) { "Backend home failed (${response.status.value})" }
        return homeTrackShelves(response.body<HomeFeedResponse>())
    }

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
            out += seedMeta.toAppTrack(audioUrl = seedAudio)
        }
        // Remaining playlist items have no stream yet; resolve lazily at play time
        // via get_stream so search stays fast. Keep items with a video_id only.
        parsed.playlist.forEach { item ->
            if (item.videoId.isNotBlank() && out.none { it.ytVideoId == item.videoId }) {
                // Skip duplicating the seed (already has stream URL attached)
                if (seedMeta?.videoId == item.videoId) return@forEach
                out += item.toAppTrack()
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
            parameter("update_queue", 0)
            parameter("key", apiKey())
        }
        if (response.status.value !in 200..299) {
            val body = runCatching { response.bodyAsText() }.getOrDefault("")
            throw Exception("get_radio failed (${response.status.value}): $body")
        }
        val parsed: GetRadioResponse = response.body()
        return parsed.playlist.filter { it.videoId.isNotBlank() }
    }

    @Serializable
    data class QueueTracksResponse(val tracks: List<BackendTrack> = emptyList())

    @Serializable
    data class QueueUpdate(
        val action: String,
        val after: String,
        val tracks: List<BackendTrack>,
        val playing: Boolean? = null,
        @SerialName("position_ms") val positionMs: Long? = null
    )

    suspend fun queueTracks(afterVideoId: String, limit: Int): List<BackendTrack> {
        requireConfigured()
        val response = ApiClient.httpClient.get("${baseUrl()}/queue_tracks/") {
            parameter("after", afterVideoId)
            parameter("limit", limit)
            parameter("key", apiKey())
        }
        check(response.status.value in 200..299) { "Queue refresh failed (${response.status.value})" }
        return response.body<QueueTracksResponse>().tracks
    }

    suspend fun updateQueue(
        action: String, afterVideoId: String, tracks: List<BackendTrack>,
        playing: Boolean? = null, positionMs: Long? = null
    ) {
        requireConfigured()
        val response = ApiClient.httpClient.post("${baseUrl()}/api/app/queue/") {
            parameter("key", apiKey())
            contentType(ContentType.Application.Json)
            setBody(QueueUpdate(action, afterVideoId, tracks, playing, positionMs))
        }
        check(response.status.value in 200..299) { "Queue update failed (${response.status.value})" }
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
        val response: HttpResponse = ApiClient.httpClient.get("${baseUrl()}/next_track/") {
            parameter("after", afterVideoId)
            parameter("key", apiKey())
        }
        check(response.status.value in 200..299) { "Next-track lookup failed (${response.status.value})" }
        val parsed: NextTrackResponse = response.body()
        return parsed.track?.takeIf { it.videoId.isNotBlank() }
    }
}

/**
 * Map a backend item into the app [Track] model: `video_id` → `ytVideoId`,
 * `audio_url` → `localUri` with `isStream = true`, `thumbnail` →
 * `thumbnailUri`, `duration_ms / 1000` → `durationSec`, fresh random `uuid`.
 *
 * Top-level (not an object member) so call sites only need the file import.
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
