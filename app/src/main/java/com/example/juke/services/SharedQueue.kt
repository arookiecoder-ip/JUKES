package com.example.juke.services

/** A restored or cleared phone queue must not be published before its cursor is ready. */
fun canPublishPhoneQueue(size: Int, index: Int, videoId: String?): Boolean =
    index in 0 until size && !videoId.isNullOrBlank()

/** Server cursor wins when it describes this song; otherwise retain the local duplicate occurrence. */
fun sharedQueueIndex(videoIds: List<String>, currentVideo: String, snapshotVideo: String,
    reportedIndex: Int, localIndex: Int): Int = when {
    snapshotVideo == currentVideo && videoIds.getOrNull(reportedIndex) == currentVideo -> reportedIndex
    videoIds.getOrNull(localIndex) == currentVideo -> localIndex
    else -> videoIds.indexOf(currentVideo)
}

fun queueIndexAfterMove(current: Int, from: Int, to: Int): Int = when {
    current == from -> to
    from < current && to >= current -> current - 1
    from > current && to <= current -> current + 1
    else -> current
}

/** Occurrences retain IDs across transfers; adding the same saved song creates a new occurrence. */
fun stableQueueEntries(tracks: List<com.example.juke.models.Track>, reservedIds: Set<String> = emptySet()): List<com.example.juke.models.Track> {
    val seen = reservedIds.toMutableSet()
    return tracks.map { track ->
        if (track.uuid.isNotBlank() && seen.add(track.uuid)) track
        else track.copy(uuid = java.util.UUID.randomUUID().toString()).also { seen.add(it.uuid) }
    }
}
