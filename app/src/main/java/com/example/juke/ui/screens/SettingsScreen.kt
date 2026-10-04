package com.example.juke.ui.screens

import android.content.Context
import android.content.Intent
import android.widget.Toast
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
    bottomPadding: androidx.compose.ui.unit.Dp = 0.dp
) {
    val context = LocalContext.current
    val haptic = rememberJukeHaptics()
    val state by account.state.collectAsStateWithLifecycle()
    val boostEnabled by music.isBoosterEnabled.collectAsStateWithLifecycle()
    val volume by music.boosterLevel.collectAsStateWithLifecycle()
    val bass by music.bassLevel.collectAsStateWithLifecycle()
    val miniLyrics by music.isMiniPlayerLyricsEnabled.collectAsStateWithLifecycle()
    val powerPrefs = remember { context.getSharedPreferences("power_prefs", Context.MODE_PRIVATE) }
    var powerUnlocked by remember { mutableStateOf(powerPrefs.getBoolean("power_tools_unlocked", false)) }
    var versionTaps by remember { mutableIntStateOf(0) }
    var lastVersionTapAt by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) { account.refreshStatus() }

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
            item { AccountStatusCard(state, account) }
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Volume and bass boost", fontWeight = FontWeight.SemiBold)
                                Text("For playback on this phone", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = boostEnabled, onCheckedChange = music::toggleVolumeBooster)
                        }
                        Text("Volume boost · $volume%")
                        androidx.compose.material3.Slider(value = volume.toFloat(), onValueChange = { music.setVolumeBoosterLevel(it.toInt()) }, valueRange = 0f..100f, enabled = boostEnabled)
                        Text("Bass boost · $bass%")
                        androidx.compose.material3.Slider(value = bass.toFloat(), onValueChange = { music.setBassLevel(it.toInt()) }, valueRange = 0f..100f, enabled = boostEnabled)
                    }
                }
            }
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Mini-player lyrics", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Switch(checked = miniLyrics, onCheckedChange = music::toggleMiniPlayerLyrics)
                    }
                }
            }

            item {
                GlassButton(
                    onClick = {
                        haptic.confirm()
                        account.signOut()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Sign out") }
            }

            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Solid surfaces", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Turn off blur and transparency",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = GlassPrefs.solid,
                            onCheckedChange = {
                                haptic.toggle()
                                GlassPrefs.solid = it
                                context.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)
                                    .edit().putBoolean("solid_surfaces", it).apply()
                            }
                        )
                    }
                }
            }

            if (powerUnlocked) {
                item {
                    GlassCard(Modifier.fillMaxWidth().clickable { haptic.click(); onNavigateToPowerTools() }) {
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
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "JUKE v${com.example.juke.BuildConfig.VERSION_NAME}",
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
                    GlassButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/rajeet-04/JUKES".toUri()))
                    }) { Text("⭐ Star on GitHub") }
                }
            }
        }
    }
}
