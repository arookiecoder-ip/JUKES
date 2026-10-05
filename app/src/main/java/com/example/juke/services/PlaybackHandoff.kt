package com.example.juke.services

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Seeking a paused shared song cannot start Alexa; active handoffs need explicit Resume. */
internal suspend fun startTransferredAlexaQueue(playing: Boolean, installPaused: suspend () -> Unit,
    resume: suspend () -> Unit, keepPaused: suspend () -> Unit) {
    installPaused()
    if (playing) resume() else keepPaused()
}

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
