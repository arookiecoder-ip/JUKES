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
        return tracks.mapNotNull { byVideo[it.ytVideoId] }.distinctBy { it.ytVideoId }
    }
    fun browseItem() = BrowseItem(id, kind, title, subtitle, image, "", if (kind == "playlist") id else "",
        0L, "", "", false, JsonObject(emptyMap()))
}
