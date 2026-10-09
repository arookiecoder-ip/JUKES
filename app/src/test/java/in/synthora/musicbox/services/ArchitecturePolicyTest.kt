package `in`.synthora.musicbox.services

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
        assertEquals(5, preloadPolicy(true, true, true, false).tracks)
        assertEquals(5, preloadPolicy(true, false, false, false).tracks)
        assertEquals(-1L, preloadPolicy(true, false, false, false).bytesPerTrack)
    }
    @Test fun `playback phases preserve real buffering without treating paused metadata as loading`() {
        assertEquals(PlaybackPhase.PAUSED, playbackPhase(true, false, false, false, false, false))
        assertEquals(PlaybackPhase.BUFFERING, playbackPhase(true, false, false, true, false, false))
        assertEquals(PlaybackPhase.SWITCHING, playbackPhase(true, true, true, true, false, false))
        assertEquals(PlaybackPhase.FAILED, playbackPhase(true, false, false, true, false, true))
        assertEquals(PlaybackPhase.PLAYING, playbackPhase(true, false, false, false, true, false))
    }
    @Test fun `diagnostics only retain bounded aggregate measurements`() {
        val before = PlaybackDiagnostics.snapshot()[PlaybackDiagnostics.Stage.RECOVERY]?.count ?: 0
        PlaybackDiagnostics.record(PlaybackDiagnostics.Stage.RECOVERY, -5, true)
        val recorded = PlaybackDiagnostics.snapshot().getValue(PlaybackDiagnostics.Stage.RECOVERY)
        assertEquals(before + 1, recorded.count)
        assertTrue(recorded.failures > 0)
        assertTrue(recorded.totalMs >= 0)
        assertTrue(PlaybackDiagnostics.snapshot().size <= PlaybackDiagnostics.Stage.entries.size)
    }
}
