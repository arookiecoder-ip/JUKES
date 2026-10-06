package com.example.juke.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class CollectionContentTest {
    private fun raw(value: String) = Json.parseToJsonElement(value).jsonObject
    @Test fun emptyAlbumCardUsesResolvedTitleCreditsAndArtworkWithoutLosingIdentity() {
        val item = BrowseParser.item(raw("""{"browseId":"MPRE123","title":"Untitled"}"""), "albums")
        val fixed = resolvedCollectionItem(item, raw("""{"title":"Real Album","artist":"Singer","year":"2026","description":"Album details","thumbnail":"hd","tracks":[]}"""))
        assertEquals("Real Album", fixed.title); assertEquals("Singer", fixed.subtitle)
        assertEquals("MPRE123", fixed.id); assertEquals("album", fixed.kind); assertEquals("hd", fixed.image)
        assertEquals("2026", fixed.raw.text("year")); assertFalse(fixed.raw.containsKey("tracks"))
    }
    @Test fun playlistAuthorObjectBecomesReadableCredit() {
        val card = BrowseParser.item(raw("""{"playlistId":"PL123","title":"Mix"}"""))
        val fixed = resolvedCollectionItem(card, raw("""{"title":"Road Trip","author":{"name":"Account"},"thumbnails":[{"url":"art"}]}"""))
        assertEquals("Account", fixed.subtitle); assertEquals("Road Trip", fixed.title); assertEquals("art", fixed.image)
    }
    @Test fun incompleteProviderDetailsRetainSavedMetadata() {
        val card = BrowseParser.item(raw("""{"browseId":"MPRE123","title":"Saved Album","artist":"Singer","thumbnail":"image"}"""), "albums")
        val fixed = resolvedCollectionItem(card, raw("{}"))
        assertEquals(card.title, fixed.title); assertEquals(card.subtitle, fixed.subtitle); assertEquals(card.image, fixed.image)
    }
}
