package `in`.synthora.musicbox.services

import `in`.synthora.musicbox.models.Track
import java.io.File
import java.net.URI

/** A download must be a readable, nonempty local file; an HTTP URI is never a download. */
internal fun readableDownloadedTrack(track: Track): Track? {
    val location = track.localUri ?: return null
    val file = runCatching {
        val uri = URI(location)
        when (uri.scheme?.lowercase()) {
            "file" -> File(uri)
            null -> File(location)
            else -> return null
        }
    }.getOrNull() ?: return null
    return track.takeIf { file.isFile && file.canRead() && file.length() > 0 }?.copy(isStream = false)
}

internal data class OfflinePlaybackQueue(val tracks: List<Track>, val index: Int)

/** Keep collection order and duplicate occurrences, excluding unavailable upcoming files. */
internal fun offlinePlaybackQueue(tracks: List<Track>, index: Int,
    local: (Track) -> Track? = ::readableDownloadedTrack): OfflinePlaybackQueue {
    require(index in tracks.indices) { "Choose a downloaded song to play" }
    val selected = local(tracks[index]) ?: throw IllegalStateException(
        "This downloaded song is missing or unreadable. Connect and download it again.")
    val available = tracks.mapIndexedNotNull { position, track ->
        (if (position == index) selected else local(track))?.let { position to it }
    }
    return OfflinePlaybackQueue(available.map { it.second }, available.indexOfFirst { it.first == index })
}
