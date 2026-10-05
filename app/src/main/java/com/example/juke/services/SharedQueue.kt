package com.example.juke.services

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
