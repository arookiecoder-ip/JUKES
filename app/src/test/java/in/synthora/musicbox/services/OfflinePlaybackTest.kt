package `in`.synthora.musicbox.services

import `in`.synthora.musicbox.models.Track
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflinePlaybackTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun track(file: java.io.File, id: String = file.name) =
        Track(id, id, "Artist", durationSec = 180, localUri = file.toURI().toString(), isStream = true)
    private fun saved(name: String): Track = track(temporary.newFile(name).apply { writeBytes(byteArrayOf(1, 2, 3)) })

    @Test fun readableFileNeedsNoVideoIdAndIsNeverMarkedAsStream() {
        val source = saved("song ü space.mp3")
        val local = requireNotNull(readableDownloadedTrack(source))
        assertNull(local.ytVideoId)
        assertFalse(local.isStream)
        assertEquals(source.localUri, local.localUri)
    }
    @Test fun missingEmptyDirectoryAndNetworkLocationsAreUnavailable() {
        assertNull(readableDownloadedTrack(track(java.io.File(temporary.root, "missing"))))
        assertNull(readableDownloadedTrack(track(temporary.newFile("empty"))))
        assertNull(readableDownloadedTrack(track(temporary.newFolder("directory"))))
        assertNull(readableDownloadedTrack(saved("valid").copy(localUri = "https://example.com/song.mp3")))
    }
    @Test fun missingNeighborsAreSkippedAndSelectedIndexIsRemapped() {
        val first = saved("first")
        val selected = saved("selected")
        val missing = track(java.io.File(temporary.root, "missing"))
        val result = offlinePlaybackQueue(listOf(missing, first, selected, missing), 2)
        assertEquals(listOf(first.uuid, selected.uuid), result.tracks.map { it.uuid })
        assertEquals(1, result.index)
    }
    @Test fun duplicateOccurrencesKeepTheirOrderAndSelection() {
        val song = saved("song")
        val result = offlinePlaybackQueue(listOf(song, song, song), 1)
        assertEquals(3, result.tracks.size)
        assertEquals(1, result.index)
    }
    @Test(expected = IllegalStateException::class)
    fun missingSelectedSongNeverStartsItsNeighbor() {
        offlinePlaybackQueue(listOf(track(java.io.File(temporary.root, "missing")), saved("neighbor")), 0)
    }
    @Test fun eachOccurrenceIsResolvedOnceToAvoidDeletionRaceChangingIndex() {
        val one = saved("one")
        val two = saved("two")
        val calls = mutableMapOf<String, Int>()
        val result = offlinePlaybackQueue(listOf(one, two), 1) {
            calls[it.uuid] = (calls[it.uuid] ?: 0) + 1
            if (calls[it.uuid] == 1) it else null
        }
        assertEquals(1, result.index)
        assertEquals(mapOf(one.uuid to 1, two.uuid to 1), calls)
    }
    @Test(expected = IllegalArgumentException::class)
    fun invalidSelectionDoesNotStartAnySong() { offlinePlaybackQueue(emptyList(), 0) }
}
