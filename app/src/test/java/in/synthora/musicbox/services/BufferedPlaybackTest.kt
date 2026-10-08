package `in`.synthora.musicbox.services

import org.junit.Assert.assertEquals
import org.junit.Test

class BufferedPlaybackTest {
    @Test fun unknownAndInvalidTimesHaveNoSecondaryProgress() {
        assertEquals(0f, bufferedPlaybackFraction(1_000, 0), 0f)
        assertEquals(0f, bufferedPlaybackFraction(1_000, -1), 0f)
        assertEquals(0f, bufferedPlaybackFraction(-1, 10_000), 0f)
    }
    @Test fun partialAndCompleteBuffersRemainWithinTheTrack() {
        assertEquals(0.5f, bufferedPlaybackFraction(5_000, 10_000), 0f)
        assertEquals(1f, bufferedPlaybackFraction(20_000, 10_000), 0f)
        assertEquals(1f, bufferedPlaybackFraction(Long.MAX_VALUE, 1), 0f)
    }
}
