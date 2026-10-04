package com.example.juke.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.client.call.body
import io.ktor.http.*
import kotlinx.serialization.json.*

fun JsonElement?.objectOrEmpty(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())
fun JsonObject.text(vararg names: String): String = names.firstNotNullOfOrNull { (get(it) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) } ?: ""
fun JsonObject.number(name: String): Long = (get(name) as? JsonPrimitive)?.longOrNull ?: 0
fun JsonObject.flag(name: String): Boolean = (get(name) as? JsonPrimitive)?.booleanOrNull == true
fun JsonObject.array(name: String): JsonArray = get(name) as? JsonArray ?: JsonArray(emptyList())
fun remoteImage(value: JsonElement?): String = when (value) {
    is JsonPrimitive -> value.contentOrNull.orEmpty()
    is JsonArray -> remoteImage(value.lastOrNull())
    is JsonObject -> value.text("url").ifBlank { remoteImage(value["thumbnails"]) }
    else -> ""
}

data class RemoteItem(val id: String, val kind: String, val title: String, val subtitle: String,
    val image: String, val videoId: String, val playlistId: String, val durationMs: Long,
    val artistId: String, val albumId: String, val editable: Boolean, val raw: JsonObject) {
    fun metadata(): JsonObject = buildJsonObject {
        put("video_id", videoId); put("title", title); put("artist", subtitle)
        put("thumbnail", image); put("duration_ms", durationMs); put("artist_id", artistId)
    }
}
data class RemoteShelf(val title: String, val items: List<RemoteItem>)
data class RemotePage(val title: String, val shelves: List<RemoteShelf>, val raw: JsonObject, val nextOffset: Long = 0, val hasMore: Boolean = false)

/** Handles both normalized v2 Home cards and raw YouTube browse/search responses. */
object AlexaRemoteParser {
    fun item(raw: JsonObject, hint: String = ""): RemoteItem {
        val target = raw["target"].objectOrEmpty()
        val play = raw["play"].objectOrEmpty()
        val video = raw.text("video_id", "videoId").ifBlank { play.text("videoId") }
        val browse = target.text("id").ifBlank { raw.text("browseId", "channel_id", "channelId", "id") }
        val playlist = raw.text("playlistId", "playlist_id", "audioPlaylistId").ifBlank { play.text("playlistId") }
        val declared = raw.text("kind", "type", "resultType").lowercase()
        val kind = when {
            video.isNotBlank() -> "track"
            browse.startsWith("MPRE") -> "album"
            browse.startsWith("VL") || browse.startsWith("RD") || browse.startsWith("PL") -> "playlist"
            browse.startsWith("UC") || declared == "artist" || hint == "artists" || hint == "related" -> "artist"
            raw.text("params").isNotBlank() -> "mood"
            declared == "album" || hint in listOf("albums", "singles", "new_releases") -> "album"
            else -> "playlist"
        }
        val artists = raw.array("artists")
        val credit = artists.mapNotNull { (it as? JsonObject)?.text("name")?.takeIf(String::isNotBlank) }.joinToString(", ")
        val artist = raw.text("artist", "author").ifBlank { credit }.ifBlank { raw.text("subtitle", "description") }
        val artistId = raw.text("artistId", "artist_id", "channel_id", "channelId").ifBlank { artists.firstOrNull().objectOrEmpty().text("id", "browseId") }
        val albumId = raw.text("albumId", "album_id", "albumBrowseId").ifBlank { raw["album"].objectOrEmpty().text("id", "browseId") }
        val clockDuration = raw.text("duration").split(":").mapNotNull { it.toLongOrNull() }.fold(0L) { seconds, part -> seconds * 60 + part } * 1000
        val duration = raw.number("duration_ms").takeIf { it > 0 } ?: (raw.number("duration_seconds").takeIf { it > 0 } ?: raw.number("durationSec")) * 1000
        val durationMs = duration.takeIf { it > 0 } ?: clockDuration
        return RemoteItem(video.ifBlank { browse.ifBlank { playlist.ifBlank { raw.text("params") } } }, kind,
            raw.text("title", "name").ifBlank { "Untitled" }, artist,
            listOf("image", "thumbnail", "thumbnail_url", "thumbnails", "images").firstNotNullOfOrNull { remoteImage(raw[it]).takeIf(String::isNotBlank) }.orEmpty(),
            video, playlist.ifBlank { if (kind == "playlist") browse.removePrefix("VL") else "" }, durationMs,
            artistId, albumId, raw.flag("editable"), raw)
    }
    fun page(data: JsonElement, title: String): RemotePage {
        val root = data.objectOrEmpty()
        fun rows(array: JsonArray, hint: String) = array.mapNotNull { it as? JsonObject }.map { item(it, hint) }
        val shelves = mutableListOf<RemoteShelf>()
        if (data is JsonArray) shelves += RemoteShelf(title, rows(data, "tracks"))
        if (root.array("shelves").isNotEmpty()) {
            root.array("shelves").forEach { shelf ->
                val obj = shelf.objectOrEmpty()
                shelves += RemoteShelf(obj.text("title"), rows(obj.array("items"), ""))
            }
        } else {
            for ((key, value) in root) {
                val array = value as? JsonArray ?: (value as? JsonObject)?.let { it["items"] as? JsonArray ?: it["results"] as? JsonArray }
                if (array != null && key !in listOf("thumbnails", "images", "filters", "liked_songs", "all") && (key != "artists" || root.containsKey("all") || root.containsKey("youtube_count"))) {
                    val label = key.replace('_', ' ').replaceFirstChar { it.uppercase() }
                    shelves += RemoteShelf(label, rows(array, key))
                }
            }
            // Library subscriptions have an artists array; album artist credits are not a shelf.

        }
        return RemotePage(root.text("title", "name").ifBlank { title }, shelves.filter { it.items.isNotEmpty() }, root, root.number("next_offset"), root.flag("has_more"))
    }
}

