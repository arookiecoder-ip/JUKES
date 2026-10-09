package `in`.synthora.musicbox.services

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

/** Overlapping/cancelled song starts cannot release a newer preparation barrier. */
internal class PhonePreparationGuard {
    private var starts = 0
    val active: Boolean @Synchronized get() = starts > 0
    @Synchronized fun begin() { starts++ }
    @Synchronized fun end() { check(starts > 0); starts-- }
}

internal fun animateSongChange(previousVideo: String?, nextVideo: String?, switching: Boolean): Boolean =
    !switching && previousVideo != nextVideo

/** A background song replacement retains an existing foreground service, never creates one. */
internal fun retainSongPreparationNotification(phoneOutput: Boolean, foreground: Boolean, preparing: Boolean): Boolean =
    phoneOutput && foreground && preparing

/** The incoming ownership token is visible before local queue preparation starts. */
internal fun destinationHandoffWaiting(output: SharedPlaybackOutput, thisPhone: String,
    acceptedToken: String, preparing: Boolean): Boolean = output.mode == "phone" &&
    thisPhone.isNotBlank() && output.owner == thisPhone && output.token.isNotBlank() &&
    (output.handoffPending || preparing || output.token != acceptedToken)

internal fun retainDestinationForeground(output: SharedPlaybackOutput, thisPhone: String,
    acceptedToken: String, preparing: Boolean, alreadyForeground: Boolean): Boolean = alreadyForeground &&
    destinationHandoffWaiting(output, thisPhone, acceptedToken, preparing)

/** A connectivity event alone must never discard healthy decoded audio. */
internal fun needsNetworkSourceRecovery(hasError: Boolean, idle: Boolean, buffering: Boolean,
    positionMs: Long, bufferedMs: Long): Boolean =
    hasError || idle || (buffering && bufferedMs <= positionMs)

/** Keep only locally initiated, available audio running through a network partition. */
internal fun canContinueCachedOffline(online: Boolean, remoteControlled: Boolean, localSource: Boolean,
    fullyCached: Boolean, handingOff: Boolean): Boolean =
    !online && !remoteControlled && !handingOff && (localSource || fullyCached)
