package `in`.synthora.musicbox.services

/** A stale/failed controller must rebuild from local downloads or a fresh server URL. */
internal fun shouldReloadPhonePlayback(connected: Boolean, failed: Boolean, idle: Boolean, pausedForMs: Long): Boolean =
    !connected || failed || idle || pausedForMs >= 60_000

/** Media3 reserves 2000..2999 for IO failures; service recovery can retry these. */
internal fun isRecoverableAudioLoadError(errorCode: Int): Boolean = errorCode in 2000..2999 || isMalformedAudio(errorCode)

internal fun isMalformedAudio(errorCode: Int): Boolean = errorCode == 3001 || errorCode == 3003

internal fun restoredResumePosition(trackId: String, savedId: String?, savedPosition: Long, fallback: Long): Long =
    (if (trackId == savedId) savedPosition else fallback).coerceAtLeast(0)

/** EOF identifies truncated audio bytes, unlike an ordinary connectivity timeout. */
internal fun hasTruncatedAudio(error: Throwable?): Boolean =
    generateSequence(error) { it.cause }.take(8).any { it is java.io.EOFException }

/** A broken HTTP transfer is not evidence that previously cached bytes are corrupt. */
internal fun shouldInvalidateAudio(errorCode: Int, truncated: Boolean, invalidResponse: Boolean,
    networkSource: Boolean): Boolean = isMalformedAudio(errorCode) || invalidResponse || (truncated && !networkSource)
