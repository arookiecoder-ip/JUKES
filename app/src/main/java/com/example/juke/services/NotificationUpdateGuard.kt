package com.example.juke.services

/** Async artwork callbacks can reach startForeground after the service call has returned. */
internal inline fun deliverNotificationUpdate(update: () -> Unit,
    isForegroundDenied: (IllegalStateException) -> Boolean, onDenied: () -> Unit) {
    try { update() }
    catch (error: IllegalStateException) {
        if (!isForegroundDenied(error)) throw error
        onDenied()
    }
}
