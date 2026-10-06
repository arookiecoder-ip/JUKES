package com.example.juke.network

/** Respect short server cooldowns; long waits and malformed headers remain explicit errors. */
internal fun rateLimitReadRetryDelay(retryAfter: String?): Long? =
    retryAfter?.toLongOrNull()?.takeIf { it in 0..15 }?.let { it.coerceAtLeast(1) * 1000 }

internal fun audioRetryDelay(status: Int?, retryAfter: String?, attempt: Int): Long =
    if (status == 429) retryAfter?.toLongOrNull()?.takeIf { it >= 0 }?.coerceIn(1, 60)?.times(1000) ?: 60_000
    else attempt.coerceIn(1, 2) * 1000L

internal fun shouldRetryRateLimitedDownload(reason: Int, attempts: Int): Boolean = reason == 429 && attempts < 2
