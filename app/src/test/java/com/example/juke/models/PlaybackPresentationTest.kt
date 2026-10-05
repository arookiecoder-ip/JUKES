package com.example.juke.models

import com.example.juke.network.BrowseParser
import com.example.juke.network.toTrack
import com.example.juke.viewmodels.MusicUiState
import com.example.juke.viewmodels.withPendingPlayback
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class PlaybackPresentationTest {
    @Test fun requestedSongStaysLoadingUntilPlaybackIsConfirmed() {
        val old = Track("old", "Old", "Artist", durationSec = 100)
        val next = Track("next", "Next", "Artist", durationSec = 200)
        val actual = MusicUiState(currentTrack = old, queue = listOf(old, next), position = 8000L)
        val pending = withPendingPlayback(actual, next)
        assertEquals(next, pending.currentTrack)
        assertTrue(pending.isLoading)
        assertEquals(0L, pending.position)
        assertEquals(200000L, pending.duration)
        assertEquals(actual.queue, pending.queue)
        assertSame(actual, withPendingPlayback(actual, null))
    }
    @Test fun accountArtistCreditsKeepEveryNameAndId() {
        val raw = Json.parseToJsonElement("""{"videoId":"song","title":"Song","artists":[{"name":"First","id":"UCfirst"},{"name":"Second","id":"UCsecond"}]}""").jsonObject
        val credits = BrowseParser.item(raw).toTrack().artistCredits()
        assertEquals(listOf(ArtistCredit("First", "UCfirst"), ArtistCredit("Second", "UCsecond")), credits)
    }
    @Test fun radioArtistStringCanBeSelectedIndividually() {
        assertEquals(listOf(ArtistCredit("First", "UCfirst"), ArtistCredit("Second")),
            Track("song", "Song", "First, Second", durationSec = 10, artistId = "UCfirst").artistCredits())
        assertEquals(1, Track("solo", "Song", "Solo", durationSec = 10).artistCredits().size)
    }
}
