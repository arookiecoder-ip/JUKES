package `in`.synthora.musicbox.network

import `in`.synthora.musicbox.models.Track
import kotlinx.serialization.json.JsonObject

/** Keep the collection identity, but persist authoritative detail metadata rather than an empty card. */
fun resolvedCollectionItem(item: BrowseItem, details: JsonObject): BrowseItem {
    val parsed = BrowseParser.item(details, if (item.kind == "album") "albums" else "playlists")
    val author = details["author"].objectOrEmpty().text("name")
    return item.copy(title = parsed.title.takeUnless { it == "Untitled" }.orEmpty().ifBlank { item.title },
        image = parsed.image.ifBlank { item.image }, subtitle = author.ifBlank { parsed.subtitle }.ifBlank { item.subtitle },
        raw = JsonObject(item.raw + details.filterKeys { it != "tracks" }))
}

data class CollectionContent(val item: BrowseItem, val tracks: List<Track>, val fetchedAtMs: Long = System.currentTimeMillis())

/** Continuation means another page, not a queue-limit violation; tolerate stale provider counts. */
internal suspend fun completeCollectionDetails(load: suspend (Long, Boolean) -> JsonObject): JsonObject {
    var page = load(0, true)
    val first = page
    val tracks = page.array("tracks").toMutableList()
    var offset = 0L
    var pages = 0
    while (page.flag("has_more") && page.array("tracks").isNotEmpty()) {
        check(++pages <= 60) { "The playlist continuation could not finish" }
        val next = page.number("next_offset").takeIf { it > offset } ?: (offset + page.array("tracks").size)
        page = load(next, false)
        offset = next
        tracks.addAll(page.array("tracks"))
        check(tracks.count { it.objectOrEmpty().text("videoId", "video_id").isNotBlank() } <= 5000) {
            "This playlist exceeds the 5,000-song queue limit"
        }
    }
    check(tracks.count { it.objectOrEmpty().text("videoId", "video_id").isNotBlank() } <= 5000) { "This playlist exceeds the 5,000-song queue limit" }
    return JsonObject(first + ("tracks" to kotlinx.serialization.json.JsonArray(tracks)) + ("has_more" to kotlinx.serialization.json.JsonPrimitive(false)))
}
