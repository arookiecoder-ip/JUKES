package com.example.juke.services

import com.example.juke.models.Track
import com.example.juke.network.BrowseItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** An offline copy keeps its account collection identity and original track ordering. */
@Serializable
data class DownloadedCollection(val id: String, val kind: String, val title: String,
    val image: String, val subtitle: String, val tracks: List<Track>) {
    val key get() = "$kind:$id"
    fun available(downloaded: List<Track>): List<Track> {
        val byVideo = downloaded.associateBy { it.ytVideoId }
        return tracks.mapNotNull { original -> byVideo[original.ytVideoId]?.let { original.copy(localUri = it.localUri, isStream = false) } }
    }
    fun browseItem() = BrowseItem(id, kind, title, subtitle, image, "", if (kind == "playlist") id else "",
        0L, "", "", false, JsonObject(mapOf("offline" to kotlinx.serialization.json.JsonPrimitive(true))))
}

/** A member remains a member when its file is removed and downloaded again. */
fun standaloneDownloads(downloaded: List<Track>, collections: List<DownloadedCollection>): List<Track> {
    val members = collections.flatMap { it.tracks }.mapNotNull { it.ytVideoId }.toSet()
    return downloaded.filter { it.ytVideoId !in members }
}
