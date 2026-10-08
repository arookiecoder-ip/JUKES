package `in`.synthora.musicbox.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SilentPlaybackWatchdogTest {

    @Test fun digitalSilenceHasZeroDeviation() {
        assertEquals(0, waveformPeakDeviation(ByteArray(256) { 128.toByte() }))
    }

    @Test fun audibleSignalExceedsThreshold() {
        // A loud 8-bit wave swings far from the 128 center.
        val loud = ByteArray(256) { i -> if (i % 2 == 0) 200.toByte() else 60.toByte() }
        assertTrue(waveformPeakDeviation(loud) > 8)
    }

    @Test fun quietButAudibleSignalStillCounts() {
        // Small but real deviation (e.g. a quiet intro) must not read as silent.
        val quiet = ByteArray(256) { i -> (128 + if (i % 4 == 0) 12 else -12).toByte() }
        assertTrue(waveformPeakDeviation(quiet) >= 8)
    }

    @Test fun nearSilentNoiseFloorStaysBelowThreshold() {
        // Dither-level wobble around silence must not count as sound.
        val floor = ByteArray(256) { i -> (128 + (i % 3 - 1) * 2).toByte() }
        assertTrue(waveformPeakDeviation(floor) < 8)
    }

    @Test fun emptyCaptureIsSilent() {
        assertEquals(0, waveformPeakDeviation(ByteArray(0)))
    }
}
