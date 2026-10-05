package com.example.juke.network

import com.example.juke.models.Track
import kotlinx.serialization.json.*

fun JsonElement?.objectOrEmpty(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())
fun JsonObject.text(vararg names: String): String = names.firstNotNullOfOrNull { (get(it) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) } ?: ""
fun JsonObject.number(name: String): Long = (get(name) as? JsonPrimitive)?.longOrNull ?: 0
fun JsonObject.flag(name: String): Boolean = (get(name) as? JsonPrimitive)?.booleanOrNull == true
fun JsonObject.array(name: String): JsonArray = get(name) as? JsonArray ?: JsonArray(emptyList())
fun imageUrl(value: JsonElement?): String = when (value) {
    is JsonPrimitive -> value.contentOrNull.orEmpty()
    is JsonArray -> imageUrl(value.lastOrNull())
    is JsonObject -> value.text("url").ifBlank { imageUrl(value["thumbnails"]) }
    else -> ""
}

data class BrowseItem(val id: String, val kind: String, val title: String, val subtitle: String,
    val image: String, val videoId: String, val playlistId: String, val durationMs: Long,
    val artistId: String, val albumId: String, val editable: Boolean, val raw: JsonObject) {
    fun metadata(): JsonObject = buildJsonObject {
        put("video_id", videoId); put("title", title); put("artist", subtitle)
        put("thumbnail", image); put("duration_ms", durationMs); put("artist_id", artistId)
    }
}
data class BrowseShelf(val title: String, val items: List<BrowseItem>)
data class BrowsePage(val title: String, val shelves: List<BrowseShelf>, val raw: JsonObject, val nextOffset: Long = 0, val hasMore: Boolean = false)

/** Handles both normalized v2 Home cards and raw YouTube browse/search responses. */
object BrowseParser {
    fun item(raw: JsonObject, hint: String = ""): BrowseItem {
        val target = raw["target"].objectOrEmpty()
        val play = raw["play"].objectOrEmpty()
        val video = raw.text("video_id", "videoId").ifBlank { play.text("videoId") }
        val browse = target.text("id").ifBlank { raw.text("browseId", "browse_id", "channel_id", "channelId", "id") }
        val playlist = raw.text("playlistId", "playlist_id", "audioPlaylistId").ifBlank { play.text("playlistId") }
        val declared = raw.text("kind", "type", "resultType").lowercase()
        val kind = when {
            video.isNotBlank() -> "track"
            browse.startsWith("MPRE") -> "album"
            browse.startsWith("VL") || browse.startsWith("RD") || browse.startsWith("PL") -> "playlist"
            browse.startsWith("UC") || declared == "artist" || hint == "artists" || hint == "related" -> "artist"
            raw.text("params").isNotBlank() -> "mood"
            declared in listOf("album", "single", "singles") || hint in listOf("albums", "singles", "new_releases") -> "album"
            else -> "playlist"
        }
        val artists = raw.array("artists")
        val credit = artists.mapNotNull { (it as? JsonObject)?.text("name")?.takeIf(String::isNotBlank) }.joinToString(", ")
        val artist = raw.text("artist", "author", "owner").ifBlank { credit }.ifBlank { raw.text("subtitle", "description") }
        val artistId = raw.text("artistId", "artist_id", "channel_id", "channelId").ifBlank { artists.firstOrNull().objectOrEmpty().text("id", "browseId") }
        val albumId = raw.text("albumId", "album_id", "albumBrowseId").ifBlank { raw["album"].objectOrEmpty().text("id", "browseId") }
        val clockDuration = raw.text("duration").split(":").mapNotNull { it.toLongOrNull() }.fold(0L) { seconds, part -> seconds * 60 + part } * 1000
        val duration = raw.number("duration_ms").takeIf { it > 0 } ?: (raw.number("duration_seconds").takeIf { it > 0 } ?: raw.number("durationSec")) * 1000
        val durationMs = duration.takeIf { it > 0 } ?: clockDuration
        return BrowseItem(video.ifBlank { browse.ifBlank { playlist.ifBlank { raw.text("params") } } }, kind,
            raw.text("title", "name").ifBlank { if (kind == "artist") raw.text("artist") else "" }.ifBlank { "Untitled" }, artist,
            listOf("image", "thumbnail", "thumbnail_url", "thumbnails", "images").firstNotNullOfOrNull { imageUrl(raw[it]).takeIf(String::isNotBlank) }.orEmpty(),
            video, playlist.ifBlank { if (kind == "playlist") browse.removePrefix("VL") else "" }, durationMs,
            artistId, albumId, raw.flag("editable"), raw)
    }
    fun page(data: JsonElement, title: String): BrowsePage {
        val root = data.objectOrEmpty()
        fun rows(array: JsonArray, hint: String) = array.mapNotNull { it as? JsonObject }.map { item(it, hint) }
        val shelves = mutableListOf<BrowseShelf>()
        if (data is JsonArray) shelves += BrowseShelf(title, rows(data, "tracks"))
        if (root.array("shelves").isNotEmpty()) {
            root.array("shelves").forEach { shelf ->
                val obj = shelf.objectOrEmpty()
                shelves += BrowseShelf(obj.text("title"), rows(obj.array("items"), ""))
            }
        } else {
            for ((key, value) in root) {
                val array = value as? JsonArray ?: (value as? JsonObject)?.let { it["items"] as? JsonArray ?: it["results"] as? JsonArray }
                if (array != null && key !in listOf("thumbnails", "images", "filters", "liked_songs", "all") && (key != "artists" || root.containsKey("all") || root.containsKey("youtube_count"))) {
                    val label = key.replace('_', ' ').replaceFirstChar { it.uppercase() }
                    shelves += BrowseShelf(label, rows(array, key))
                }
            }
            // Library subscriptions have an artists array; album artist credits are not a shelf.

        }
        return BrowsePage(root.text("title", "name").ifBlank { title }, shelves.filter { it.items.isNotEmpty() }, root, root.number("next_offset"), root.flag("has_more"))
    }
}

/** A playable account song. Liked state comes from the YouTube account, keyed by video id. */
fun BrowseItem.toTrack(liked: Set<String> = emptySet(), fallbackImage: String = ""): Track = Track(
    uuid = java.util.UUID.randomUUID().toString(),
    title = title,
    artist = subtitle,
    thumbnailUri = image.ifBlank { fallbackImage }.ifBlank { null },
    durationSec = (durationMs / 1000).toInt(),
    ytVideoId = videoId,
    isStream = true,
    isFavourite = videoId in liked,
    albumId = albumId.ifBlank { null },
    artistId = artistId.ifBlank { null },
    artists = raw.array("artists").mapNotNull { credit ->
        val artist = credit.objectOrEmpty()
        artist.text("name").takeIf { it.isNotBlank() }?.let {
            com.example.juke.models.ArtistCredit(it, artist.text("id", "browseId", "channel_id").ifBlank { null })
        }
    }
)

/** Song metadata in the shape every queue endpoint accepts. */
fun Track.metadata(): JsonObject = buildJsonObject {
    put("video_id", ytVideoId.orEmpty()); put("title", title); put("artist", artist)
    put("thumbnail", thumbnailUri.orEmpty()); put("duration_ms", durationSec * 1000L)
    artistId?.let { put("artist_id", it) }
}
