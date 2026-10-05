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
