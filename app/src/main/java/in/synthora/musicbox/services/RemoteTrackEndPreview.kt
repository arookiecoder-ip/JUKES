package `in`.synthora.musicbox.services

/** Presentation only: advancing this preview never sends a next/play command to a receiver. */
internal data class RemoteTrackEndPreview(val baseline: EchoState, val index: Int, val startedAt: Long) {
    fun expired(now: Long): Boolean = now - startedAt >= 30_000

    fun present(latest: EchoState): EchoState = latest.copy(
        track = baseline.queue[index], queue = baseline.queue, index = index,
        positionMs = 0, anchoredAt = startedAt, durationMs = baseline.queue[index].durationSec * 1000L,
        playing = true, confirmed = false, processing = true)

    /** Ignore an unchanged old boundary snapshot; all real changes remain authoritative. */
    fun retainFor(state: EchoState, now: Long): Boolean {
        if (state.remoteBoundaryKey() != baseline.remoteBoundaryKey() || !state.playing ||
            state.sharedOutput.handoffPending || state.queueVersion != baseline.queueVersion || state.queue != baseline.queue) return false
        if (state.playbackRevision >= 0 && baseline.playbackRevision >= 0) {
            if (state.playbackRevision < baseline.playbackRevision) return true
            if (state.playbackRevision > baseline.playbackRevision) return false
        }
        // A repeat-one restart or seek back on the same song overrides the prediction.
        if (state.confirmed && !state.processing && state.livePosition(now) < state.durationMs - 2_000) return false
        return true
    }
}

internal fun EchoState.remoteBoundaryKey(): String = listOf(sharedOutput.mode, sharedOutput.owner,
    sharedOutput.token, sharedOutput.serial, sharedOutput.epoch, queueVersion.toString(), index.toString(), track?.uuid.orEmpty()).joinToString("|")

internal fun remoteTrackEndDelay(state: EchoState, now: Long): Long? {
    if (!state.playing || !state.confirmed || state.processing || state.sharedOutput.handoffPending ||
        state.durationMs <= 0 || state.index !in state.queue.indices ||
        state.queue[state.index].uuid != state.track?.uuid || state.index + 1 !in state.queue.indices) return null
    return (state.durationMs - state.livePosition(now)).coerceAtLeast(0)
}

internal fun remoteTrackEndPreview(state: EchoState, now: Long): RemoteTrackEndPreview? =
    if (remoteTrackEndDelay(state, now) == 0L) RemoteTrackEndPreview(state, state.index + 1, now) else null

/** Revisions are comparable only within the same receiver and backend lifetime. */
internal fun olderRemoteNotificationSnapshot(candidate: EchoState, previous: EchoState): Boolean =
    candidate.sharedOutput.epoch.isNotBlank() && candidate.sharedOutput.epoch == previous.sharedOutput.epoch &&
        candidate.sharedOutput.mode == previous.sharedOutput.mode && candidate.sharedOutput.owner == previous.sharedOutput.owner &&
        candidate.sharedOutput.token == previous.sharedOutput.token && candidate.sharedOutput.serial == previous.sharedOutput.serial &&
        candidate.playbackRevision >= 0 && previous.playbackRevision >= 0 && candidate.playbackRevision < previous.playbackRevision
