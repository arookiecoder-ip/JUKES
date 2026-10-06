package com.example.juke.network

/** Respect short server cooldowns; long waits and malformed headers remain explicit errors. */
internal fun rateLimitReadRetryDelay(retryAfter: String?): Long? =
    retryAfter?.toLongOrNull()?.takeIf { it in 0..15 }?.let { it.coerceAtLeast(1) * 1000 }
