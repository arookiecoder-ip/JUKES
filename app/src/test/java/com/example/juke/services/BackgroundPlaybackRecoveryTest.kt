package com.example.juke.services

import org.junit.Assert.*
import org.junit.Test

class BackgroundPlaybackRecoveryTest {
    @Test fun expiredLeaseResumesOnlyAfterRenewalOfTheSameIntent() {
        val interruption = PlaybackInterruption()
        interruption.remember("song", "lease", true)
        assertFalse(interruption.canResume("song", "lease", false, false, false))
        assertTrue(interruption.canResume("song", "lease", true, false, false))
        assertFalse(interruption.canResume("other song", "lease", true, false, false))
        assertFalse(interruption.canResume("song", "new lease", true, false, false))
        assertFalse(interruption.canResume("song", "lease", true, true, false))
        assertFalse(interruption.canResume("song", "lease", true, false, true))
    }
    @Test fun manualPauseOrTrackChangeCancelsAutomaticRecovery() {
        val interruption = PlaybackInterruption()
        interruption.remember("song", "lease", true)
        interruption.clear()
        assertFalse(interruption.canResume("song", "lease", true, false, false))
        interruption.remember("song", "lease", false)
        assertFalse(interruption.pending)
        interruption.remember("song", "", true)
        assertFalse(interruption.pending)
    }
    @Test fun aLongServerOutageDoesNotExhaustRecoveryPermanently() {
        val retry = BackgroundRetry()
        var now = 0L
        repeat(100) {
            assertTrue(retry.ready(now))
            retry.failed(now)
            assertFalse(retry.ready(now + 1))
            now += 60_000
            assertTrue(retry.ready(now))
        }
    }
    @Test fun preloadingBacksOffAndCanRestartAfterNetworkReturns() {
        val retry = BackgroundRetry()
        retry.failed(1_000)
        assertFalse(retry.ready(5_999))
        assertTrue(retry.ready(6_000))
        retry.failed(6_000)
        assertFalse(retry.ready(15_999))
        assertTrue(retry.ready(16_000))
        retry.reset()
        assertTrue(retry.ready(6_001))
    }
    @Test fun serverRateLimitOverridesShortRecoveryIntervals() {
        val retry = BackgroundRetry()
        retry.failed(1_000, 60_000)
        assertFalse(retry.ready(60_999))
        assertTrue(retry.ready(61_000))
    }
}
