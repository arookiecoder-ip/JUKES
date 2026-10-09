package `in`.synthora.musicbox.services

internal fun bufferedPlaybackFraction(bufferedMs: Long, durationMs: Long): Float =
    if (durationMs <= 0 || bufferedMs <= 0) 0f
    else (bufferedMs.toDouble() / durationMs).coerceIn(0.0, 1.0).toFloat()
