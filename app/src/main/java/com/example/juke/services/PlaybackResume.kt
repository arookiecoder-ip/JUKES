package com.example.juke.services

/** A stale/failed controller must rebuild from local downloads or a fresh server URL. */
internal fun shouldReloadPhonePlayback(connected: Boolean, failed: Boolean, idle: Boolean, pausedForMs: Long): Boolean =
    !connected || failed || idle || pausedForMs >= 60_000
