package `in`.synthora.musicbox.services

import org.junit.Assert.*
import org.junit.Test

class PlaybackResumeTest {
    @Test fun truncatedAudioRequiresFreshBytesButTimeoutDoesNot() {
        assertTrue(hasTruncatedAudio(java.io.IOException("wrapped", java.io.EOFException())))
        assertFalse(hasTruncatedAudio(java.net.SocketTimeoutException()))
        assertFalse(hasTruncatedAudio(null))
    }
    @Test fun restoredCursorBelongsOnlyToTheSavedSong() {
        assertEquals(42_000L, restoredResumePosition("a", "a", 42_000, 0))
        assertEquals(7_000L, restoredResumePosition("b", "a", 42_000, 7_000))
        assertEquals(0L, restoredResumePosition("a", "a", -1, 0))
    }
    @Test fun networkAndServerErrorsAllowRecoveryButDecoderErrorsDoNot() {
        assertTrue(isRecoverableAudioLoadError(2001)) // disconnected network
        assertTrue(isRecoverableAudioLoadError(2004)) // HTTP response, including a pending server download
        assertTrue(isRecoverableAudioLoadError(3001)) // malformed cached container
        assertTrue(isRecoverableAudioLoadError(3003)) // malformed manifest
        assertFalse(isRecoverableAudioLoadError(3002)) // unsupported container
        assertFalse(isRecoverableAudioLoadError(4001)) // decoder initialization
        assertFalse(isRecoverableAudioLoadError(1000)) // unspecified internal failure
    }
    @Test fun recentFailureDiagnosticsAreBoundedAndRetainErrorCodes() {
        repeat(12) { PlaybackDiagnostics.recordAudioFailure(3001, listOf("PlaybackException", "ParserException"), null) }
        val recent = PlaybackDiagnostics.recentAudioFailures()
        assertEquals(8, recent.size)
        assertEquals(3001, recent.last().code)
        assertEquals(listOf("PlaybackException", "ParserException"), recent.last().causes)
    }
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
