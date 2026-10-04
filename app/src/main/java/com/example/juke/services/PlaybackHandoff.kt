package com.example.juke.services

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Keep the selected output and resume its source if the destination cannot take over. */
internal suspend fun transferPlayback(
    pauseSource: suspend () -> Unit,
    startTarget: suspend () -> Unit,
    restoreSource: suspend () -> Unit,
    stopTarget: suspend () -> Unit,
    commit: () -> Unit
) {
    try {
        pauseSource()
        startTarget()
        commit()
    } catch (failure: Exception) {
        withContext(NonCancellable) {
            runCatching { stopTarget() }.exceptionOrNull()?.let(failure::addSuppressed)
            runCatching { restoreSource() }.exceptionOrNull()?.let(failure::addSuppressed)
        }
        throw failure
    }
}
