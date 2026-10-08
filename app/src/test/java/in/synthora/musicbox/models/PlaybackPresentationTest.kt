package `in`.synthora.musicbox.models

import `in`.synthora.musicbox.network.BrowseParser
import `in`.synthora.musicbox.network.toTrack
import `in`.synthora.musicbox.viewmodels.MusicUiState
import `in`.synthora.musicbox.viewmodels.withPendingPlayback
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class PlaybackPresentationTest {
    @Test fun requestedSongStaysLoadingUntilPlaybackIsConfirmed() {
        val old = Track("old", "Old", "Artist", durationSec = 100)
        val next = Track("next", "Next", "Artist", durationSec = 200)
        val actual = MusicUiState(currentTrack = old, queue = listOf(old, next), position = 8000L, isPlaying = true)
        val pending = withPendingPlayback(actual, next)
        assertEquals(next, pending.currentTrack)
        assertTrue(pending.isLoading)
        assertFalse(pending.isPlaying)
        assertEquals(0L, pending.position)
        assertEquals(200000L, pending.duration)
        assertEquals(actual.queue, pending.queue)
        assertSame(actual, withPendingPlayback(actual, null))
    }
    @Test fun handoffKeepsTheTransferredCursorWhenTheSameItemIsPrepared() {
        val song = Track("entry", "Song", "Artist", durationSec = 200)
        val handedOff = MusicUiState(currentTrack = song, queue = listOf(song), queueIndex = 0, position = 75000L)
        val prepared = `in`.synthora.musicbox.viewmodels.reconcilePhonePlayback(handedOff, listOf(song), "entry")
        assertEquals(75000L, prepared.position)
        assertEquals(song, prepared.currentTrack)
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
    @Test fun actualTrackIdentityWinsOverStaleQueueIndex() {
        val old = Track("old", "Old", "Artist", durationSec = 100, thumbnailUri = "old.jpg")
        val next = Track("next", "Next", "Artist", durationSec = 200, thumbnailUri = "next.jpg")
        val stale = MusicUiState(currentTrack = old, queue = listOf(old, next), queueIndex = 0, position = 8000)
        val result = `in`.synthora.musicbox.viewmodels.reconcilePhonePlayback(stale, listOf(old, next), "next")
        assertEquals(next, result.currentTrack)
        assertEquals(1, result.queueIndex)
        assertEquals("next.jpg", result.currentTrack?.thumbnailUri)
        assertEquals(0L, result.position)
        val reordered = `in`.synthora.musicbox.viewmodels.reconcilePhonePlayback(result, listOf(next, old), "next")
        assertEquals(next, reordered.currentTrack)
        assertEquals(0, reordered.queueIndex)
        assertSame(stale, `in`.synthora.musicbox.viewmodels.reconcilePhonePlayback(stale, listOf(next), "missing"))
    }

}
