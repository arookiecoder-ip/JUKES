package com.example.juke.services

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Presence keeps flowing while playback handles the latest server state. */
internal class PresenceDelivery<T>(scope: CoroutineScope,
    private val onFailure: (Exception) -> Unit = {}, apply: suspend (T) -> Unit) {
    private val updates = Channel<T>(Channel.CONFLATED)
    init {
        scope.launch {
            for (update in updates) {
                try { apply(update) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { onFailure(e) }
            }
        }
    }
    fun offer(update: T) { updates.trySend(update) }
}

/** Keep commands completed during an in-flight presence request for its next send. */
internal class CommandAcknowledgments {
    private val pending = linkedSetOf<String>()
    fun completed(id: String) { pending += id }
    fun snapshot(): List<String> = pending.take(32)
    fun delivered(sent: List<String>) { pending.removeAll(sent.toSet()) }
}

/** Bound outage traffic without treating an expired lease as playback permission. */
internal fun presenceRetryDelay(failures: Int, retryAfterMs: Long): Long =
    maxOf((2_000L shl failures.coerceIn(0, 5)).coerceAtMost(60_000), retryAfterMs.coerceAtLeast(0))

internal data class PublishedCursor(val token: String, val mediaId: String, val playing: Boolean,
    val buffering: Boolean, val position: Long, val at: Long) {
    fun needsPublication(previous: PublishedCursor?): Boolean {
        if (previous == null || token != previous.token || mediaId != previous.mediaId ||
            playing != previous.playing || buffering != previous.buffering) return true
        val elapsed = (at - previous.at).coerceAtLeast(0)
        val expected = previous.position + if (previous.playing && !previous.buffering) elapsed else 0
        return kotlin.math.abs(position - expected) > 1_500 || (playing && elapsed >= 10_000)
    }
}
