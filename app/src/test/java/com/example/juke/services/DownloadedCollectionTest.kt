package com.example.juke.services

import com.example.juke.models.Track
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class DownloadedCollectionTest {
    private fun song(video: String) = Track(video, video, "Artist", durationSec = 180, ytVideoId = video)
    @Test fun availableSongsRetainCollectionOrderAndExcludeIncompleteDownloads() {
        val a = song("aaaaaaaaaaa"); val b = song("bbbbbbbbbbb"); val c = song("ccccccccccc")
        val collection = DownloadedCollection("PL123", "playlist", "Mix", "image", "Artist", listOf(a, b, c, a))
        val available = collection.available(listOf(c.copy(localUri = "file:///c"), a.copy(localUri = "file:///a")))
        assertEquals(listOf(a.ytVideoId, c.ytVideoId), available.map { it.ytVideoId })
        assertTrue(available.all { it.localUri != null })
    }
    @Test fun collectionSurvivesRestoreWithAccountIdentityAndMetadata() {
        val album = DownloadedCollection("MPRE123", "album", "Album", "image", "Artist", listOf(song("aaaaaaaaaaa")))
        val restored = Json.decodeFromString<DownloadedCollection>(Json.encodeToString(album))
        assertEquals(album, restored)
        assertEquals("album:MPRE123", restored.key)
        assertEquals("MPRE123", restored.browseItem().id)
    }
}