/** Dedicated client: no request logging, no redirects carrying credentials, memory-only owner cookies. */
class AlexaRemoteApi(private val server: String, private val key: String) : AutoCloseable {
    private val cookieStore = AcceptAllCookiesStorage()
    private val client = HttpClient(OkHttp) {
        followRedirects = false
        install(HttpCookies) { storage = cookieStore }
        install(HttpTimeout) { requestTimeoutMillis = 120_000 }
    }
    suspend fun request(path: String, method: HttpMethod = HttpMethod.Get, body: JsonObject? = null,
        query: Map<String, String> = emptyMap()): JsonElement {
        require(path.startsWith("/") && !path.startsWith("//"))
        val response = client.request(server.trimEnd('/') + path) {
            this.method = method
            header(HttpHeaders.Accept, "application/json")
            if (key.isNotBlank()) header("X-Api-Key", key)
            query.forEach { (name, value) -> parameter(name, value) }
            if (body != null) { contentType(ContentType.Application.Json); setBody(body.toString()) }
        }
        val text = response.bodyAsText()
        val parsed = runCatching { Json.parseToJsonElement(text) }.getOrNull()
        if (response.status.value !in 200..299) {
            val error = parsed.objectOrEmpty()["error"]
            val message = (error as? JsonPrimitive)?.contentOrNull ?: error.objectOrEmpty().text("message")
            throw IllegalStateException(message?.takeIf(String::isNotBlank) ?: "Server returned ${response.status.value}. Check server and owner login or API key.")
        }
        return parsed ?: throw IllegalStateException("Server returned a web page. Check the Alexa server address.")
    }
    suspend fun qr(): String {
        val response = client.get(server + "/alexa/jam/qr/") { if(key.isNotBlank()) header("X-Api-Key", key) }
        check(response.status.value == 200) { "QR code unavailable" }
        return response.bodyAsText()
    }
    suspend fun join(path: String) {
        require(path.matches(Regex("/(j|jam)/[A-Za-z0-9_-]+")))
        val response = client.get(server + path)
        check(response.status.value in listOf(302,303)) { "This Jam has ended or the invitation is invalid." }
    }
    suspend fun browserCookies(url: String): List<String> = cookieStore.get(Url(url)).map { renderSetCookieHeader(it) }
    suspend fun importBrowserCookies(url: String, cookies: String) {
        cookies.split(";").forEach { part ->
            val pair = part.trim().split("=", limit = 2)
            if (pair.size == 2) cookieStore.addCookie(Url(url), Cookie(pair[0], pair[1], domain = Url(url).host, path = "/", secure = true, httpOnly = true))
        }
    }
    override fun close() = client.close()
}
