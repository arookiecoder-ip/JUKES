package com.example.juke.services

data class PreloadPolicy(val tracks: Int, val bytesPerTrack: Long)
fun preloadPolicy(online: Boolean, metered: Boolean, allowMetered: Boolean, buffering: Boolean): PreloadPolicy = when {
    !online || buffering -> PreloadPolicy(0, 0)
    metered && !allowMetered -> PreloadPolicy(0, 0)
    metered -> PreloadPolicy(1, 512L * 1024)
    else -> PreloadPolicy(5, 2L * 1024 * 1024)
}
