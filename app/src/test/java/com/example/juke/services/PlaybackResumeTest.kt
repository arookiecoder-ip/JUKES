package com.example.juke.services

import org.junit.Assert.*
import org.junit.Test

class PlaybackResumeTest {
    @Test fun closedAndFailedSessionsRequireFreshPreparation() {
        assertTrue(shouldReloadPhonePlayback(false, false, false, 0))
        assertTrue(shouldReloadPhonePlayback(true, true, false, 0))
        assertTrue(shouldReloadPhonePlayback(true, false, true, 0))
    }
    @Test fun warmPauseResumesButLongPauseRechecksDownloadsAndServer() {
        assertFalse(shouldReloadPhonePlayback(true, false, false, 59_999))
        assertTrue(shouldReloadPhonePlayback(true, false, false, 60_000))
        assertTrue(shouldReloadPhonePlayback(true, false, false, 86_400_000))
    }
}
