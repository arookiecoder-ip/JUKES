package com.example.juke

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.test.platform.app.InstrumentationRegistry
import com.example.juke.models.Track
import com.example.juke.network.deviceAudioUrl
import com.example.juke.services.*
import com.example.juke.ui.components.player.QueueBottomSheetContent
import com.example.juke.ui.screens.HapticSettingsCard
import com.example.juke.ui.theme.JUKETheme
import com.example.juke.viewmodels.MusicUiState
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

@UnstableApi
class QueueRecoveryRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun hapticSettingIsVisibleAndPersistsWholeRowToggle() {
        val prefs = context.getSharedPreferences("music_settings_prefs", Context.MODE_PRIVATE)
        val old = prefs.getBoolean("haptics_enabled", true)
        prefs.edit().putBoolean("haptics_enabled", true).commit()
        try {
            compose.setContent { JUKETheme { Surface { HapticSettingsCard() } } }
            compose.onNodeWithText("Haptic feedback").assertIsDisplayed()
            compose.onNodeWithTag("Haptic settings").assertIsOn().performClick().assertIsOff()
            assertFalse(prefs.getBoolean("haptics_enabled", true))
            compose.onNodeWithTag("Haptic settings").performClick().assertIsOn()
            assertTrue(prefs.getBoolean("haptics_enabled", false))
        } finally { prefs.edit().putBoolean("haptics_enabled", old).commit() }
    }
    @Test fun queueDragMovesRowsAndKeepsPlayingSongWithRetryVisible() {
        val songs = (0..5).map { Track("q$it", "Queue song $it", "Artist", durationSec = 180, ytVideoId = "fixture000$it") }
        var queue by mutableStateOf(songs)
        var move: Pair<Int,Int>? = null
        var retried = false
        compose.setContent { JUKETheme { Surface(Modifier.fillMaxWidth().height(560.dp)) {
            QueueBottomSheetContent(songs[0], queue, queue.indexOf(songs[0]), MusicUiState(currentTrack = songs[0], queue = queue),
                {}, { from, to -> move = from to to; queue = queue.toMutableList().apply { add(to, removeAt(from)) } }, {}, {},
                error = "Queue connection interrupted", onRetry = { retried = true })
        } } }
        compose.onNodeWithText("Retry").performClick(); assertTrue(retried)
        compose.onNodeWithContentDescription("Drag to reorder Queue song 1", useUnmergedTree = true).performTouchInput {
            down(center)
            val distance = 140 * context.resources.displayMetrics.density
            moveBy(androidx.compose.ui.geometry.Offset(0f, distance / 2)); advanceEventTime(32)
            moveBy(androidx.compose.ui.geometry.Offset(0f, distance / 2)); advanceEventTime(100); up()
        }
        compose.waitForIdle()
        assertNotNull("Drag did not reorder", move)
        assertEquals(1, move!!.first)
        assertTrue(move!!.second > 1)
        assertEquals(songs[0], queue[0])
    }
    @Test fun nextFivePreloadUsesAuthenticatedCacheAndFollowsQueueMutations() {
        PlaybackStreamRegressionTest.AudioServer().use { server ->
            val folder = File(context.cacheDir, "preload-test-${UUID.randomUUID()}")
            val cache = SimpleCache(folder, LeastRecentlyUsedCacheEvictor(8L * 1024 * 1024), StandaloneDatabaseProvider(context))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val warmer = UpcomingAudioPreloader(playbackDataSources(context, cache, server.base, "fixture-key"), scope)
            lateinit var player: ExoPlayer
            val urls = (0..8).map { deviceAudioUrl(server.base, "video00000$it") }
            try {
                instrumentation.runOnMainSync {
                    player = ExoPlayer.Builder(context).build()
                    player.setMediaItems(urls.map { MediaItem.fromUri(it) }, 0, 0)
                    assertEquals(urls.subList(1, 6), upcomingAudioUrls(player))
                    warmer.update(upcomingAudioUrls(player))
                }
                compose.waitUntil(15000) { urls.subList(1, 6).all { cache.isCached(it, 0, 512) } }
                assertFalse(cache.isCached(urls[6], 0, 512))
                assertTrue(server.requests.all { it["x-api-key"] == "fixture-key" })
                instrumentation.runOnMainSync {
                    player.setShuffleOrder(DefaultShuffleOrder(intArrayOf(0, 8, 7, 6, 5, 4, 3, 2, 1), 1L))
                    player.shuffleModeEnabled = true
                    assertEquals(listOf(8, 7, 6, 5, 4).map { urls[it] }, upcomingAudioUrls(player))
                    warmer.update(upcomingAudioUrls(player))
                    player.shuffleModeEnabled = false
                    player.moveMediaItem(8, 1)
                    assertEquals(listOf(8, 1, 2, 3, 4).map { urls[it] }, upcomingAudioUrls(player))
                    player.addMediaItem(1, MediaItem.fromUri(deviceAudioUrl(server.base, "new00000000")))
                    assertTrue(upcomingAudioUrls(player).first().contains("new00000000"))
                    warmer.update(upcomingAudioUrls(player))
                    player.clearMediaItems(); warmer.update(upcomingAudioUrls(player))
                }
            } finally {
                instrumentation.runOnMainSync { warmer.clear(); player.release(); scope.cancel() }
                cache.release(); folder.deleteRecursively()
            }
        }
    }
}
