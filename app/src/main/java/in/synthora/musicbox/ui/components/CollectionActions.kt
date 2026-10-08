package `in`.synthora.musicbox.ui.components

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import `in`.synthora.musicbox.network.BrowseItem

@Composable
fun CollectionActions(item: BrowseItem, onPlay: () -> Unit, onShuffle: () -> Unit, onQueue: () -> Unit, options: List<ExtraSongOption> = emptyList()) {
    val menu = LocalMediaMenu.current
    val share = rememberShareAction()
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly) {
        IconButton(onClick = onShuffle) { Icon(Icons.Default.Shuffle, "Shuffle collection", Modifier.size(28.dp)) }
        IconButton(onClick = onQueue) { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, "Add collection to queue", Modifier.size(28.dp)) }
        FilledIconButton(onClick = onPlay, modifier = Modifier.size(72.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color.Black)) {
            Icon(Icons.Default.PlayArrow, "Play collection", Modifier.size(40.dp))
        }
        IconButton(onClick = {
            val url = if (item.kind == "album") "https://music.youtube.com/browse/${item.id}"
                else "https://music.youtube.com/playlist?list=${item.playlistId.ifBlank { item.id }.removePrefix("VL")}"
            share("Share ${item.title}", url)
        }) { Icon(Icons.Default.Share, "Share collection", Modifier.size(28.dp)) }
        IconButton(onClick = { menu?.show(item, extras = options) }) { Icon(Icons.Default.MoreVert, "Collection options", Modifier.size(28.dp)) }
    }
}
