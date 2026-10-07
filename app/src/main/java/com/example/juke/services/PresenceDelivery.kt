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
