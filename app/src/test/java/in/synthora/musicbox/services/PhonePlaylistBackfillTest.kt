package `in`.synthora.musicbox.services

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PhonePlaylistBackfillTest {
    @Test fun failedPublishKeepsAllThousandSongsLocally() = runBlocking {
        val local = mutableListOf<Int>()
        val songs = (1..1000).toList()
        var calls = 0
        try {
            fillPhonePlaylist(songs, 200, { true }, { local.addAll(it) }, {
                calls++
                assertEquals(songs, local)
                throw IllegalStateException("409")
            })
            fail("Server rejection must be reported for retry")
        } catch (_: IllegalStateException) { }
        assertEquals(songs, local)
        assertEquals(1, calls)
    }

    @Test fun songAdvanceDoesNotInvalidateTheSamePlaylist() = runBlocking {
        var currentSong = 1
        val queue = mutableListOf<Int>()
        var published = false
        val filled = fillPhonePlaylist((2..1000).toList(), 200, { true }, {
            queue.addAll(it)
            currentSong++
        }, { published = true })
        assertTrue(filled)
        assertTrue(published)
        assertTrue(currentSong > 1)
        assertEquals(999, queue.size)
    }

    @Test fun newPlaybackIntentStopsOldQueueFillAndNeverPublishesIt() = runBlocking {
        var current = true
        val local = mutableListOf<Int>()
        var published = false
        val filled = fillPhonePlaylist((1..1000).toList(), 200, { current }, {
            local.addAll(it)
            current = false
        }, { published = true })
        assertFalse(filled)
        assertFalse(published)
        assertEquals(200, local.size)
    }
}
