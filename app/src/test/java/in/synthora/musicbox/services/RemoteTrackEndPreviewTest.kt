package `in`.synthora.musicbox.services

import `in`.synthora.musicbox.models.Track
import org.junit.Assert.*
import org.junit.Test

class RemoteTrackEndPreviewTest {
    private val tracks = listOf(Track("one", "One", "Artist", durationSec = 180, ytVideoId = "one"),
        Track("two", "Two", "Artist", durationSec = 200, ytVideoId = "two"),
        Track("three", "Three", "Artist", durationSec = 160, ytVideoId = "three"))
    private val state = EchoState(track = tracks[0], queue = tracks, index = 0, playing = true,
        positionMs = 179_000, anchoredAt = 1_000, durationMs = 180_000, confirmed = true,
        queueVersion = 5, playbackRevision = 10,
        sharedOutput = SharedPlaybackOutput(mode = "phone", owner = "receiver", token = "token", epoch = "server"))

    @Test fun bannerAdvancesAtBoundaryWithoutWaitingForServerAndRemainsUnconfirmed() {
        assertEquals(1_000L, remoteTrackEndDelay(state, 1_000))
        assertNull(remoteTrackEndPreview(state, 1_999))
        val preview = requireNotNull(remoteTrackEndPreview(state, 2_000))
        val displayed = preview.present(state)
        assertEquals(tracks[1], displayed.track); assertEquals(1, displayed.index)
        assertEquals(0L, displayed.positionMs); assertEquals(200_000L, displayed.durationMs)
        assertTrue(displayed.playing); assertTrue(displayed.loading); assertFalse(displayed.confirmed)
        assertEquals(tracks[0], state.track)
    }
    @Test fun previewExpiresRatherThanAdvancingThroughUnconfirmedSongs() {
        val preview = requireNotNull(remoteTrackEndPreview(state, 2_000))
        assertFalse(preview.expired(31_999))
        assertTrue(preview.expired(32_000))
        assertNull(remoteTrackEndDelay(preview.present(state), 202_000))
    }
    @Test fun unchangedOldSnapshotCannotFlickerBannerBackAtBoundary() {
        val preview = requireNotNull(remoteTrackEndPreview(state, 2_000))
        assertTrue(preview.retainFor(state, 3_000))
        assertTrue(preview.retainFor(state.copy(volume = 70), 3_000))
        assertTrue(preview.retainFor(state.copy(playbackRevision = 9), 3_000))
    }
    @Test fun confirmedNextOrDifferentShuffleOutcomeWinsImmediately() {
        val preview = requireNotNull(remoteTrackEndPreview(state, 2_000))
        for (index in listOf(1, 2)) assertFalse(preview.retainFor(state.copy(track = tracks[index], index = index,
            positionMs = 100, anchoredAt = 3_000, durationMs = tracks[index].durationSec * 1000L, playbackRevision = 11), 3_000))
    }
    @Test fun newerRevisionOfOldSongIsAuthoritativeIncludingARepeatOrSeekBack() {
        val preview = requireNotNull(remoteTrackEndPreview(state, 2_000))
        assertFalse(preview.retainFor(state.copy(playbackRevision = 11), 3_000))
        assertFalse(preview.retainFor(state.copy(positionMs = 0, anchoredAt = 3_000), 3_000))
        assertFalse(preview.retainFor(state.copy(positionMs = 50_000, anchoredAt = 3_000), 3_000))
    }
    @Test fun pauseDisconnectOutputSwitchAndQueueEditCancelPrediction() {
        val preview = requireNotNull(remoteTrackEndPreview(state, 2_000))
        assertFalse(preview.retainFor(state.copy(playing = false), 3_000))
        assertFalse(preview.retainFor(state.copy(sharedOutput = state.sharedOutput.copy(owner = "other")), 3_000))
        assertFalse(preview.retainFor(state.copy(sharedOutput = state.sharedOutput.copy(token = "new")), 3_000))
        assertFalse(preview.retainFor(state.copy(sharedOutput = state.sharedOutput.copy(handoffPending = true)), 3_000))
        assertFalse(preview.retainFor(state.copy(queueVersion = 6), 3_000))
        assertFalse(preview.retainFor(state.copy(queue = tracks.reversed()), 3_000))
    }
    @Test fun duplicatesUseQueueOccurrenceRatherThanJustVideoId() {
        val duplicate = tracks[0].copy(uuid = "second-occurrence")
        val duplicateState = state.copy(queue = listOf(tracks[0], duplicate))
        val preview = requireNotNull(remoteTrackEndPreview(duplicateState, 2_000))
        assertEquals(duplicate, preview.present(duplicateState).track)
        assertFalse(preview.retainFor(duplicateState.copy(track = duplicate, index = 1, positionMs = 0, anchoredAt = 3_000), 3_000))
    }
    @Test fun unknownDurationMissingQueueLastSongAndUnsettledStateNeverInventNextSong() {
        val invalid = listOf(state.copy(durationMs = 0), state.copy(queue = emptyList()), state.copy(index = -1),
            state.copy(index = 2, track = tracks[2]), state.copy(playing = false), state.copy(confirmed = false),
            state.copy(processing = true), state.copy(sharedOutput = state.sharedOutput.copy(handoffPending = true)))
        invalid.forEach { assertNull(remoteTrackEndDelay(it, 5_000)) }
    }
    @Test fun alexaUsesTheSameBoundaryLogicAndStaleRevisionsDoNotOverrideNewSong() {
        val alexa = state.copy(sharedOutput = SharedPlaybackOutput(mode = "alexa", serial = "echo", epoch = "server"))
        assertNotNull(remoteTrackEndPreview(alexa, 2_000))
        val newer = alexa.copy(track = tracks[1], index = 1, playbackRevision = 11)
        assertTrue(olderRemoteNotificationSnapshot(alexa, newer))
        assertFalse(olderRemoteNotificationSnapshot(alexa.copy(sharedOutput = alexa.sharedOutput.copy(epoch = "restart")), newer))
        assertFalse(olderRemoteNotificationSnapshot(alexa.copy(sharedOutput = alexa.sharedOutput.copy(serial = "other")), newer))
        assertFalse(olderRemoteNotificationSnapshot(alexa.copy(playbackRevision = -1), newer))
    }
}
