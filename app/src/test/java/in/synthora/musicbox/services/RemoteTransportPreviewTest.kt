package `in`.synthora.musicbox.services

import `in`.synthora.musicbox.models.Track
import `in`.synthora.musicbox.viewmodels.MusicUiState
import org.junit.Assert.*
import org.junit.Test

class RemoteTransportPreviewTest {
    private val one = Track("one", "One", "Artist", durationSec = 180, ytVideoId = "one")
    private val two = Track("two", "Two", "Artist", durationSec = 180, ytVideoId = "two")
    private val ui = MusicUiState(currentTrack = one, queue = listOf(one, two), queueIndex = 0,
        isPlaying = true, position = 30_000, duration = 180_000)
    private fun snapshot(playing: Boolean = false) = EchoState(track = one, queue = ui.queue, index = 0,
        playing = playing, positionMs = 30_000, anchoredAt = 100, confirmed = true,
        sharedOutput = SharedPlaybackOutput(mode = "phone", owner = "receiver", token = "token"))

    @Test fun pauseIsImmediateAndStalePlayingSnapshotCannotUndoIt() {
        val preview = remoteTransportPreview("receiver", "pause", ui, 100)
        val shown = preview.present(ui, 300)
        assertFalse(shown.isPlaying); assertFalse(shown.isLoading)
        assertEquals(30_000L, shown.position)
        assertFalse(preview.matches(snapshot(true), 300))
        assertTrue(preview.matches(snapshot(false), 300))
    }
    @Test fun nextShowsPredictedSongBeforeReceiverResponds() {
        val preview = remoteTransportPreview("receiver", "next", ui, 100)
        assertEquals(two, preview.present(ui, 100).currentTrack)
        assertEquals(1, preview.present(ui, 100).queueIndex)
        assertFalse(preview.matches(snapshot(true), 100))
        assertTrue(preview.matches(snapshot(true).copy(track = two, index = 1), 100))
    }
    @Test fun seekMovesClockImmediatelyAndAcknowledgesDelayedReceiverAnchor() {
        val preview = remoteTransportPreview("receiver", "seek", ui, 100, 90_000)
        assertEquals(90_200L, preview.present(ui, 300).position)
        assertFalse(preview.matches(snapshot(true), 300))
        assertFalse(preview.matches(snapshot(true).copy(track = two, positionMs = 90_000), 300))
        assertTrue(preview.copy(waiting = true).matches(snapshot(true).copy(positionMs = 90_000), 5_000))
    }
    @Test fun loadingAndErrorsAppearOnlyWhenConfirmationDoesNotArrive() {
        val preview = remoteTransportPreview("receiver", "play", ui.copy(isPlaying = false), 100)
        assertTrue(preview.present(ui, 100).isPlaying)
        assertFalse(preview.present(ui.copy(isLoading = true), 100).isLoading)
        assertTrue(preview.copy(waiting = true).present(ui, 1_100).isLoading)
        val failed = preview.copy(error = "No reply").present(ui.copy(isPlaying = false), 2_100)
        assertEquals("No reply", failed.error); assertFalse(failed.isPlaying)
    }
    @Test fun WrongDeviceOrUnconfirmedSnapshotCannotAcknowledgeIntent() {
        val preview = remoteTransportPreview("receiver", "pause", ui, 100)
        assertFalse(preview.matches(snapshot().copy(confirmed = false), 100))
        assertFalse(preview.matches(snapshot().copy(processing = true), 100))
        assertFalse(preview.matches(snapshot().copy(sharedOutput = SharedPlaybackOutput(mode = "phone", owner = "other")), 100))
        assertFalse(preview.matches(snapshot().copy(sharedOutput = snapshot().sharedOutput.copy(handoffPending = true)), 100))
    }
    @Test fun matchingOldSnapshotStillRequiresANewerReceiverRevision() {
        val preview = remoteTransportPreview("receiver", "pause", ui, 100).copy(baselineRevision = 5)
        assertFalse(preview.matches(snapshot().copy(playbackRevision = 5), 100))
        assertFalse(preview.matches(snapshot().copy(playbackRevision = 4), 100))
        assertTrue(preview.matches(snapshot().copy(playbackRevision = 6), 100))
        assertTrue(preview.matches(snapshot(), 100)) // Compatibility with older servers.
        assertTrue(preview.copy(baselineEpoch = "old").matches(snapshot().copy(playbackRevision = 1,
            sharedOutput = snapshot().sharedOutput.copy(epoch = "restarted")), 100))
    }
    @Test fun previousRestartsCurrentSongAfterThreeSecondsAndWaitsForResetClock() {
        val preview = remoteTransportPreview("receiver", "previous", ui, 100)
        assertEquals(one, preview.track); assertEquals(0, preview.index)
        assertFalse(preview.matches(snapshot(true), 100))
        assertTrue(preview.matches(snapshot(true).copy(positionMs = 0), 100))
        val previous = remoteTransportPreview("receiver", "previous", ui.copy(currentTrack = two,
            queueIndex = 1, position = 1_000, isPlaying = false), 100)
        assertEquals(one, previous.track); assertTrue(previous.playing)
        assertTrue(remoteTransportPreview("receiver", "next", ui.copy(isPlaying = false), 100).playing)
    }
    @Test fun seekClampsAndQueueBoundaryNeverPredictsNonexistentSong() {
        val seek = remoteTransportPreview("receiver", "seek", ui, 100, Long.MAX_VALUE)
        assertEquals(180_000L, seek.position)
        val next = remoteTransportPreview("receiver", "next", ui.copy(currentTrack = two, queueIndex = 1), 100)
        assertEquals(two, next.track); assertEquals(1, next.index)
    }
}
