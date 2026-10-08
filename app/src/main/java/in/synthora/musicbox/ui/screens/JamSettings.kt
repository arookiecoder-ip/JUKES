package `in`.synthora.musicbox.ui.screens

import androidx.compose.ui.graphics.asImageBitmap
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
import `in`.synthora.musicbox.ui.components.GlassAlertDialog
import `in`.synthora.musicbox.ui.components.rememberShareAction
import `in`.synthora.musicbox.viewmodels.JamViewModel

@Composable
fun JamSettings(jam: JamViewModel = viewModel()) {
    val state by jam.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val share = rememberShareAction()
    var confirmEnd by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { jam.refresh() }
    Column(Modifier.padding(16.dp)) {
        Text("Jam", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        Text("Invite friends to the shared Alexa queue with a web app link.", style = MaterialTheme.typography.bodyMedium)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = jam::refresh) { Text("Retry") } }
        Box(Modifier.fillMaxWidth().padding(top = 12.dp).height(48.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
        if (state.active) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(modifier = Modifier.weight(1f).height(48.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp), enabled = state.url.isNotBlank(), onClick = { showQr = true }) { Text("Manage") }
                Button(modifier = Modifier.weight(1f).height(48.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.error), enabled = !state.busy, onClick = { confirmEnd = true }) { if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.error) else Text("End Jam") }
            }
        } else Button(enabled = !state.busy, onClick = jam::start, modifier = Modifier.fillMaxWidth().height(48.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)) { if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary) else Text("Start Jam") }
        }
    }
    if (showQr && state.url.isNotBlank()) {
        val bitmap = remember(state.url) {
            val matrix = com.google.zxing.MultiFormatWriter().encode(state.url, com.google.zxing.BarcodeFormat.QR_CODE, 512, 512)
            android.graphics.Bitmap.createBitmap(512, 512, android.graphics.Bitmap.Config.ARGB_8888).apply {
                setPixels(IntArray(512 * 512) { i -> if (matrix[i % 512, i / 512]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }, 0, 512, 0, 0, 512, 512)
            }
        }
        GlassAlertDialog(onDismissRequest = { showQr = false }, title = { Text("Join this Jam") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.foundation.Image(bitmap.asImageBitmap(), "Scan to join this Jam", Modifier.fillMaxWidth().aspectRatio(1f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button( modifier = Modifier.weight(1f), shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp), onClick = { share("Join my Music Box Jam", state.url) }) { Text("Share link") }
                    FilledTonalButton(modifier = Modifier.weight(1f), shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp), onClick = {
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Music Box Jam", state.url))
                        `in`.synthora.musicbox.network.NetworkFeedback.notify("Jam link copied")
                    }) { Text("Copy") }
                }
            } },
            confirmButton = { TextButton(onClick = { showQr = false }) { Text("Done") } })
    }
    if (confirmEnd) GlassAlertDialog(onDismissRequest = { confirmEnd = false }, title = { Text("End this Jam?") },
        text = { Text("The shared link will stop working for everyone in this Jam.") },
        confirmButton = { TextButton(onClick = { confirmEnd = false; jam.stop() }) { Text("End Jam") } },
        dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Cancel") } })
}
