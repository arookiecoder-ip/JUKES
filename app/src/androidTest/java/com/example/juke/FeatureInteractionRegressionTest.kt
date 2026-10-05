package com.example.juke

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.example.juke.models.Track
import com.example.juke.network.BrowseParser
import com.example.juke.ui.components.DetailHero
import com.example.juke.ui.screens.*
import com.example.juke.ui.theme.JUKETheme
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FeatureInteractionRegressionTest {
    @get:Rule val compose = createComposeRule()
    @Test fun librarySwipesInBothDirectionsThroughAllFilters() {
        val names = listOf("All", "Playlists", "Albums", "Artists", "Downloads")
        compose.setContent { JUKETheme { Surface(Modifier.fillMaxSize()) {
            val pager = rememberPagerState { names.size }
            LibraryPages(pager) { page -> Box(Modifier.fillMaxSize()) { Text(names[page]) } }
        } } }
        for (name in names.drop(1)) {
            compose.onNodeWithTag("Library pages").performTouchInput { swipeRight() }
            compose.waitForIdle()
            compose.onNodeWithText(name).assertIsDisplayed()
        }
        for (name in names.dropLast(1).reversed()) {
            compose.onNodeWithTag("Library pages").performTouchInput { swipeLeft() }
            compose.waitForIdle()
            compose.onNodeWithText(name).assertIsDisplayed()
        }
    }
    @Test fun artistBannerDoesNotStretchWhenDescriptionExpands() {
        var expanded by mutableStateOf(false)
        compose.setContent { JUKETheme { DetailHero(null, artist = true) {
            Spacer(Modifier.height(170.dp))
            Button(onClick = { expanded = !expanded }) { Text("More") }
            if (expanded) Spacer(Modifier.height(300.dp))
        } } }
        compose.onNodeWithTag("Artist banner", useUnmergedTree = true).assertHeightIsEqualTo(240.dp)
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithTag("Artist banner", useUnmergedTree = true).assertHeightIsEqualTo(240.dp)
    }
    @Test fun searchArtworkSizeIsConsistentAndArtistShowsThreeSongs() {
        var kind by mutableStateOf("song")
        val songs = (1..3).map { Track("song$it", "Top song $it", "Artist", durationSec = 180, ytVideoId = "aaaaaaaaaaa") }
        compose.setContent { JUKETheme { Surface {
            val item = BrowseParser.item(buildJsonObject {
                put("kind", kind); put("title", "Top result");
                if (kind == "song") put("videoId", "aaaaaaaaaaa") else put("browseId", when(kind) { "artist" -> "UCfixture"; "album" -> "MPREfixture"; else -> "PLfixture" })
            })
            SearchHeroCard(item, songs, {}, {}, {}, {})
        } } }
        for (type in listOf("song", "album", "playlist", "artist")) {
            compose.runOnIdle { kind = type }
            compose.onNodeWithTag("Search result artwork", useUnmergedTree = true).assertWidthIsEqualTo(84.dp).assertHeightIsEqualTo(84.dp)
            if (type != "artist") compose.onNodeWithTag("Search top result").assertHeightIsEqualTo(120.dp)
        }
        compose.onAllNodesWithText("Artist", substring = false).assertCountEquals(4)
        for (song in songs) compose.onNodeWithText(song.title).assertIsDisplayed()
        compose.onNodeWithText("Open Artist").assertDoesNotExist()
    }
    @Test fun selectedDownloadsDeleteOnlyAfterConfirmation() {
        val tracks = (1..3).map { Track("s$it", "Song $it", "Artist", durationSec = 180, ytVideoId = "video00000$it") }
        val selection = DownloadSelection().apply { select(tracks[0]); toggle(tracks[2]) }
        var removed = emptyList<Track>()
        compose.setContent { JUKETheme { Surface { DownloadSelectionBar(selection, tracks) { removed = it } } } }
        compose.onNodeWithText("2 selected").assertExists()
        compose.onNodeWithContentDescription("Delete selected downloads").performClick()
        assertTrue(removed.isEmpty())
        compose.onNodeWithText("Cancel", substring = false).performClick()
        assertTrue(removed.isEmpty())
        compose.onNodeWithContentDescription("Delete selected downloads").performClick()
        compose.onNodeWithText("Delete", substring = false).performClick()
        assertEquals(listOf(tracks[0], tracks[2]), removed)
        assertFalse(selection.active)
    }
    @Test fun downloadNotificationsAreProgressNotificationsThatOpenDownloads() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val destination = com.example.juke.services.downloadDestinationIntent(context)
        assertEquals(MainActivity::class.java.name, destination.component?.className)
        assertTrue(destination.getBooleanExtra(com.example.juke.services.DownloadService.OPEN_DOWNLOADS, false))
        assertNull(destination.data)
        val state = com.example.juke.services.DownloadStatus(active = 3, total = 10, completed = 2, title = "Song", percent = 25)
        val active = com.example.juke.services.downloadNotification(context, state, true)
        assertEquals(android.app.Notification.CATEGORY_PROGRESS, active.category)
        assertTrue(active.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(25, active.extras.getInt(android.app.Notification.EXTRA_PROGRESS))
        assertNotNull(active.contentIntent)
        assertEquals("Cancel", active.actions.single().title.toString())
        assertFalse(active.extras.getString(android.app.Notification.EXTRA_TEMPLATE).orEmpty().contains("MediaStyle"))
        val finished = com.example.juke.services.downloadNotification(context, state.copy(active = 0, completed = 10), false)
        assertEquals("Downloads complete", finished.extras.getString(android.app.Notification.EXTRA_TITLE))
        assertTrue(finished.flags and android.app.Notification.FLAG_AUTO_CANCEL != 0)
    }

}
