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
    @Test fun duplicatePlaySharesRequestButPauseInvalidatesEveryLateResult() {
        val commands = ResumeCommandFence()
        val first = commands.play()
        assertEquals(first, commands.play())
        assertTrue(commands.current(first))
        commands.pause()
        assertFalse(commands.current(first))
        val next = commands.play()
        assertNotEquals(first, next)
        assertFalse(commands.current(first))
        assertTrue(commands.current(next))
    }

    @Test fun restoredQueuePreservesDuplicateOccurrenceAndRemapsMissingNeighbors() {
        assertEquals(ResumeCursor(1, 42000), resumeCursor(2, 42000, listOf(0, 2, 3), 180000, false, false))
        assertEquals(ResumeCursor(2, 42000), resumeCursor(2, 42000, listOf(0, 1, 2), 180000, false, false))
        assertEquals(ResumeCursor(0, 0), resumeCursor(9, 42000, listOf(0, 2, 3), 180000, false, false))
        assertEquals(ResumeCursor(0, 0), resumeCursor(0, -1, listOf(0), 180000, false, false))
    }

    @Test fun endedTrackHonorsQueueAndRepeatWithoutLoopingAtEndPosition() {
        assertEquals(ResumeCursor(1, 0), resumeCursor(0, 180000, listOf(0, 1), 180000, false, false))
        assertEquals(ResumeCursor(0, 0), resumeCursor(0, 180001, listOf(0, 1), 180000, true, false))
        assertEquals(ResumeCursor(0, 0), resumeCursor(1, 180000, listOf(0, 1), 180000, false, true))
        assertEquals(ResumeCursor(1, 0), resumeCursor(1, 180000, listOf(0, 1), 180000, false, false))
        assertEquals(ResumeCursor(0, 42000), resumeCursor(0, 42000, listOf(0), 0, false, false))
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyRestorationHasNoPlayableNotification() { resumeCursor(0, 0, emptyList(), 0, false, false) }

    @Test fun otherDeviceAndAlexaAreNeverStolenButExpiredPhoneLeaseCanResumeLocally() {
        assertTrue(blocksLocalResumption(SharedPlaybackOutput(mode = "alexa"), "this"))
        assertTrue(blocksLocalResumption(SharedPlaybackOutput(mode = "phone", owner = "other", leaseMs = 1000), "this"))
        assertTrue(blocksLocalResumption(SharedPlaybackOutput(mode = "phone", owner = "this", handoffPending = true), "this"))
        assertFalse(blocksLocalResumption(SharedPlaybackOutput(mode = "phone", owner = "this", leaseMs = 1000), "this"))
        assertFalse(blocksLocalResumption(SharedPlaybackOutput(mode = "phone", owner = "other", leaseMs = 0), "this"))
        assertFalse(blocksLocalResumption(SharedPlaybackOutput(), "this"))
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun mediaSessionCommandsNeverBypassResumeOrPauseCancellation() {
        val rendererEvents = mutableListOf<String>()
        val renderer = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader,
            arrayOf(androidx.media3.common.Player::class.java)) { _, method, _ ->
            rendererEvents += method.name
            null
        } as androidx.media3.common.Player
        val commands = ResumeCommandFence()
        val requests = mutableListOf<Long>()
        val session = ResumableSessionPlayer(renderer,
            onPlay = { requests += commands.play() },
            onPause = { commands.pause(); renderer.pause() },
            onStop = { commands.pause(); renderer.stop() })
        session.play()
        session.playWhenReady = true
        assertEquals(2, requests.size)
        assertEquals(requests[0], requests[1])
        assertTrue(rendererEvents.isEmpty()) // Authorization/preparation has not completed.
        session.playWhenReady = false
        assertFalse(commands.current(requests[0]))
        assertEquals(listOf("pause"), rendererEvents)
        session.play()
        session.stop()
        assertFalse(commands.current(requests.last()))
        assertEquals(listOf("pause", "stop"), rendererEvents)
    }

}
