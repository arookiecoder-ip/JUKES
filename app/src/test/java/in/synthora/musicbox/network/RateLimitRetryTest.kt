package `in`.synthora.musicbox.network
import org.junit.Assert.*
import org.junit.Test
class RateLimitRetryTest {
    @Test fun shortCooldownIsRespected() { assertEquals(3000L, rateLimitReadRetryDelay("3")); assertEquals(1000L, rateLimitReadRetryDelay("0")) }
    @Test fun invalidOrLongCooldownNeverCausesUnboundedRetries() {
        listOf(null, "-1", "999999", "invalid").forEach { assertNull(rateLimitReadRetryDelay(it)) }
    }
    @Test fun streamingCooldownPreservesServerDelayAndDoesNotHammerOnMissingHeader() {
        assertEquals(7000L, audioRetryDelay(429, "7", 1))
        assertEquals(60000L, audioRetryDelay(429, null, 1))
        assertEquals(60000L, audioRetryDelay(429, "90000", 1))
        assertEquals(1000L, audioRetryDelay(502, null, 1))
    }
    @Test fun downloadRetriesAreLimitedAndDoNotRetryMissingOrUnauthorizedSongs() {
        assertTrue(shouldRetryRateLimitedDownload(429, 0)); assertTrue(shouldRetryRateLimitedDownload(429, 1))
        assertFalse(shouldRetryRateLimitedDownload(429, 2))
        assertFalse(shouldRetryRateLimitedDownload(404, 0)); assertFalse(shouldRetryRateLimitedDownload(401, 0))
    }
}
