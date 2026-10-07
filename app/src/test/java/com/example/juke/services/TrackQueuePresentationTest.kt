package com.example.juke.services

import com.example.juke.models.Track
import org.junit.Assert.*
import org.junit.Test

class TrackQueuePresentationTest {
    private fun track(id: String) = Track(id, id, "Artist", durationSec = 180, ytVideoId = id)
    @Test fun progressUpdatesReuseLargeQueueAndItsRows() {
        val presenter = TrackQueuePresentation()
        val queue = (0..999).map { track(it.toString()) }
        val first = presenter.present(queue, emptySet())
        repeat(100) { assertSame(first, presenter.present(queue, emptySet())) }
        assertSame(queue[900], first[900])
    }
    @Test fun likeChangesAndQueueEditsAreReflectedWithoutDroppingSongs() {
        val presenter = TrackQueuePresentation()
        val a = track("a"); val b = track("b")
        val queue = listOf(a, b)
        assertFalse(presenter.present(queue, emptySet())[0].isFavourite)
        assertTrue(presenter.present(queue, setOf("a"))[0].isFavourite)
        val changed = presenter.present(listOf(b, a), setOf("a"))
        assertEquals(listOf("b", "a"), changed.map { it.uuid })
        assertTrue(changed[1].isFavourite)
    }
    @Test fun currentLyricsReplaceOnlyTheMatchingQueueEntry() {
        val presenter = TrackQueuePresentation()
        val a = track("a"); val b = track("b")
        val queue = listOf(a, b)
        val lyrics = a.copy(plainLyrics = "New lyrics")
        val updated = presenter.present(queue, active = lyrics)
        assertEquals("New lyrics", updated[0].plainLyrics)
        assertSame(b, updated[1])
        assertSame(updated, presenter.present(queue, active = lyrics.copy()))
    }
}
