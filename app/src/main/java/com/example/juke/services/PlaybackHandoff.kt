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
    commit: () -> Unit,
    targetTimeoutMs: Long = 40_000,
    recoveryTimeoutMs: Long = 8_000
) {
    try {
        pauseSource()
        try {
            kotlinx.coroutines.withTimeout(targetTimeoutMs) { startTarget() }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            throw IllegalStateException("Alexa did not respond. The switch was cancelled; please retry.", timeout)
        }
        commit()
    } catch (failure: Exception) {
        withContext(NonCancellable) {
            runCatching { kotlinx.coroutines.withTimeout(recoveryTimeoutMs) { stopTarget() } }.exceptionOrNull()?.let(failure::addSuppressed)
            runCatching { kotlinx.coroutines.withTimeout(recoveryTimeoutMs) { restoreSource() } }.exceptionOrNull()?.let(failure::addSuppressed)
        }
        throw failure
    }
}

/** A failed older switch must never restart over a newer explicit phone play. */
internal fun shouldRestorePhoneSource(originalToken: String, currentToken: String): Boolean =
    currentToken.isBlank() || currentToken == originalToken
