package `in`.synthora.musicbox.utils

import org.junit.Assert.*
import org.junit.Test

class PlaybackTimeTest {
    @Test fun unknownAndInvalidDurationsNeverShowNegativeNumbers() {
        assertEquals("0:00", playbackTime(-9223372036854775807L))
        assertEquals("0:00", playbackTime(-1))
        assertEquals("0:00", playbackTime(0))
    }
    @Test fun actualDurationUsesNormalMusicClock() {
        assertEquals("3:01", playbackTime(181000))
        assertEquals("60:00", playbackTime(3600000))
    }
}
