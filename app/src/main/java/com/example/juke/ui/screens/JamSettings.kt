package com.example.juke.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.juke.ui.components.GlassAlertDialog
import com.example.juke.ui.components.rememberShareAction
import com.example.juke.viewmodels.JamViewModel

@Composable
fun JamSettings(jam: JamViewModel = viewModel()) {
    val state by jam.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val share = rememberShareAction()
    var confirmEnd by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { jam.refresh() }
    Column(Modifier.padding(16.dp)) {
        Text("Jam", style = MaterialTheme.typography.titleMedium)
        Text("Invite friends to the shared Alexa queue with a web app link.", style = MaterialTheme.typography.bodyMedium)
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = jam::refresh) { Text("Retry") } }
        if (state.active) {
            Text(state.url, Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !state.busy && state.url.isNotBlank(), onClick = { share("Join my Music Box Jam", state.url) }) { Text("Share link") }
                TextButton(enabled = state.url.isNotBlank(), onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Music Box Jam", state.url))
                    com.example.juke.network.NetworkFeedback.notify("Jam link copied")
                }) { Text("Copy") }
                TextButton(enabled = !state.busy, onClick = { confirmEnd = true }) { Text("End Jam") }
            }
        } else Button(enabled = !state.busy, onClick = jam::start) { Text("Start Jam") }
    }
    if (confirmEnd) GlassAlertDialog(onDismissRequest = { confirmEnd = false }, title = { Text("End this Jam?") },
        text = { Text("The shared link will stop working for everyone in this Jam.") },
        confirmButton = { TextButton(onClick = { confirmEnd = false; jam.stop() }) { Text("End Jam") } },
        dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Cancel") } })
}
