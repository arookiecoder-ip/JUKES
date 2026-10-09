package `in`.synthora.musicbox.models

import `in`.synthora.musicbox.network.BrowseParser
import `in`.synthora.musicbox.network.toTrack
import `in`.synthora.musicbox.network.metadata
import `in`.synthora.musicbox.viewmodels.MusicUiState
import `in`.synthora.musicbox.viewmodels.withPendingPlayback
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import `in`.synthora.musicbox.services.resolveTracks

class PlaybackPresentationTest {
    @Test fun liveMetadataUpdatesArtworkAndMissingCurrentRowsStillChangeTheSong() {
        val old = Track("old", "Old", "Artist", durationSec = 100)
        val stored = Track("next", "Stale", "Stale artist", thumbnailUri = "stale.jpg", durationSec = 100, ytVideoId = "video")
        val live = stored.copy(title = "New", artist = "New artist", thumbnailUri = "new.jpg", durationSec = 200, ytVideoId = null)
        val snapshot = `in`.synthora.musicbox.services.PhonePlaybackSnapshot(listOf("old", "next"), "next", live)
        val resolved = snapshot.resolveTracks(mapOf("old" to old, "next" to stored))
        assertEquals("New", resolved[1].title)
        assertEquals("new.jpg", resolved[1].thumbnailUri)
        assertEquals("video", resolved[1].ytVideoId)
        val missing = snapshot.resolveTracks(mapOf("old" to old))
        val state = `in`.synthora.musicbox.viewmodels.reconcilePhonePlayback(MusicUiState(currentTrack = old), missing, "next")
        assertEquals(live, state.currentTrack)
    }

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

    @Test fun combinedIdlessCreditMatchesWebappAndSplitsScreenshotArtists() {
        val byline = "Sai Abhyankkar and Shruti Haasan and Vivek"
        val track = Track("song", "Song", byline, durationSec = 10,
            artists = listOf(ArtistCredit(byline)))
        assertEquals(listOf("Sai Abhyankkar", "Shruti Haasan", "Vivek"),
            track.artistCredits().map { it.name })
    }

    @Test fun authoritativeNamesContainingConjunctionsRemainOneArtist() {
        for (name in listOf("Of Monsters and Men", "Florence and the Machine", "Simon & Garfunkel", "Earth, Wind & Fire")) {
            val credit = ArtistCredit(name, "UCband")
            assertEquals(listOf(credit), Track("song", "Song", name, durationSec = 10,
                artists = listOf(credit)).artistCredits())
        }
        val credits = listOf(ArtistCredit("Of Monsters and Men", "UCband"), ArtistCredit("Guest"))
        assertEquals(credits, Track("song", "Song", "Unused", durationSec = 10, artists = credits).artistCredits())
        assertEquals(listOf(ArtistCredit("Simon & Garfunkel")),
            Track("song", "Song", "Simon & Garfunkel", durationSec = 10).artistCredits())
    }

    @Test fun legacySeparatorsBlankIdsAndOxfordCommaKeepCorrectPrimaryId() {
        for (byline in listOf("A, B, and C", " A AND B featuring C ", "A · B ft. C")) {
            assertEquals(listOf(ArtistCredit("A", "UCfirst"), ArtistCredit("B"), ArtistCredit("C")),
                Track("song", "Song", byline, durationSec = 10, artistId = "UCfirst",
                    artists = listOf(ArtistCredit(byline, " "))).artistCredits())
        }
        assertTrue(Track("song", "Song", " ", durationSec = 10).artistCredits().isEmpty())
        assertEquals(listOf(ArtistCredit("A")),
            Track("song", "Song", "A and a", durationSec = 10).artistCredits())
    }

    @Test fun queueMetadataPreservesAuthoritativeBandNamesOnRoundTrip() {
        val credits = listOf(ArtistCredit("Of Monsters and Men", "UCband"), ArtistCredit("Guest", "UCguest"))
        val track = Track("song", "Song", "Of Monsters and Men, Guest", durationSec = 10,
            ytVideoId = "video", artists = credits)
        assertEquals(credits, BrowseParser.item(track.metadata()).toTrack().artistCredits())
    }

}
