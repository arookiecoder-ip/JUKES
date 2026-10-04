package com.example.juke.network

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AlexaBackendApiTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun searchContractHandlesBothThumbnailShapesAndNulls() {
        val payload = json.decodeFromString<AlexaBackendApi.FindStreamListResponse>(
            """{"song_info":{"metadata":{"title":"Seed","video_id":"AAAAAAAAAAA",
            "thumbnail":{"url":"https://example.com/seed.jpg"},"duration_ms":123456},
            "stream":{"audio_url":"https://example.com/staging/proxy/?video_id=AAAAAAAAAAA&key=test"}},
            "playlist":[{"video_id":"BBBBBBBBBBB","thumbnail":"https://example.com/b.jpg"},
            {"video_id":"CCCCCCCCCCC","thumbnail":null}]}"""
        )
        val track = payload.songInfo!!.metadata!!.toAppTrack(payload.songInfo!!.stream!!.audioUrl)
        assertEquals("AAAAAAAAAAA", track.ytVideoId)
        assertEquals(123, track.durationSec)
        assertEquals("https://example.com/seed.jpg", track.thumbnailUri)
        assertTrue(track.isStream)
        assertNull(track.spotifyId)
        assertTrue(track.localUri!!.contains("/staging/proxy/"))
        assertEquals("https://example.com/b.jpg", AlexaBackendApi.thumbnailUrl(payload.playlist[0].thumbnail))
        assertNull(AlexaBackendApi.thumbnailUrl(payload.playlist[1].thumbnail))
    }

    @Test fun homeUsesTargetKindsAndDropsCollectionsAndEmptyShelves() {
        val feed = json.decodeFromString<AlexaBackendApi.HomeFeedResponse>(
            """{"schemaVersion":2,"filters":[{"id":"all"}],"shelves":[
            {"id":"mixed","title":"For you","items":[
            {"title":"Song","target":{"kind":"track","id":"AAAAAAAAAAA"},
            "artists":[{"name":"Artist","id":"ignored"}],"image":"https://example.com/a.jpg"},
            {"title":"Duplicate","videoId":"AAAAAAAAAAA","target":{"kind":"track","id":"AAAAAAAAAAA"}},
            {"title":"Playlist","videoId":"BBBBBBBBBBB","target":{"kind":"playlist","id":"PL123"}}]},
            {"id":"albums","title":"Albums","items":[{"target":{"kind":"album","id":"MPRE123"}}]}]}"""
        )
        val shelves = AlexaBackendApi.homeTrackShelves(feed)
        assertEquals(1, shelves.size)
        assertEquals("For you", shelves.single().title)
        assertEquals(1, shelves.single().tracks.size)
        assertEquals("Artist", shelves.single().tracks.single().artist)
        assertEquals("AAAAAAAAAAA", shelves.single().tracks.single().ytVideoId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun homeRejectsUnknownSchema() {
        AlexaBackendApi.homeTrackShelves(AlexaBackendApi.HomeFeedResponse(schemaVersion = 1))
    }
}
