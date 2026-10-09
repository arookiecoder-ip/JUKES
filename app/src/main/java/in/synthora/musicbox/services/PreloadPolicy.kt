package `in`.synthora.musicbox.services

data class PreloadPolicy(val tracks: Int, val bytesPerTrack: Long)
fun preloadPolicy(online: Boolean, metered: Boolean, allowMetered: Boolean, buffering: Boolean): PreloadPolicy = when {
    !online || buffering -> PreloadPolicy(0, 0)
    metered && !allowMetered -> PreloadPolicy(0, 0)
    else -> PreloadPolicy(5, -1L)
}
