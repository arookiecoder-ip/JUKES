package `in`.synthora.musicbox.services

/** Queue identity and actual playing item delivered together by Media3.onEvents. */
data class PhonePlaybackSnapshot(val queueIds: List<String> = emptyList(), val currentId: String? = null,
    val currentMetadata: `in`.synthora.musicbox.models.Track? = null)

/** Current Media3 metadata wins; missing database rows must not retain the previous song. */
internal fun PhonePlaybackSnapshot.resolveTracks(known: Map<String, `in`.synthora.musicbox.models.Track>): List<`in`.synthora.musicbox.models.Track> =
    queueIds.mapNotNull { id ->
        val stored = known[id]
        val live = currentMetadata?.takeIf { it.uuid == id }
        if (live == null) stored else stored?.copy(
            title = live.title.ifBlank { stored.title },
            artist = live.artist.ifBlank { stored.artist },
            thumbnailUri = live.thumbnailUri ?: stored.thumbnailUri,
            durationSec = live.durationSec.takeIf { it > 0 } ?: stored.durationSec) ?: live
    }
