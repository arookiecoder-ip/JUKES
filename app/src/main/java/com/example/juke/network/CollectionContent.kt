package com.example.juke.network

import com.example.juke.models.Track
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
