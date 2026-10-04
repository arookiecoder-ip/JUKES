package com.example.juke.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class BrowseParserTest {
    private fun item(json: String) = BrowseParser.item(Json.parseToJsonElement(json).jsonObject)
    @Test fun homeTargetsPreserveEveryEntityKind() {
        val page = BrowseParser.page(Json.parseToJsonElement("""{"shelves":[{"title":"Mixed","items":[{"kind":"track","videoId":"abcdef12345","title":"Song"},{"kind":"album","target":{"id":"MPRE123"},"play":{"playlistId":"OLAK123"},"title":"Album"},{"kind":"artist","target":{"id":"UC123"},"title":"Artist"},{"kind":"station","target":{"id":"VLRD123"},"title":"Station"}]}]}"""), "Home")
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
        val page = BrowseParser.page(Json.parseToJsonElement("""{"title":"My list","tracks":[{"video_id":"abcdef12345","title":"Track"}],"has_more":true,"next_offset":30}"""), "Playlist")
        assertTrue(page.hasMore); assertEquals(30L,page.nextOffset); assertEquals("My list",page.title)
        assertTrue(item("""{"playlistId":"PL123","title":"Editable","editable":true}""").editable)
        assertFalse(item("""{"playlistId":"LM","title":"Likes"}""").editable)
    }
    @Test fun radioAlbumMisclassificationIsCorrected() {
        assertEquals("playlist", item("""{"browseId":"VLRD123","resultType":"album"}""").kind)
    }
    @Test fun malformedOptionalFieldsDoNotCrashTheFeed() {
        val page = BrowseParser.page(Json.parseToJsonElement("""{"shelves":[null, {"title":"Odd","items":[null,{"title":"Empty","thumbnails":null,"artists":null}]}]}"""),"Home")
        assertEquals("Empty",page.shelves.single().items.single().title)
    }
    @Test fun searchArtistsAndSubscriptionsUseBackendIds() {
        val search = BrowseParser.page(Json.parseToJsonElement("""{"all":[],"songs":[{"video_id":"abcdef12345","title":"Song"}],"artists":[{"browseId":"UC123","title":"Artist"}],"albums":[{"browseId":"MPRE123","title":"Album"}],"playlists":[{"playlistId":"PL123","title":"Playlist"}]}"""), "Search")
        assertEquals(setOf("track","artist","album","playlist"), search.shelves.flatMap { it.items }.map { it.kind }.toSet())
        val subscriptions = BrowseParser.page(Json.parseToJsonElement("""{"artists":[{"channel_id":"UC123","name":"Artist","thumbnail":"art"}],"youtube_count":1}"""), "Artists")
        assertEquals("UC123",subscriptions.shelves.single().items.single().id)
        assertEquals("artist",subscriptions.shelves.single().items.single().kind)
    }
    @Test fun exploreSupportsNestedChartsAndMoodTargets() {
        val page = BrowseParser.page(Json.parseToJsonElement("""{"top_songs":{"items":[{"videoId":"abcdef12345","title":"Chart"}]},"moods_and_genres":[{"params":"abc123","title":"Calm"}],"new_releases":[{"browseId":"MPRE123","audioPlaylistId":"OLAK123","title":"Release","type":"Album"}]}"""),"Explore")
        assertEquals(listOf("track","mood","album"),page.shelves.flatMap { it.items }.map { it.kind })
        assertEquals("abc123",page.shelves[1].items.single().id)
    }
    @Test fun normalizedSearchBrowseIdsKeepArtistsAlbumsAndPlaylistsNavigable() {
        val artist = BrowseParser.item(Json.parseToJsonElement("""{"browse_id":"UC123","name":"Singer","thumbnail":"art"}""") as kotlinx.serialization.json.JsonObject, "artists")
        assertEquals("UC123", artist.id)
        assertEquals("Singer", artist.title)
        assertEquals("artist", artist.kind)
        val album = BrowseParser.item(Json.parseToJsonElement("""{"browse_id":"MPRE123","playlist_id":"OLAK123","title":"Album"}""") as kotlinx.serialization.json.JsonObject, "albums")
        assertEquals("MPRE123", album.id)
        assertEquals("OLAK123", album.playlistId)
        val playlist = BrowseParser.item(Json.parseToJsonElement("""{"browse_id":"VLPL123","owner":"Owner","title":"Playlist"}""") as kotlinx.serialization.json.JsonObject, "playlists")
        assertEquals("PL123", playlist.playlistId)
        assertEquals("Owner", playlist.subtitle)
    }
    @Test fun unfilteredArtistHeroRetainsItsNameAndAlbumRadioKeepsPlaylistRouting() {
        val artist = item("""{"category":"Top result","resultType":"artist","browseId":"UC123","artist":"Singer"}""")
        assertEquals("Singer", artist.title)
        assertEquals("artist", artist.kind)
        val station = BrowseParser.item(Json.parseToJsonElement("""{"browse_id":"VLRD123","title":"Mix"}""") as kotlinx.serialization.json.JsonObject, "albums")
        assertEquals("playlist", station.kind)
        assertEquals("RD123", station.playlistId)
    }
}
