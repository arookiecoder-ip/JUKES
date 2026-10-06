package com.example.juke.services

import org.junit.Assert.*
import org.junit.Test

class ArchitecturePolicyTest {
    @Test fun `background paused reads slow down while transitions stay responsive`() {
        assertEquals(30_000L, remotePollDelayMs(false, false, false, 0))
        assertEquals(10_000L, remotePollDelayMs(false, true, false, 0))
        assertEquals(3_000L, remotePollDelayMs(true, true, false, 0))
        assertEquals(3_000L, remotePollDelayMs(false, false, true, 0))
        assertEquals(60_000L, remotePollDelayMs(false, false, false, 10))
    }
    @Test fun `preloading never competes with buffering or consumes mobile data by default`() {
        assertEquals(0, preloadPolicy(true, false, false, true).tracks)
        assertEquals(0, preloadPolicy(true, true, false, false).tracks)
        assertEquals(0, preloadPolicy(false, false, true, false).tracks)
        assertEquals(1, preloadPolicy(true, true, true, false).tracks)
        assertEquals(5, preloadPolicy(true, false, false, false).tracks)
        assertEquals(2L * 1024 * 1024, preloadPolicy(true, false, false, false).bytesPerTrack)
    }
}
