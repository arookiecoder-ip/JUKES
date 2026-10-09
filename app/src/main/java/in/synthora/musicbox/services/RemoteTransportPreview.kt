package `in`.synthora.musicbox.services

import `in`.synthora.musicbox.models.Track
import `in`.synthora.musicbox.viewmodels.MusicUiState

/** A local presentation intent, never an assertion that the receiver has acknowledged it. */
internal data class RemoteTransportPreview(
    val owner: String, val action: String, val track: Track?, val index: Int,
    val playing: Boolean, val position: Long, val startedAt: Long,
    val waiting: Boolean = false, val error: String? = null, val baselineRevision: Long = -1
) {
    fun positionAt(now: Long): Long = (position + if (playing && !waiting && error == null)
        (now - startedAt).coerceAtLeast(0) else 0).coerceAtLeast(0)

    fun present(state: MusicUiState, now: Long): MusicUiState {
        if (error != null) return state.copy(error = error, isLoading = false)
        val duration = track?.durationSec?.times(1000L) ?: state.duration
        return state.copy(currentTrack = track ?: state.currentTrack, queueIndex = index,
            duration = duration, position = positionAt(now).let { if (duration > 0) it.coerceAtMost(duration) else it },
            isPlaying = playing, isLoading = waiting, error = null)
    }

    fun matches(state: EchoState, now: Long): Boolean {
        if (state.sharedOutput.mode != "phone" || state.sharedOutput.owner != owner ||
            state.sharedOutput.handoffPending || state.processing || !state.confirmed ||
            (baselineRevision >= 0 && state.playbackRevision >= 0 && state.playbackRevision <= baselineRevision)) return false
        return when (action) {
            "play", "pause" -> state.playing == playing
            "seek" -> sameTrack(track, state.track) && (kotlin.math.abs(state.livePosition(now) - positionAt(now)) <= 2_000 ||
                kotlin.math.abs(state.positionMs - position) <= 2_000)
            "next", "previous" -> sameTrack(track, state.track) && state.index == index && state.playing == playing
            else -> false
        }
    }
}

internal fun remoteTransportPreview(owner: String, action: String, state: MusicUiState,
    now: Long, seek: Long? = null): RemoteTransportPreview {
    val targetIndex = when (action) {
        "next" -> (state.queueIndex + 1).takeIf { it in state.queue.indices }
        "previous" -> (state.queueIndex - 1).takeIf { it in state.queue.indices }
        else -> null
    }
    val target = targetIndex?.let(state.queue::get) ?: state.currentTrack
    val position = when (action) {
        "seek" -> (seek ?: state.position).coerceIn(0, state.duration.takeIf { it > 0 } ?: Long.MAX_VALUE)
        "next", "previous" -> 0L
        else -> state.position
    }
    return RemoteTransportPreview(owner, action, target, targetIndex ?: state.queueIndex,
        when (action) { "play" -> true; "pause" -> false; else -> state.isPlaying }, position, now)
}

private fun sameTrack(a: Track?, b: Track?): Boolean = a != null && b != null &&
    (a.uuid == b.uuid || (a.ytVideoId != null && a.ytVideoId == b.ytVideoId))
