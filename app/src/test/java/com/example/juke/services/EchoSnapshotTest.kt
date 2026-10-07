package com.example.juke.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class EchoSnapshotTest {
    @Test fun pauseAcknowledgmentNeverShowsPlaybackLoading() {
        assertFalse(EchoState(playing = false, processing = true, confirmed = false).loading)
        assertFalse(EchoState(playing = false, processing = true, confirmed = true).loading)
    }
    @Test fun realResumeAndNewSongPreparationStillShowLoading() {
        assertTrue(EchoState(playing = true, processing = true, confirmed = false).loading)
        assertTrue(EchoState(playing = true, processing = false, confirmed = false).loading)
    }
    @Test fun confirmedPlaybackDoesNotLoadWhenVolumeChanges() {
        assertFalse(EchoState(playing = true, processing = false, confirmed = true, volume = 70).loading)
    }

    private fun snapshot(json: String) = parseEchoSnapshot(Json.parseToJsonElement(json) as JsonObject, 1_000, null, false)

    @Test fun unknownVolumeStaysUnknownInsteadOfBecomingZero() {
        assertNull(snapshot("""{"volume":null}""").volume)
        assertEquals(0, snapshot("""{"volume":0}""").volume)
    }

    @Test fun duplicateSongsKeepTheCorrectQueueIndexAndIdentity() {
        val state = snapshot("""{"video_id":"AAAAAAAAAAA","queue_index":1,"queue":[
            {"video_id":"AAAAAAAAAAA","title":"First"},{"video_id":"AAAAAAAAAAA","title":"Second"}]}""")
        assertEquals(1, state.index)
        assertEquals("Second", state.track!!.title)
        assertEquals(state.queue[1].uuid, state.track.uuid)
    }

    @Test fun missingQueueIndexDoesNotSelectFirstSong() {
        val state = snapshot("""{"video_id":"AAAAAAAAAAA","title":"Current","queue":[{"video_id":"AAAAAAAAAAA","title":"Queued"}]}""")
        assertEquals(-1, state.index)
        assertEquals("Current", state.track!!.title)
    }

    @Test fun progressWaitsForConfirmationAndNeverExceedsDuration() {
        val unconfirmed = snapshot("""{"playing":true,"playback_confirmed":false,"position_ms":5000,"duration_ms":10000}""")
        assertEquals(5000, unconfirmed.livePosition(9_000))
        assertEquals(10000, unconfirmed.copy(confirmed = true).livePosition(9_000))
        assertEquals(5000, unconfirmed.copy(playing = false, confirmed = true).livePosition(9_000))
    }

    @Test fun staleVolumeDoesNotUndoSliderWhileGraceWindowIsActive() {
        val raw = Json.parseToJsonElement("""{"volume":20}""") as JsonObject
        assertEquals(75, parseEchoSnapshot(raw, 1_000, 75, true).volume)
        assertEquals(20, parseEchoSnapshot(raw, 1_000, 75, false).volume)
    }
    @Test fun liveMetadataReplacesStaleQueueArtworkWithoutLosingQueueIdentity() {
        val state = snapshot("""{"video_id":"AAAAAAAAAAA","title":"Live title","thumbnail_url":"large.jpg","queue_index":0,"queue":[{"video_id":"AAAAAAAAAAA","title":"Old title","thumbnail":"tiny.jpg"}]}""")
        assertEquals("Live title", state.track?.title)
        assertEquals("large.jpg", state.track?.thumbnailUri)
        assertEquals(state.queue[0].uuid, state.track?.uuid)
        assertEquals(0, state.index)
    }

    @Test fun stableQueueIdsSurviveReorderAndSlimSnapshots() {
        val first = snapshot("""{"video_id":"song-a","queue_index":0,"queue_version":5,"queue":[{"video_id":"song-a","entry_id":"first"},{"video_id":"song-a","entry_id":"second"}]}""")
        assertEquals(listOf("first", "second"), first.queue.map { it.uuid })
        val slim = parseEchoSnapshot(Json.parseToJsonElement("""{"video_id":"song-a","queue_index":1,"queue_version":5}""") as JsonObject,
            2_000, null, false, first.queue)
        assertEquals(first.queue, slim.queue)
        assertEquals("second", slim.track?.uuid)
        assertEquals(5L, slim.queueVersion)
        val empty = parseEchoSnapshot(Json.parseToJsonElement("""{"queue":[]}""") as JsonObject,
            2_000, null, false, first.queue)
        assertTrue(empty.queue.isEmpty())
    }
    @Test fun closedPhoneImmediatelyFreezesClockWithoutLosingQueue() {
        val original = snapshot("""{"video_id":"AAAAAAAAAAA","queue_index":0,"queue":[{"video_id":"AAAAAAAAAAA","title":"Song"}],"playing":true,"playback_confirmed":true,"position_ms":4000,"duration_ms":120000}""")
        val closed = SharedPlaybackOutput(mode = "phone", owner = "", token = "closed")
        val paused = original.withDisconnectedPhone(closed, 2000)
        assertFalse(paused.playing)
        assertFalse(paused.loading)
        assertEquals(5000L, paused.livePosition(9000))
        assertSame(original.queue, paused.queue)
        assertEquals(original.track, paused.track)
        assertEquals(closed, paused.sharedOutput)
        assertEquals(5000L, paused.withDisconnectedPhone(closed, 10000).livePosition(12000))
    }

    @Test fun livePhoneOrAlexaPresenceDoesNotPausePlayback() {
        val original = EchoState(playing = true, confirmed = true)
        assertSame(original, original.withDisconnectedPhone(SharedPlaybackOutput(mode = "alexa"), 2000))
        assertSame(original, original.withDisconnectedPhone(SharedPlaybackOutput(mode = "phone", owner = "live-phone"), 2000))
    }
}
