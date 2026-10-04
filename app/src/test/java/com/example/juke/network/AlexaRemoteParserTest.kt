package com.example.juke.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class AlexaRemoteParserTest {
    private fun item(json: String) = AlexaRemoteParser.item(Json.parseToJsonElement(json).jsonObject)
    @Test fun homeTargetsPreserveEveryEntityKind() {
        val page = AlexaRemoteParser.page(Json.parseToJsonElement("""{"shelves":[{"title":"Mixed","items":[{"kind":"track","videoId":"abcdef12345","title":"Song"},{"kind":"album","target":{"id":"MPRE123"},"play":{"playlistId":"OLAK123"},"title":"Album"},{"kind":"artist","target":{"id":"UC123"},"title":"Artist"},{"kind":"station","target":{"id":"VLRD123"},"title":"Station"}]}]}"""), "Home")
        assertEquals(listOf("track", "album", "artist", "playlist"), page.shelves.single().items.map { it.kind })
        assertEquals("MPRE123",page.shelves.single().items[1].id)
        assertEquals("OLAK123",page.shelves.single().items[1].playlistId)
        assertEquals("RD123",page.shelves.single().items[3].playlistId)
    }
    @Test fun songRadioSeedDoesNotBecomeAPlaylist() {
        val track = item("""{"videoId":"abcdef12345","audioPlaylistId":"RD123","duration":"3:42","artists":[{"name":"Singer","id":"UC123"}],"album":{"id":"MPRE123"},"thumbnails":[{"url":"small"},{"url":"large"}]}""")
        assertEquals("track", track.kind); assertEquals(222000L, track.durationMs)
        assertEquals("Singer",track.subtitle); assertEquals("UC123",track.artistId)
        assertEquals("MPRE123",track.albumId); assertEquals("large",track.image)
    }
    @Test fun playlistContinuationAndOwnerCapabilitiesSurviveParsing() {
        val page = AlexaRemoteParser.page(Json.parseToJsonElement("""{"title":"My list","tracks":[{"video_id":"abcdef12345","title":"Track"}],"has_more":true,"next_offset":30}"""), "Playlist")
        assertTrue(page.hasMore); assertEquals(30L,page.nextOffset); assertEquals("My list",page.title)
        assertTrue(item("""{"playlistId":"PL123","title":"Editable","editable":true}""").editable)
        assertFalse(item("""{"playlistId":"LM","title":"Likes"}""").editable)
    }
    @Test fun radioAlbumMisclassificationIsCorrected() {
        assertEquals("playlist", item("""{"browseId":"VLRD123","resultType":"album"}""").kind)
    }
    @Test fun malformedOptionalFieldsDoNotCrashTheFeed() {
        val page = AlexaRemoteParser.page(Json.parseToJsonElement("""{"shelves":[null, {"title":"Odd","items":[null,{"title":"Empty","thumbnails":null,"artists":null}]}]}"""),"Home")
        assertEquals("Empty",page.shelves.single().items.single().title)
    }
}
