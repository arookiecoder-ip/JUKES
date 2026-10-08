package `in`.synthora.musicbox.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.synthora.musicbox.services.RemoteVolumeControl
import kotlinx.coroutines.delay

/** Feedback follows the key press, independently of the target's network reply. */
@Composable
fun RemoteVolumeFeedback() {
    val feedback by RemoteVolumeControl.feedback.collectAsStateWithLifecycle()
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(feedback?.sequence) {
        if (feedback == null) { visible = false; return@LaunchedEffect }
        visible = true
        delay(1_200)
        visible = false
    }
    val shown = feedback ?: return
    Popup(alignment = Alignment.TopCenter) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            Surface(Modifier.statusBarsPadding().padding(12.dp).widthIn(max = 360.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 4.dp) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.VolumeUp, null)
                        Spacer(Modifier.width(8.dp))
                        Text(shown.target, Modifier.weight(1f), maxLines = 1)
                        Text("${shown.value}%")
                    }
                    LinearProgressIndicator(progress = { shown.value / 100f }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
