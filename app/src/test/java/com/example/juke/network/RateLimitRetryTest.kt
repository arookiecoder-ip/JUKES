package com.example.juke.network
import org.junit.Assert.*
import org.junit.Test
class RateLimitRetryTest {
    @Test fun shortCooldownIsRespected() { assertEquals(3000L, rateLimitReadRetryDelay("3")); assertEquals(1000L, rateLimitReadRetryDelay("0")) }
    @Test fun invalidOrLongCooldownNeverCausesUnboundedRetries() {
        listOf(null, "-1", "999999", "invalid").forEach { assertNull(rateLimitReadRetryDelay(it)) }
    }
}
