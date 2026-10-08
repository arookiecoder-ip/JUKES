package `in`.synthora.musicbox.services

/** A stale/failed controller must rebuild from local downloads or a fresh server URL. */
internal fun shouldReloadPhonePlayback(connected: Boolean, failed: Boolean, idle: Boolean, pausedForMs: Long): Boolean =
    !connected || failed || idle || pausedForMs >= 60_000

/** Media3 reserves 2000..2999 for IO failures; service recovery can retry these. */
internal fun isRecoverableAudioLoadError(errorCode: Int): Boolean = errorCode in 2000..2999 || isMalformedAudio(errorCode)

internal fun isMalformedAudio(errorCode: Int): Boolean = errorCode == 3001 || errorCode == 3003
