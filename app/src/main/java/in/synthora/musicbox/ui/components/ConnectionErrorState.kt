package `in`.synthora.musicbox.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun ConnectionErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier,
    offline: Boolean = false) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 340.dp).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(if (offline) Icons.Outlined.WifiOff else Icons.Outlined.CloudOff, null,
                Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (offline) "You're offline" else "Couldn't load this section",
                style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(if (offline) "Connect to Wi-Fi or mobile data, then try again." else message,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            OutlinedButton(onClick = onRetry) { Text("Try again") }
        }
    }
}
