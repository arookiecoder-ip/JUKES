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
