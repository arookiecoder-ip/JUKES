package `in`.synthora.musicbox.services

import `in`.synthora.musicbox.models.Track
import `in`.synthora.musicbox.network.BrowseItem
import `in`.synthora.musicbox.viewmodels.HomeShelf
import kotlin.random.Random

internal fun speedDialItems(shelves: List<HomeShelf>, recent: List<Track>): List<BrowseItem> {
    val songs = recent.filter { !it.ytVideoId.isNullOrBlank() }.take(12).map {
        BrowseItem(id = it.ytVideoId!!, kind = "track", title = it.title, subtitle = it.artist,
            image = it.thumbnailUri.orEmpty(), videoId = it.ytVideoId.orEmpty(), playlistId = "", durationMs = it.durationSec * 1000L,
            artistId = it.artistId.orEmpty(), albumId = it.albumId.orEmpty(), editable = false,
            raw = kotlinx.serialization.json.JsonObject(emptyMap()))
    }
    return (songs + shelves.flatMap { it.items }.filter {
        it.kind == "track" && it.videoId.isNotBlank()
    }).distinctBy { if (it.kind == "track") "track:${it.videoId}" else "${it.kind}:${it.id}" }.take(12)
}

/** Home is account-personalized; listening history and likes bias its radio seeds. */
internal fun chooseHomeRadioSeed(shelves: List<HomeShelf>, recent: List<Track>, liked: Set<String>,
    currentVideoId: String?, random: Random = Random.Default): Track? {
    val candidates = (shelves.flatMap { it.tracks } + recent).filter { !it.ytVideoId.isNullOrBlank() }
        .distinctBy { it.ytVideoId }
    val available = candidates.filter { it.ytVideoId != currentVideoId }.ifEmpty { candidates }
    if (available.isEmpty()) return null
    val familiarArtists = recent.map { it.artist.lowercase() }.filter { it.isNotBlank() }.toSet()
    val weighted = available.flatMap { track ->
        List(1 + (if (track.ytVideoId in liked) 3 else 0) +
            (if (track.artist.lowercase() in familiarArtists) 2 else 0)) { track }
    }
    return weighted[random.nextInt(weighted.size)]
}
