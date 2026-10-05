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
        assertEquals(listOf(a.ytVideoId, c.ytVideoId, a.ytVideoId), available.map { it.ytVideoId })
        assertTrue(available.all { it.localUri != null })
    }
    @Test fun deletedAndRedownloadedCollectionMemberNeverBecomesStandalone() {
        val a = song("aaaaaaaaaaa"); val b = song("bbbbbbbbbbb"); val standalone = song("ccccccccccc")
        val collection = DownloadedCollection("PL123", "playlist", "Mix", "image", "Artist", listOf(a, b))
        assertEquals(listOf(standalone), standaloneDownloads(listOf(b, standalone), listOf(collection)))
        assertEquals(listOf(standalone), standaloneDownloads(listOf(b, standalone, a), listOf(collection)))
        assertEquals(listOf(a.uuid, b.uuid), collection.available(listOf(b, a.copy(uuid = "new-download"))).map { it.uuid })
    }
    @Test fun collectionSurvivesRestoreWithAccountIdentityAndMetadata() {
        val album = DownloadedCollection("MPRE123", "album", "Album", "image", "Artist", listOf(song("aaaaaaaaaaa")))
        val restored = Json.decodeFromString<DownloadedCollection>(Json.encodeToString(album))
        assertEquals(album, restored)
        assertEquals("album:MPRE123", restored.key)
        assertEquals("MPRE123", restored.browseItem().id)
    }
    @Test fun offlineQueueActionsUseOnlyCompletedLocalFilesAndNeverFallbackToOnline() {
        val a = song("aaaaaaaaaaa"); val b = song("bbbbbbbbbbb")
        val collection = DownloadedCollection("PL123", "playlist", "Mix", "", "", listOf(a, b, a))
        val item = collection.browseItem()
        val files = listOf(a.copy(localUri = "file:///a"))
        val tracks = requireNotNull(offlineCollectionTracks(item, listOf(collection), files))
        assertEquals(listOf(a, a).map { it.ytVideoId }, tracks.map { it.ytVideoId })
        assertTrue(tracks.all { it.localUri == "file:///a" && !it.isStream })
        assertEquals(emptyList<Track>(), offlineCollectionTracks(item, listOf(collection), emptyList()))
        assertEquals(emptyList<Track>(), offlineCollectionTracks(item, emptyList(), files))
        assertNull(offlineCollectionTracks(item.copy(raw = kotlinx.serialization.json.JsonObject(emptyMap())), listOf(collection), files))
    }
}
