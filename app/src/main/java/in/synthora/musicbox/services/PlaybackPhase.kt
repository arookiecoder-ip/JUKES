package `in`.synthora.musicbox.services

enum class PlaybackPhase { IDLE, RESOLVING, BUFFERING, PLAYING, PAUSED, SWITCHING, FAILED }
fun playbackPhase(hasTrack: Boolean, switching: Boolean, resolving: Boolean, buffering: Boolean,
                  playing: Boolean, failed: Boolean): PlaybackPhase = when {
    switching -> PlaybackPhase.SWITCHING
    resolving -> PlaybackPhase.RESOLVING
    failed -> PlaybackPhase.FAILED
    buffering -> PlaybackPhase.BUFFERING
    !hasTrack -> PlaybackPhase.IDLE
    playing -> PlaybackPhase.PLAYING
    else -> PlaybackPhase.PAUSED
}
