package com.example.juke.services

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Complete the local playlist before publishing, so failed HTTP cannot lose later chunks. */
suspend fun <T> fillPhonePlaylist(remainder: List<T>, chunkSize: Int,
    stillCurrent: () -> Boolean, append: suspend (List<T>) -> Unit, publish: suspend () -> Unit): Boolean {
    require(chunkSize in 1..200)
    for (chunk in remainder.chunked(chunkSize)) {
        currentCoroutineContext().ensureActive()
        if (!stillCurrent()) return false
        append(chunk)
    }
    currentCoroutineContext().ensureActive()
    if (!stillCurrent()) return false
    publish()
    return true
}
