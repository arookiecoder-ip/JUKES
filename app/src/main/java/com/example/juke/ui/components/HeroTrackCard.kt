package com.example.juke.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.juke.models.Track
import com.example.juke.utils.rememberJukeHaptics

@Composable
fun HeroTrackCard(track: Track, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptic = rememberJukeHaptics()
    val menu = LocalMediaMenu.current
    Column(modifier.fillMaxWidth().combinedClickable(onClickLabel = "Play ${track.title}", role = Role.Button, onLongClick = { haptic.heavyClick(); menu?.show(track) }, onLongClickLabel = "Song options", onClick = { haptic.click(); onClick() })) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.5f).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            AsyncImage(track.thumbnailUri, null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            FilledIconButton(onClick = { haptic.click(); onClick() }, modifier = Modifier.size(80.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                Icon(Icons.Filled.PlayArrow, "Play ${track.title}", Modifier.size(52.dp), tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(track.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
        Text(track.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
