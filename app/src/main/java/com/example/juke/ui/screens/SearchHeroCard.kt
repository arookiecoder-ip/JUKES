package com.example.juke.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.juke.models.Track
import com.example.juke.network.BrowseItem
import com.example.juke.ui.components.FlatTrackRow

/** All mobile top results share the same artwork and main-row bounds. Artist songs share its backdrop. */
@Composable
fun SearchHeroCard(item: BrowseItem, topSongs: List<Track>, onOpen: () -> Unit, onPlay: () -> Unit,
    onSong: (Track) -> Unit, onOptions: () -> Unit, modifier: Modifier = Modifier) {
    val surface = MaterialTheme.colorScheme.surfaceContainer
    Box(modifier.fillMaxWidth().clip(RectangleShape).testTag("Search top result").background(surface)) {
        if (item.kind == "artist") {
            AsyncImage(item.image, null, Modifier.matchParentSize().blur(16.dp).alpha(0.35f), contentScale = ContentScale.Crop)
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(surface.copy(alpha = 0.55f), surface.copy(alpha = 0.9f)))))
        }
        Column {
            Row(Modifier.fillMaxWidth().height(120.dp).combinedClickable(onClick = onOpen, onLongClick = onOptions)
                .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(84.dp).testTag("Search result artwork").clip(if (item.kind == "artist") CircleShape else RectangleShape)) {
                    AsyncImage(item.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(item.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (item.kind == "artist") "Artist" else listOf(if (item.kind == "track") "Song" else item.kind.replaceFirstChar { it.uppercase() }, item.subtitle).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (item.kind != "artist") FilledTonalIconButton(onClick = onPlay) { Icon(Icons.Default.PlayArrow, "Play ${item.title}") }
                IconButton(onClick = onOptions) { Icon(Icons.Default.MoreVert, "Options for ${item.title}") }
            }
            if (item.kind == "artist") topSongs.take(3).forEach { song ->
                FlatTrackRow(song.thumbnailUri, song.title, song.artist,
                    if (song.durationSec > 0) "%d:%02d".format(song.durationSec / 60, song.durationSec % 60) else "",
                    onClick = { onSong(song) }, modifier = Modifier.padding(horizontal = 12.dp), track = song, sharpArtwork = true, showMore = true)
            }
        }
    }
}
