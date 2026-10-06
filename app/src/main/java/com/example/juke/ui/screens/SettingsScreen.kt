package com.example.juke.ui.screens

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.juke.ui.components.GlassButton
import com.example.juke.ui.components.GlassTopAppBar
import com.example.juke.ui.theme.GlassCard
import com.example.juke.ui.theme.GlassPrefs
import com.example.juke.utils.rememberJukeHaptics
import com.example.juke.viewmodels.AccountViewModel

/** Account status, sign-out and appearance. Power Tools unlock by tapping the version 7 times. */
@Composable
fun SettingsScreen(
    account: AccountViewModel,
    music: com.example.juke.viewmodels.MusicViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToPowerTools: () -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
    jam: com.example.juke.viewmodels.JamViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val context = LocalContext.current
    val haptic = rememberJukeHaptics()
    val state by account.state.collectAsStateWithLifecycle()
    val boostEnabled by music.isBoosterEnabled.collectAsStateWithLifecycle()
    val volume by music.boosterLevel.collectAsStateWithLifecycle()
    val bass by music.bassLevel.collectAsStateWithLifecycle()
    val miniLyrics by music.isMiniPlayerLyricsEnabled.collectAsStateWithLifecycle()
    val settingsPrefs = remember { context.getSharedPreferences("music_settings_prefs", Context.MODE_PRIVATE) }
    var mobilePrefetch by remember { mutableStateOf(settingsPrefs.getBoolean("prefetch_mobile_data", false)) }
    var cacheMb by remember { mutableIntStateOf(settingsPrefs.getInt("stream_cache_mb", 256)) }
    var hapticsEnabled by remember { mutableStateOf(settingsPrefs.getBoolean("haptics_enabled", true)) }
    val echoDevices by music.echo.devices.collectAsStateWithLifecycle()
    val amazonConnected by music.echo.amazonConnected.collectAsStateWithLifecycle()
    val powerPrefs = remember { context.getSharedPreferences("power_prefs", Context.MODE_PRIVATE) }
    var powerUnlocked by remember { mutableStateOf(powerPrefs.getBoolean("power_tools_unlocked", false)) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var versionTaps by remember { mutableIntStateOf(0) }
    var lastVersionTapAt by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) { account.refreshStatus(); music.refreshDevices() }

    if (confirmSignOut) com.example.juke.ui.components.GlassAlertDialog(
        onDismissRequest = { confirmSignOut = false },
        title = { Text("Sign out?") }, text = { Text("You’ll need to sign in again to access your account.") },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { confirmSignOut = false; account.signOut() }) { Text("Sign out") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } })

    Column(Modifier.fillMaxSize()) {
        GlassTopAppBar(
            title = { Text("Settings", fontWeight = FontWeight.Bold) },
            navigationIcon = {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onSurface)
                }
            }
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp + bottomPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { GlassCard(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RectangleShape)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Haptic feedback", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Switch(hapticsEnabled, { hapticsEnabled = it; settingsPrefs.edit().putBoolean("haptics_enabled", it).apply() })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Mini-player lyrics", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Switch(miniLyrics, music::toggleMiniPlayerLyrics)
                    }
                }
            } }
            item { GlassCard(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RectangleShape)) { JamSettings(jam) } }
            item { GlassCard(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RectangleShape)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Preload on mobile data", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Switch(mobilePrefetch, { mobilePrefetch = it; settingsPrefs.edit().putBoolean("prefetch_mobile_data", it).apply() })
                    }
                    Text("Stream cache", fontWeight = FontWeight.SemiBold)
                    Row {
                        listOf(128, 256, 512, 1024).forEach { size ->
                            androidx.compose.material3.TextButton(onClick = { cacheMb = size; settingsPrefs.edit().putInt("stream_cache_mb", size).apply() }) {
                                Text("${size} MB", color = if (cacheMb == size) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Text("Cache size applies after restarting the app.", style = MaterialTheme.typography.bodySmall)
                }
            } }
            item { GlassCard(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RectangleShape)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Alexa", fontWeight = FontWeight.SemiBold)
                    if (amazonConnected == false) Text("Connect your Amazon account")
                    else if (echoDevices.isEmpty()) Text("No Echo devices found")
                    echoDevices.forEach { device ->
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            Text(device.name, Modifier.weight(1f))
                            Text(if (device.online) "Online" else "Offline", color = if (device.online) androidx.compose.ui.graphics.Color(0xFF66BB6A) else MaterialTheme.colorScheme.error)
                        }
                    }
                }
            } }
            item { GlassCard(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RectangleShape)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Volume and bass boost", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Switch(boostEnabled, music::toggleVolumeBooster)
                    }
                    Text("For playback on this phone", style = MaterialTheme.typography.bodySmall)
                    Text("Volume boost · $volume%")
                    com.example.juke.ui.components.player.ExpandableTrackSlider(volume / 100f, "Volume boost", { music.setVolumeBoosterLevel((it * 100).toInt()) }, enabled = boostEnabled, onValueChange = { music.setVolumeBoosterLevel((it * 100).toInt()) })
                    Text("Bass boost · $bass%")
                    com.example.juke.ui.components.player.ExpandableTrackSlider(bass / 100f, "Bass boost", { music.setBassLevel((it * 100).toInt()) }, enabled = boostEnabled, onValueChange = { music.setBassLevel((it * 100).toInt()) })
                }
            } }
            item { Box(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RectangleShape)) { AccountStatusCard(state, account) } }

            if (powerUnlocked) {
                item {
                    GlassCard(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RectangleShape).clickable { haptic.click(); onNavigateToPowerTools() }) {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Power Tools", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Gestures, diagnostics and export",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            item {
                Column(
                    Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RectangleShape).padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Music Box v${com.example.juke.BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            val now = System.currentTimeMillis()
                            if (now - lastVersionTapAt > 2000L) versionTaps = 0
                            lastVersionTapAt = now
                            if (powerUnlocked) {
                                Toast.makeText(context, "Power Tools are already unlocked", Toast.LENGTH_SHORT).show()
                            } else {
                                versionTaps++
                                haptic.tick()
                                val left = 7 - versionTaps
                                if (left <= 0) {
                                    powerUnlocked = true
                                    powerPrefs.edit().putBoolean("power_tools_unlocked", true).apply()
                                    haptic.confirm()
                                    Toast.makeText(context, "Power Tools unlocked", Toast.LENGTH_SHORT).show()
                                } else if (versionTaps >= 3) {
                                    Toast.makeText(
                                        context, "$left more tap${if (left == 1) "" else "s"} to unlock Power Tools",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    )

                }
            }
            item { androidx.compose.material3.Button(
                onClick = { haptic.confirm(); confirmSignOut = true },
                modifier = Modifier.fillMaxWidth(), shape = RectangleShape,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.error)
            ) { Text("Sign out") } }

        }
    }
}
