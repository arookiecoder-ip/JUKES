package com.example.juke

import android.content.Context
import android.app.Notification
import android.app.NotificationManager
import android.app.UiAutomation
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.example.juke.models.Track
import com.example.juke.services.EchoState
import com.example.juke.services.RemotePlaybackService
import com.example.juke.ui.components.player.ResponsivePlayerLayout
import com.example.juke.ui.components.player.SyncedLyricsLines
import com.example.juke.ui.screens.LyricLine
import com.example.juke.ui.theme.JUKETheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TabletPlaybackRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun portraitAndLandscapePlayerKeepControlsBesideOrBelowArtwork() {
        var landscape by mutableStateOf(false)
        compose.setContent { JUKETheme { Surface(Modifier.requiredSize(if (landscape) 1000.dp else 700.dp, if (landscape) 700.dp else 1000.dp)) {
            ResponsivePlayerLayout(artwork = { Text("Artwork") }, controls = { Text("Controls") })
        } } }
        var artwork = compose.onNodeWithTag("Player artwork pane").fetchSemanticsNode().boundsInRoot
        var controls = compose.onNodeWithTag("Player controls pane").fetchSemanticsNode().boundsInRoot
        assertTrue(controls.top >= artwork.bottom)
        assertTrue(controls.width <= 680 * context.resources.displayMetrics.density + 1)
        compose.runOnIdle { landscape = true }
        artwork = compose.onNodeWithTag("Player artwork pane").fetchSemanticsNode().boundsInRoot
        controls = compose.onNodeWithTag("Player controls pane").fetchSemanticsNode().boundsInRoot
        assertTrue(controls.left >= artwork.right)
        assertTrue(controls.height > artwork.height * 0.8f)
    }
    @Test fun playerAdaptsToActualDeviceRotation() {
        compose.setContent { JUKETheme { ResponsivePlayerLayout(artwork = { Text("Artwork") }, controls = { Text("Controls") }) } }
        try {
            for (rotation in listOf(UiAutomation.ROTATION_FREEZE_0, UiAutomation.ROTATION_FREEZE_90)) {
                instrumentation.uiAutomation.setRotation(rotation)
                compose.waitForIdle()
                val a = compose.onNodeWithTag("Player artwork pane").fetchSemanticsNode().boundsInRoot
                val c = compose.onNodeWithTag("Player controls pane").fetchSemanticsNode().boundsInRoot
                assertTrue("Artwork and controls overlap after rotation", c.left >= a.right || c.top >= a.bottom)
            }
        } finally { instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_UNFREEZE) }
    }
    @Test fun lyricsRecentreAfterSeekingAndManualScroll() {
        val lines = (0 until 20).map { LyricLine(it * 1000L, "Line $it") }
        var position by mutableLongStateOf(8000)
        compose.setContent { JUKETheme { Surface(Modifier.size(360.dp, 420.dp)) {
            SyncedLyricsLines(lines, position, { position = it }, Modifier.fillMaxSize())
        } } }
        fun centred(index: Int): Boolean {
            val viewport = compose.onNodeWithTag("Synced lyrics").fetchSemanticsNode().boundsInRoot
            val line = compose.onNodeWithTag("Lyric line $index").fetchSemanticsNode().boundsInRoot
            return kotlin.math.abs(viewport.center.y - line.center.y) < 4 * context.resources.displayMetrics.density
        }
        compose.waitUntil(5_000) { centred(8) }
        compose.onNodeWithTag("Lyric line 9").performClick()
        compose.waitUntil(5_000) { centred(9) }
        compose.onNodeWithTag("Synced lyrics").performTouchInput { swipeUp() }
        compose.waitUntil(5_000) { runCatching { centred(9) }.getOrDefault(false) }
    }
    @Test fun disablingHapticsPreventsEveryFeedbackCall() {
        val prefs = context.getSharedPreferences("music_settings_prefs", Context.MODE_PRIVATE)
        val previous = prefs.getBoolean("haptics_enabled", true)
        var calls = 0
        val view = object : android.view.View(context) {
            override fun performHapticFeedback(feedbackConstant: Int): Boolean { calls++; return true }
        }
        try {
            prefs.edit().putBoolean("haptics_enabled", false).commit()
            val haptics = com.example.juke.utils.JukeHaptics(view, context)
            haptics.click(); haptics.heavyClick(); haptics.tick(); haptics.toggle(); haptics.confirm(); haptics.reject(); haptics.gestureStart(); haptics.gestureEnd()
            assertEquals(0, calls)
            prefs.edit().putBoolean("haptics_enabled", true).commit()
            haptics.gestureStart()
            assertEquals(1, calls)
        } finally { prefs.edit().putBoolean("haptics_enabled", previous).commit() }
    }
    @Test fun alexaPlaybackPostsActualMediaNotificationWithoutAnExternalController() {
        val prefs = context.getSharedPreferences("music_settings_prefs", Context.MODE_PRIVATE)
        val oldOutput = prefs.getString("playback_output", null)
        val oldSerial = prefs.getString("echo_serial", null)
        prefs.edit().putString("playback_output", "ALEXA").putString("echo_serial", "").commit()
        val track = Track("notification-fixture", "Alexa notification fixture", "Artist", durationSec = 180, ytVideoId = "abcdefghijk")
        try {
            compose.setContent { Text("Notification test") }
            instrumentation.runOnMainSync { RemotePlaybackService.start(context,
                EchoState(track = track, queue = listOf(track), index = 0, playing = true, confirmed = true, durationMs = 180000)) }
            val manager = context.getSystemService(NotificationManager::class.java)
            compose.waitUntil(15_000) { manager.activeNotifications.any { it.id == 1002 } }
            val notification = manager.activeNotifications.first { it.id == 1002 }.notification
            assertEquals(track.title, notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertNotNull(notification.extras.getParcelable<android.os.Parcelable>(Notification.EXTRA_MEDIA_SESSION))
            assertTrue("Alexa notification has no transport controls", notification.actions.isNotEmpty())
        } finally {
            instrumentation.runOnMainSync { RemotePlaybackService.stop(context); RemotePlaybackService.initialSnapshot = null }
            prefs.edit().apply { if (oldOutput == null) remove("playback_output") else putString("playback_output", oldOutput)
                if (oldSerial == null) remove("echo_serial") else putString("echo_serial", oldSerial) }.commit()
        }
    }
}
