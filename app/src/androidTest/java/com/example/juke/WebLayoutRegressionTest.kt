package com.example.juke

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.example.juke.models.ArtistCredit
import com.example.juke.models.Track
import com.example.juke.network.BrowseParser
import com.example.juke.ui.components.*
import com.example.juke.ui.screens.PlayerHeader
import com.example.juke.ui.screens.SongOnlyShelf
import com.example.juke.ui.theme.JUKETheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WebLayoutRegressionTest {
    @get:Rule val compose = createComposeRule()
    private fun screenshot(name: String, node: SemanticsNodeInteraction = compose.onRoot()) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = java.io.File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        java.io.File(directory, "$name.png").outputStream().use {
            node.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    private val song = Track("song", "Song title", "First, Second", durationSec = 180)

    @Test fun wholeClosedSearchBarActivatesTheInput() {
        var opened by mutableStateOf(false)
        compose.setContent { JUKETheme { SearchHeader("Search music", "", {}, opened, { opened = it }, "Search songs") } }
        compose.onNodeWithText("Search music").performClick()
        compose.onNode(hasSetTextAction()).assertIsFocused().performTextInput("Song")
        assertTrue(opened)
    }
    @Test fun songShelfShowsFourCompactRowsAndPlayAll() {
        var selected = -1
        val tracks = (0..4).map { song.copy(uuid = "$it", title = "Song $it") }
        compose.setContent { JUKETheme(darkTheme = true) { Surface { SongOnlyShelf("Trending songs for you", tracks) { selected = it } } } }
        (0..3).forEach { compose.onNodeWithText("Song $it").assertIsDisplayed() }
        screenshot("compact-song-shelf")
        compose.onNodeWithText("Song 3").performClick()
        assertEquals(3, selected)
        compose.onNodeWithText("Play all").performClick()
        assertEquals(0, selected)
    }
    @Test fun searchSongHeroHasSquareWebArtwork() {
        var played = false
        compose.setContent { JUKETheme(darkTheme = true) { Surface { HeroTrackCard(song, { played = true }) } } }
        compose.onNodeWithTag("Search song artwork", useUnmergedTree = true).assertWidthIsEqualTo(84.dp).assertHeightIsEqualTo(84.dp)
        screenshot("search-song-hero")
        compose.onNodeWithText("Song title").performClick()
        assertTrue(played)
    }
    @Test fun lyricsHeaderReplacesMoreWithClose() {
        var closed = false
        compose.setContent { JUKETheme { PlayerHeader({}, {}, {}, {}, {}, false, true, true,
            track = song, showLyrics = true, onToggleLyrics = { closed = true }) } }
        compose.onNodeWithContentDescription("Song options").assertDoesNotExist()
        compose.onNodeWithContentDescription("Hide lyrics").performClick()
        assertTrue(closed)
    }
    @Test fun playerMenuOpensWithEveryOptionVisible() {
        compose.setContent { JUKETheme(darkTheme = true) { Surface { PlayerHeader({}, {}, {}, {}, {}, false, true, true, track = song) } } }
        compose.onNodeWithContentDescription("Song options").performClick()
        compose.onNodeWithText("Lyrics").assertIsDisplayed()
        compose.onNodeWithText("Romanized lyrics: Off").assertIsDisplayed()
        screenshot("expanded-player-menu", compose.onNodeWithTag("Player options", useUnmergedTree = true))
    }
    @Test fun artistPickerListsAndSelectsIndividualArtists() {
        var selected: ArtistCredit? = null
        compose.setContent { JUKETheme { ArtistPickerSheet(listOf(ArtistCredit("First", "UCfirst"), ArtistCredit("Second", "UCsecond")), {}) { selected = it } } }
        compose.onNodeWithText("First").assertIsDisplayed()
        compose.onNodeWithText("Second").performClick()
        assertEquals(ArtistCredit("Second", "UCsecond"), selected)
    }
    @Test fun collectionButtonsPlayShuffleAndQueue() {
        var played = false; var shuffled = false; var queued = false
        val album = BrowseParser.item(Json.parseToJsonElement("""{"browseId":"MPREtest","title":"Album"}""").jsonObject)
        compose.setContent { JUKETheme(darkTheme = true) { Surface { CollectionActions(album, { played = true }, { shuffled = true }, { queued = true }) } } }
        screenshot("collection-actions")
        compose.onNodeWithContentDescription("Play collection").performClick()
        compose.onNodeWithContentDescription("Shuffle collection").performClick()
        compose.onNodeWithContentDescription("Add collection to queue").performClick()
        assertTrue(played && shuffled && queued)
    }
}
