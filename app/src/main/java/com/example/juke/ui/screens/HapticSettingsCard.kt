package com.example.juke.ui.screens

import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.juke.ui.theme.GlassCard

@Composable
fun HapticSettingsCard() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("music_settings_prefs", Context.MODE_PRIVATE) }
    var enabled by remember { mutableStateOf(prefs.getBoolean("haptics_enabled", true)) }
    GlassCard(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RectangleShape)) {
        Row(Modifier.fillMaxWidth().testTag("Haptic settings").toggleable(enabled, role = Role.Switch, onValueChange = {
            enabled = it; prefs.edit().putBoolean("haptics_enabled", it).apply()
        }).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Haptic feedback", fontWeight = FontWeight.SemiBold)
                Text(if (enabled) "Vibration on taps and gestures" else "Vibration disabled", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = enabled, onCheckedChange = null)
        }
    }
}
