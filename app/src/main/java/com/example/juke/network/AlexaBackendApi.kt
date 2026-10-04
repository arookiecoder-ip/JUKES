package com.example.juke.network

import android.util.Log
import com.example.juke.BuildConfig
import com.example.juke.models.Track
import io.ktor.client.HttpClient
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
import kotlin.math.abs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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

    // Beta audio-provider support; keeps JUKE_BACKEND_* separate from Alexa source mode.
    private val json = Json { ignoreUnknownKeys = true }

    val isDownloadConfigured: Boolean
        get() = BuildConfig.JUKE_BACKEND_URL.isNotBlank() && BuildConfig.JUKE_BACKEND_KEY.isNotBlank()

    private val base get() = BuildConfig.JUKE_BACKEND_URL.trimEnd('/')
    private val key get() = BuildConfig.JUKE_BACKEND_KEY

    /** null = not probed yet, false = server predates `/audio/` (use the older endpoints). */
    @Volatile private var hasAudioEndpoint: Boolean? = null

    /** Uploads that are a different rendition of the song; skipped unless the title asks for them. */
    private val VARIANT_WORDS = listOf(
        "remix", "slowed", "reverb", "sped up", "speed up", "lofi", "lo-fi", "live", "karaoke",
        "instrumental", "cover", "dance mix", "8d", "nightcore", "acoustic", "unplugged", "mashup",
        "reprise", "extended", "version"
    )

    /**
     * A download request for the song. [live] = for immediate playback: a cache miss is streamed
     * as yt-dlp produces it (fast first byte, no length). Otherwise the server finishes the file
     * first so the download has a length and can be fetched in parallel ranges.
     */
    suspend fun getDownloadRequest(
        title: String,
        artist: String,
        durationSec: Int?,
        live: Boolean,
        client: HttpClient = ApiClient.httpClient
    ): SpotifyApi.DirectDownloadRequest {
        check(isDownloadConfigured) { "Backend not configured" }
        // Title + main artist: every featured artist in the query pulls in their other songs.
        val mainArtist = artist.split(", ").first()
        val query = "$title $mainArtist"
        val expected = durationSec?.takeIf { it > 0 }

        if (hasAudioEndpoint != false) {
            val response = client.get("$base/audio/") {
                header("X-Api-Key", key)
                parameter("q", query)
                expected?.let { parameter("duration", it) }
                parameter("info", "1")
            }
            val body = response.bodyAsText()
            val isJson = response.contentType()?.match(ContentType.Application.Json) == true
            when {
                response.status.value == 200 && isJson -> {
                    hasAudioEndpoint = true
                    val info = json.parseToJsonElement(body).jsonObject
                    val videoId = info.string("video_id") ?: error("Backend returned no video_id")
                    // The server picks by length only; make sure it is the same song.
                    check(isSameSong(info, title, artist)) {
                        "Backend match '${info.string("title")}' by ${info.string("artist")} is not '$title'"
                    }
                    val foundSec = (info["duration_ms"]?.jsonPrimitive?.intOrNull ?: 0) / 1000
                    checkLength(foundSec, expected, videoId)
                    return audioRequest(videoId, live)
                }
                // A route the server doesn't have comes back as its HTML "Page not found" page.
                response.status.value == 404 && !isJson -> {
                    Log.i(TAG, "Backend has no /audio/ endpoint; using /alexa/search + /proxy")
                    hasAudioEndpoint = false
                }
                else -> error("Backend /audio/ HTTP ${response.status.value}: ${body.take(120)}")
            }
        }
        return legacyRequest(query, title, artist, expected, client)
    }

    private fun audioRequest(videoId: String, live: Boolean): SpotifyApi.DirectDownloadRequest {
        val url = buildString {
            append("$base/audio/?video_id=").append(enc(videoId)).append("&key=").append(enc(key))
            if (!live) append("&wait=1")
        }
        // A finished file has Content-Length and ranges; a live stream has neither.
        return SpotifyApi.DirectDownloadRequest(url = url, probeRanges = !live)
    }

    private suspend fun legacyRequest(
        query: String,
        title: String,
        artist: String,
        expected: Int?,
        client: HttpClient
    ): SpotifyApi.DirectDownloadRequest {
        val search = client.get("$base/alexa/search/") {
            header("X-Api-Key", key)
            parameter("q", query)
        }
        check(search.status.value == 200) { "Backend search HTTP ${search.status.value}" }
        val songs = json.parseToJsonElement(search.bodyAsText()).jsonObject["songs"]?.jsonArray
            ?.mapNotNull { it as? JsonObject }.orEmpty()
        val pick = pickSong(songs.take(20), title, artist, expected)
            ?: error("Backend search has no '$title' by $artist") // next source takes over
        val videoId = pick.string("video_id")!!
        checkLength((pick["duration_ms"]?.jsonPrimitive?.intOrNull ?: 0) / 1000, expected, videoId)

        val stream = client.get("$base/get_stream/") {
            header("X-Api-Key", key)
            parameter("video_id", videoId)
        }
        check(stream.status.value == 200) { "Backend get_stream HTTP ${stream.status.value}" }
        val url = json.parseToJsonElement(stream.bodyAsText()).jsonObject.string("audio_url")
            ?: error("Backend returned no audio_url")
        return SpotifyApi.DirectDownloadRequest(url = url, probeRanges = false)
    }

    /**
     * The search hit that is this song, or null. Search ranking is popularity-driven: other songs
     * by a featured artist, or another song with the same name, often come first. So a hit must
     * carry the Spotify title and at least one of its artists, must not be a remix/slowed/live/...
     * rendition the title didn't ask for, and must not have a clearly different length when the
     * server reports one. Among those, a length match wins, then search order.
     */
    internal fun pickSong(songs: List<JsonObject>, title: String, artist: String, expected: Int?): JsonObject? {
        val wanted = title.lowercase()
        val tolerance = expected?.let { maxOf(8, it * 7 / 100) }
        fun seconds(song: JsonObject) = (song["duration_ms"]?.jsonPrimitive?.intOrNull ?: 0) / 1000
        val matches = songs.filter { song ->
            val name = song.string("title").orEmpty().lowercase()
            val sec = seconds(song)
            !song.string("video_id").isNullOrBlank() &&
                isSameSong(song, title, artist) &&
                VARIANT_WORDS.none { it in name && it !in wanted } &&
                (expected == null || tolerance == null || sec <= 0 || abs(sec - expected) <= tolerance)
        }
        return matches.firstOrNull { tolerance != null && seconds(it) > 0 } ?: matches.firstOrNull()
    }

    /** Same title (ignoring "(feat. …)", "[…]" and punctuation) and at least one shared artist. */
    internal fun isSameSong(song: JsonObject, title: String, artist: String): Boolean {
        // Spotify's " - Remastered 2011" / " - Radio Edit" suffix is not part of the name. (Only on
        // the Spotify side: uploads use "Artist - Song".)
        val coreTitle = words(stripExtras(title.substringBefore(" - "))).ifBlank { words(title) }
        if (coreTitle.isBlank()) return false
        // Uploads often put the artist in the title ("Artist - Song ft X"), so look at both.
        val hitTitle = words(stripExtras(song.string("title").orEmpty()))
        if (" $coreTitle " !in " $hitTitle ") return false
        val credits = " " + words(song.string("artist").orEmpty() + " " + song.string("title").orEmpty()) + " "
        return artist.split(", ").map(::words).any { it.length >= 2 && " $it " in credits }
    }

    private val BRACKETS = Regex("""[(\[][^)\]]*[)\]]""")
    private val FEATURING = Regex("""\b(feat|ft|featuring)\b.*""", RegexOption.IGNORE_CASE)
    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")
    private fun stripExtras(text: String) = FEATURING.replace(BRACKETS.replace(text, " "), " ")
    private fun words(text: String) = NON_WORD.replace(text.lowercase(), " ").trim()

    /** Reject a clearly different version before downloading anything (0 = server didn't say). */
    private fun checkLength(foundSec: Int, expected: Int?, videoId: String) {
        if (expected == null || foundSec <= 0) return
        if (abs(foundSec - expected) > maxOf(8, expected * 7 / 100)) {
            error("Backend match $videoId is ${foundSec}s, expected ${expected}s")
        }
    }

    private fun JsonObject.string(name: String) = this[name]?.jsonPrimitive?.contentOrNull
    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
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
