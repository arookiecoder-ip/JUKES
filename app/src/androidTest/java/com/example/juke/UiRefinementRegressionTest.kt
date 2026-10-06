package com.example.juke

import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.juke.models.Track
import com.example.juke.network.BrowseParser
import com.example.juke.ui.components.player.ExpandableTrackSlider
import com.example.juke.ui.components.player.QueueBottomSheetContent
import com.example.juke.ui.screens.HomeContent
import com.example.juke.ui.theme.JUKETheme
import com.example.juke.viewmodels.HomeShelf
import com.example.juke.viewmodels.HomeUiState
import com.example.juke.viewmodels.MusicUiState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Native UI checks use fixture data and never send playback/account requests. */
class UiRefinementRegressionTest {
    @get:Rule val compose = createComposeRule()
    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = java.io.File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        java.io.File(directory, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    private val current = Track("current", "Current song", "Artist", durationSec = 180)
    private val first = Track("next", "Next song", "Artist", durationSec = 200)
    private val second = Track("last", "Last song", "Artist", durationSec = 190)

    @Test fun homeHeaderScrollsAndArtistArtworkMatchesCollectionArtwork() {
        val collection = BrowseParser.item(Json.parseToJsonElement("""{"playlistId":"PLfixture","title":"Collection"}""").jsonObject)
        val artist = BrowseParser.item(Json.parseToJsonElement("""{"kind":"artist","browseId":"UCfixture","title":"Artist card"}""").jsonObject)
        val shelves = (0..5).map { HomeShelf("s$it", "Shelf $it", "", listOf(collection.copy(id = "p$it")), emptyList()) } +
            HomeShelf("artists", "Artists", "", listOf(artist), emptyList())
        compose.setContent { JUKETheme(darkTheme = true) { Surface { HomeContent(HomeUiState(shelves = shelves, isLoading = false), "Alexa · Online") } } }
        compose.onNodeWithText("Music Box").assertIsDisplayed()
        screenshot("home")
        compose.onNodeWithTag("home-artwork-p0", useUnmergedTree = true)
            .assertWidthIsEqualTo(160.dp).assertHeightIsEqualTo(160.dp)
        compose.onNodeWithTag("Home feed").performScrollToNode(hasText("Artist card"))
        compose.onNodeWithTag("home-artwork-UCfixture", useUnmergedTree = true)
            .assertWidthIsEqualTo(160.dp).assertHeightIsEqualTo(160.dp)
        screenshot("home-artists")
        compose.onNodeWithText("Music Box").assertDoesNotExist()
    }

    @Test fun sliderThickensWhileDraggingAndCommitsOnlyOnRelease() {
        var value by mutableFloatStateOf(0.2f)
        var commits = 0
        compose.setContent { JUKETheme { ExpandableTrackSlider(value, "Seek test", { value = it; commits++ }) } }
        val thin = compose.onNodeWithTag("Seek test track", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithContentDescription("Seek test").performTouchInput {
            down(Offset(width * 0.2f, centerY)); moveTo(Offset(width * 0.8f, centerY))
        }
        compose.waitForIdle()
        val thick = compose.onNodeWithTag("Seek test track", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.height
        assertTrue(thick > thin)
        assertEquals(0, commits)
        compose.onNodeWithContentDescription("Seek test").performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(1, commits)
        assertTrue(value > 0.7f)
        assertEquals(thin, compose.onNodeWithTag("Seek test track", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.height, 1f)
    }

    @Test fun queueOptionsMoveSongsUsingTheirQueueIndices() {
        var moved: Pair<Int, Int>? = null
        compose.setContent { JUKETheme(darkTheme = true) { Surface(Modifier.fillMaxSize()) {
            QueueBottomSheetContent(current, listOf(current, first, second), 0, MusicUiState(), {},
                { from, to -> moved = from to to }, {}, {})
        } } }
        compose.onNodeWithText("Queue").assertIsDisplayed()
        compose.onNodeWithText("3 songs").assertIsDisplayed()
        screenshot("queue")
        compose.onNodeWithContentDescription("Options for Next song").performClick()
        compose.onNodeWithText("Move up").assertIsEnabled()
        compose.onNodeWithText("Move down").performClick()
        assertEquals(1 to 2, moved)
    }

    @Test fun queueHasClearEmptyStateAndSaveAction() {
        var saved = false
        compose.setContent { JUKETheme { Surface(Modifier.fillMaxSize()) {
            QueueBottomSheetContent(current, listOf(current), 0, MusicUiState(), {}, { _, _ -> }, {}, {},
                onSaveAsPlaylist = { saved = true })
        } } }
        compose.onNodeWithText("No upcoming songs").assertIsDisplayed()
        compose.onNodeWithContentDescription("Queue options").performClick()
        compose.onNodeWithText("Shuffle upcoming").assertIsNotEnabled()
        compose.onNodeWithText("Save queue to playlist").performClick()
        assertTrue(saved)
    }
}
