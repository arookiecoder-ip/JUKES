package com.example.juke.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class EchoSnapshotTest {
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
}
