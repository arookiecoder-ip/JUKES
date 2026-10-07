package com.example.juke.ui.components.player

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricCenteringTest {
    @Test fun wrappedLineCentersUsingItsFullHeight() {
        assertEquals(-50f, lyricCenterDelta(200, 200, 0, 700), 0f)
        assertEquals(0f, lyricCenterDelta(250, 200, 0, 700), 0f)
    }
    @Test fun contentPaddingDoesNotMoveTheVisualCenter() {
        assertEquals(0f, lyricCenterDelta(25, 50, -300, 400), 0f)
    }
}
