package com.example.juke.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.juke.models.Track

/** The web search song hero: square art beside the title and artist. */
@Composable
fun HeroTrackCard(track: Track, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val menu = LocalMediaMenu.current
    Row(modifier.fillMaxWidth().combinedClickable(onClickLabel = "Play ${track.title}", role = Role.Button,
        onLongClick = { menu?.show(track) }, onLongClickLabel = "Song options", onClick = onClick),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(84.dp).testTag("Search song artwork").background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            AsyncImage(track.thumbnailUri, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Icon(Icons.Filled.PlayArrow, null, Modifier.size(32.dp), tint = Color.White)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(track.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(track.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = { menu?.show(track) }) { Icon(Icons.Default.MoreVert, "Song options") }
    }
}
