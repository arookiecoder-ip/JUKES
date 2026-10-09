package `in`.synthora.musicbox.ui.screens

import android.content.Context
import android.content.Intent
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import `in`.synthora.musicbox.ui.components.GlassButton
import `in`.synthora.musicbox.ui.components.GlassTopAppBar
import `in`.synthora.musicbox.ui.theme.GlassCard
import `in`.synthora.musicbox.utils.Diagnostics
import `in`.synthora.musicbox.utils.rememberJukeHaptics
import kotlinx.coroutines.launch

/** Advanced settings, gesture shortcuts and diagnostics for people who want to tune the app. */
@Composable
fun PowerToolsScreen(onNavigateBack: () -> Unit, bottomPadding: androidx.compose.ui.unit.Dp = 0.dp) {
    val context = LocalContext.current
    val haptic = rememberJukeHaptics()
    val scope = rememberCoroutineScope()
    val power = remember { context.getSharedPreferences("power_prefs", Context.MODE_PRIVATE) }

    var doubleTap by remember { mutableStateOf(power.getBoolean("mini_double_tap_play_pause", false)) }
    var longPress by remember { mutableStateOf(power.getBoolean("mini_long_press_favorite", false)) }

    fun share(subject: String, text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, subject))
    }

    Column(Modifier.fillMaxSize()) {
        GlassTopAppBar(
            title = { Text("Power Tools", fontWeight = FontWeight.Bold) },
            navigationIcon = {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onSurface)
                }
            }
        )
        LazyColumn(
            // Weighted so the list owns the remaining height and scrolls; the bottom inset keeps the last
            // card clear of the floating mini player and tab bar.
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 100.dp + bottomPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Section("Gestures and shortcuts") {
                    SwitchRow("Double-tap mini player", "Play or pause", doubleTap) {
                        haptic.toggle()
                        doubleTap = it
                        power.edit().putBoolean("mini_double_tap_play_pause", it).apply()
                    }
                    SwitchRow("Long-press mini player", "Add or remove the favourite", longPress) {
                        haptic.toggle()
                        longPress = it
                        power.edit().putBoolean("mini_long_press_favorite", it).apply()
                    }
                    Label("Also built in", "Tap Search again to select your query and type. Swipe the mini player to change songs. Queue ⋮ menu: shuffle, sort, clear played, save as playlist.")
                }
            }

            item {
                Section("Lock") {
                    Label("Hide Power Tools", "Locks this page again. Unlock it by tapping the version in Audio Control 7 times.")
                    GlassButton(onClick = {
                        haptic.confirm()
                        power.edit().putBoolean("power_tools_unlocked", false).apply()
                        onNavigateBack()
                    }) { Text("Lock Power Tools") }
                }
            }

            item {
                Section("Diagnostics and export") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassButton(onClick = {
                            haptic.click()
                            scope.launch { share("Music Box diagnostics", Diagnostics.report(context)) }
                        }) { Text("Share diagnostics") }
                        GlassButton(onClick = {
                            haptic.click()
                            scope.launch { share("Music Box backup", Diagnostics.backupJson(context)) }
                        }) { Text("Export backup") }
                    }
                    Label("Backup contents", "App settings as JSON. No credentials. Your library stays in your account. Diagnostics include recent app log lines.")
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun Label(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
