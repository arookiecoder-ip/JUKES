package com.example.juke.services

/** Resume only the exact playback intent interrupted by an ownership outage. */
internal class PlaybackInterruption {
    private var mediaId: String? = null
    private var token = ""
    val pending: Boolean get() = mediaId != null
    fun remember(id: String?, claim: String, playing: Boolean) {
        if (id != null && claim.isNotBlank() && playing) { mediaId = id; token = claim }
    }
    fun matches(id: String?, claim: String): Boolean = pending && mediaId == id && token == claim
    fun canResume(id: String?, claim: String, permitted: Boolean, handoff: Boolean, inCall: Boolean): Boolean =
        matches(id, claim) && permitted && !handoff && !inCall
    fun clear() { mediaId = null; token = "" }
}

/** Outages keep retrying at a bounded rate, instead of exhausting two attempts forever. */
internal class BackgroundRetry(private val baseMs: Long = 5_000, private val maxMs: Long = 60_000) {
    private var failures = 0
    private var retryAt = 0L
    fun ready(now: Long): Boolean = now >= retryAt
    fun failed(now: Long, minimumDelayMs: Long = 0) {
        val delay = (baseMs * (1L shl failures.coerceAtMost(4))).coerceAtMost(maxMs)
        failures = (failures + 1).coerceAtMost(5)
        retryAt = now + maxOf(delay, minimumDelayMs)
    }
    fun reset() { failures = 0; retryAt = 0 }
}

/** Retain an existing foreground service only for an interrupted local play intent. */
internal fun retainRecoveryNotification(phoneOutput: Boolean, alreadyForeground: Boolean,
    playing: Boolean, leasePending: Boolean, networkPending: Boolean, streamPending: Boolean,
    handingOff: Boolean): Boolean = phoneOutput && alreadyForeground && !playing && !handingOff &&
    (leasePending || networkPending || streamPending)

/** A hung buffering loader is different from silence in real, advancing playback. */
internal class BufferingStall(private val timeoutMs: Long = 30_000) {
    private var mediaId: String? = null
    private var since: Long? = null
    private var position = 0L
    private var buffered = 0L
    fun shouldRecover(id: String?, buffering: Boolean, wantsPlayback: Boolean, permitted: Boolean,
        now: Long, positionMs: Long, bufferedMs: Long): Boolean {
        if (id == null || !buffering || !wantsPlayback || !permitted) {
            mediaId = null; since = null
            return false
        }
        if (mediaId != id || positionMs != position || bufferedMs > buffered || since == null) {
            mediaId = id; since = now; position = positionMs; buffered = bufferedMs
            return false
        }
        if (now - requireNotNull(since) < timeoutMs) return false
        since = now
        return true
    }
}

internal fun deferDeviceCommand(addressedToThisPhone: Boolean, permitted: Boolean, preparing: Boolean): Boolean =
    addressedToThisPhone && (!permitted || preparing)

/** Only a visible app or an already foreground playback service may join the bridge. */
internal fun canJoinRemoteForeground(visible: Boolean, localForeground: Boolean, alreadyJoined: Boolean, phoneOutput: Boolean): Boolean =
    !phoneOutput && (visible || localForeground || alreadyJoined)
