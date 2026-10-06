package com.example.juke

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.example.juke.models.Track
import com.example.juke.network.BrowseParser
import com.example.juke.services.DownloadedCollection
import com.example.juke.ui.components.*
import com.example.juke.ui.screens.DownloadSelection
import com.example.juke.ui.screens.DownloadTrackRow
import com.example.juke.ui.theme.JUKETheme
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CollectionParityRegressionTest {
    @get:Rule val compose = createComposeRule()
    @Test fun downloadedAndAccountCollectionsKeepSameHeroAndActions() {
        val menu = MediaMenuController()
        val online = BrowseParser.item(buildJsonObject { put("kind", "album"); put("browseId", "MPREfixture"); put("title", "Album") })
        val offline = DownloadedCollection(online.id, "album", "Album", "", "Artist", emptyList()).browseItem()
        var downloaded by mutableStateOf(false)
        var played = 0; var shuffled = 0; var queued = 0
        compose.setContent { JUKETheme { CompositionLocalProvider(LocalMediaMenu provides menu) {
            Surface { CollectionDetailHero(if (downloaded) offline else online, "Album", "", "Artist", "Album · 12 tracks",
                onPlay = { played++ }, onShuffle = { shuffled++ }, onQueue = { queued++ },
                options = if (downloaded) listOf(ExtraSongOption(Icons.Default.CheckBoxOutlineBlank, "Select songs") {}) else emptyList()) }
        } } }
        val originalBounds = compose.onNodeWithTag("Collection hero").fetchSemanticsNode().boundsInRoot
        for (local in listOf(false, true)) {
            compose.runOnIdle { downloaded = local }
            assertEquals(originalBounds, compose.onNodeWithTag("Collection hero").fetchSemanticsNode().boundsInRoot)
            compose.onNodeWithTag("Collection artwork", useUnmergedTree = true).assertWidthIsEqualTo(200.dp).assertHeightIsEqualTo(200.dp)
            compose.onNodeWithContentDescription("Play collection").performClick()
            compose.onNodeWithContentDescription("Shuffle collection").performClick()
            compose.onNodeWithContentDescription("Add collection to queue").performClick()
            compose.onNodeWithContentDescription("Share collection").assertIsDisplayed()
            compose.onNodeWithContentDescription("Collection options").performClick()
            assertEquals(if (local) offline else online, menu.item)
            assertEquals(if (local) listOf("Select songs") else emptyList<String>(), menu.extraOptions.map { it.label })
        }
        assertEquals(2, played); assertEquals(2, shuffled); assertEquals(2, queued)
    }
    @Test fun downloadedAlbumRowsKeepNumberingOptionsAndSelection() {
        val menu = MediaMenuController()
        val track = Track("song", "Song", "Artist", ytVideoId = "abcdefghijk", durationSec = 180)
        val selection = DownloadSelection()
        var played = false
        compose.setContent { JUKETheme { CompositionLocalProvider(LocalMediaMenu provides menu) {
            Surface { DownloadTrackRow(track, selection, { played = true }, number = 1, sharpArtwork = false) }
        } } }
        compose.onNodeWithText("1").assertIsDisplayed()
        compose.onNodeWithText("3:00").assertIsDisplayed()
        compose.onNodeWithText("Song").performClick(); assertTrue(played)
        compose.onNodeWithContentDescription("More options").performClick()
        assertEquals(track, menu.track)
        compose.runOnIdle { menu.queueActions!!.select!!() }
        assertEquals(setOf(track.ytVideoId), selection.selected)
        compose.onNode(isToggleable()).assertIsDisplayed()
    }
}
