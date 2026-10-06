package com.example.juke.viewmodels

import com.example.juke.models.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fast playlist starts: the tapped/first song plays at once, this remainder backfills Up Next. */
class PlaylistRemainderTest {

    private fun track(videoId: String, title: String = videoId) = Track(
        uuid = "uuid-$videoId-$title",
        title = title,
        artist = "Artist",
        durationSec = 180,
        ytVideoId = videoId
    )

    private val playlist = listOf("vid-a", "vid-b", "vid-c", "vid-d").map { track(it) }

    @Test fun orderedKeepsPlaylistOrderAfterThePlayingSong() {
        val remainder = playlistRemainder(playlist, "vid-b", shuffle = false)
        assertEquals(listOf("vid-c", "vid-d"), remainder.map { it.ytVideoId })
    }

    @Test fun orderedFromFirstSongReturnsEverythingAfter() {
        val remainder = playlistRemainder(playlist, "vid-a", shuffle = false)
        assertEquals(listOf("vid-b", "vid-c", "vid-d"), remainder.map { it.ytVideoId })
    }

    @Test fun orderedFromLastSongIsEmpty() {
        assertTrue(playlistRemainder(playlist, "vid-d", shuffle = false).isEmpty())
    }

    @Test fun orderedKeepsLaterDuplicatesOfThePlayingSong() {
        val dupes = listOf(track("vid-a", "first"), track("vid-b"), track("vid-a", "second"))
        val remainder = playlistRemainder(dupes, "vid-a", shuffle = false)
        assertEquals(listOf("vid-b", "vid-a"), remainder.map { it.ytVideoId })
        assertEquals("second", remainder.last().title)
    }

    @Test fun missingSongFallsBackToEverythingButIt() {
        val remainder = playlistRemainder(playlist, "vid-gone", shuffle = false)
        assertEquals(listOf("vid-a", "vid-b", "vid-c", "vid-d"), remainder.map { it.ytVideoId })
    }

    @Test fun shuffleReturnsEveryOtherSongExactlyOnce() {
        val remainder = playlistRemainder(playlist, "vid-a", shuffle = true)
        assertEquals(listOf("vid-b", "vid-c", "vid-d").sorted(), remainder.map { it.ytVideoId }.sorted())
        assertEquals(3, remainder.size)
    }

    @Test fun shuffleNeverRepeatsThePlayingSong() {
        repeat(20) {
            val remainder = playlistRemainder(playlist, "vid-c", shuffle = true)
            assertTrue(remainder.none { it.ytVideoId == "vid-c" })
            assertEquals(listOf("vid-a", "vid-b", "vid-d").sorted(), remainder.map { it.ytVideoId }.sorted())
        }
    }

    @Test fun emptyPlaylistStaysEmpty() {
        assertTrue(playlistRemainder(emptyList(), "vid-a", shuffle = false).isEmpty())
        assertTrue(playlistRemainder(emptyList(), "vid-a", shuffle = true).isEmpty())
    }

    @Test fun backfillChunkingMatchesServerLimits() {
        // The app appends in PLAYLIST_BACKFILL_CHUNK pieces; the phone `extend`
        // endpoint accepts at most 200 tracks per call.
        assertTrue(MusicViewModel.PLAYLIST_BACKFILL_CHUNK <= 200)
    }
}
