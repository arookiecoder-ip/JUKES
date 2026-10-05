package com.example.juke.ui.components.player

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.example.juke.models.Track
import com.example.juke.ui.components.LocalMediaMenu
import com.example.juke.viewmodels.MusicUiState
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueBottomSheetContent(
    currentTrack: Track, queue: List<Track>, queueIndex: Int, uiState: MusicUiState,
    onClose: () -> Unit, onMoveTrack: (Int, Int) -> Unit, onRemoveTrack: (String) -> Unit,
    onPlayTrack: (Track) -> Unit, statusText: String? = null,
    onShuffleUpcoming: () -> Unit = {}, onSortUpcoming: () -> Unit = {},
    onClearPlayed: () -> Unit = {}, onSaveAsPlaylist: () -> Unit = {}
) {
    val menu = LocalMediaMenu.current
    val rowHeight = with(LocalDensity.current) { 72.dp.toPx() }
    val upcomingStart = (queueIndex + 1).coerceIn(0, queue.size)
    val upcoming = queue.drop(upcomingStart)
    var toolsOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Current queue", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("${upcoming.size} upcoming songs", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { toolsOpen = true }) { Icon(Icons.Default.MoreVert, "Queue options") }
                DropdownMenu(expanded = toolsOpen, onDismissRequest = { toolsOpen = false }) {
                    DropdownMenuItem(text = { Text("Shuffle upcoming") }, enabled = upcoming.size > 1,
                        onClick = { toolsOpen = false; onShuffleUpcoming() })
                    DropdownMenuItem(text = { Text("Sort upcoming") }, enabled = upcoming.size > 1,
                        onClick = { toolsOpen = false; onSortUpcoming() })
                    DropdownMenuItem(text = { Text("Clear played songs") }, enabled = queueIndex > 0,
                        onClick = { toolsOpen = false; onClearPlayed() })
                    DropdownMenuItem(text = { Text("Save queue to playlist") },
                        onClick = { toolsOpen = false; onSaveAsPlaylist() })
                }
            }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close queue") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (!statusText.isNullOrBlank()) Text(statusText, Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            item(key = "playing") {
                QueueSectionLabel("Now playing")
                QueueSongRow(currentTrack, active = true, onClick = { onPlayTrack(currentTrack) },
                    onOptions = { menu?.show(currentTrack) })
            }
            item(key = "upcoming") { QueueSectionLabel("Up next") }
            if (upcoming.isEmpty()) item(key = "empty") {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("No upcoming songs", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
                    Text("Add songs from Home, Search or Library.", Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            itemsIndexed(upcoming, key = { index, track -> "$index-${track.uuid}" }) { index, track ->
                val actualIndex = upcomingStart + index
                var dragOffset by remember { mutableFloatStateOf(0f) }
                var dragging by remember { mutableStateOf(false) }
                var options by remember { mutableStateOf(false) }
                val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
                    if (!dragging && !uiState.isQueueOperationInProgress && value == SwipeToDismissBoxValue.EndToStart) {
                        onRemoveTrack(track.uuid); true
                    } else false
                })
                SwipeToDismissBox(state = dismiss, enableDismissFromStartToEnd = false,
                    enableDismissFromEndToStart = !dragging && !uiState.isQueueOperationInProgress,
                    modifier = Modifier.zIndex(if (dragging) 1f else 0f).graphicsLayer { translationY = dragOffset },
                    backgroundContent = {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(end = 24.dp),
                            contentAlignment = Alignment.CenterEnd) {
                            Icon(Icons.Default.Delete, "Remove from queue", tint = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }) {
                    QueueSongRow(track, onClick = { onPlayTrack(track) }, onOptions = { options = true }, lifted = dragging,
                        trailing = {
                            Icon(Icons.Default.DragHandle, "Drag to reorder ${track.title}", tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(40.dp).padding(8.dp).pointerInput(actualIndex, queue.size, uiState.isQueueOperationInProgress) {
                                    if (!uiState.isQueueOperationInProgress) detectDragGestures(
                                        onDragStart = { dragging = true; dragOffset = 0f },
                                        onDragCancel = { dragging = false; dragOffset = 0f },
                                        onDragEnd = {
                                            val target = (actualIndex + (dragOffset / rowHeight).roundToInt()).coerceIn(upcomingStart, queue.lastIndex)
                                            dragging = false; dragOffset = 0f
                                            if (target != actualIndex) onMoveTrack(actualIndex, target)
                                        },
                                        onDrag = { change, delta -> change.consume()
                                            dragOffset = (dragOffset + delta.y).coerceIn(
                                                (upcomingStart - actualIndex) * rowHeight, (queue.lastIndex - actualIndex) * rowHeight)
                                        }
                                    )
                                })
                            DropdownMenu(expanded = options, onDismissRequest = { options = false }) {
                                DropdownMenuItem(text = { Text("Play now") }, onClick = { options = false; onPlayTrack(track) })
                                DropdownMenuItem(text = { Text("Move up") }, enabled = index > 0 && !uiState.isQueueOperationInProgress,
                                    onClick = { options = false; onMoveTrack(actualIndex, actualIndex - 1) })
                                DropdownMenuItem(text = { Text("Move down") }, enabled = index < upcoming.lastIndex && !uiState.isQueueOperationInProgress,
                                    onClick = { options = false; onMoveTrack(actualIndex, actualIndex + 1) })
                                DropdownMenuItem(text = { Text("Remove from queue") }, enabled = !uiState.isQueueOperationInProgress,
                                    onClick = { options = false; onRemoveTrack(track.uuid) })
                                DropdownMenuItem(text = { Text("Song options") }, onClick = { options = false; menu?.show(track) })
                            }
                        })
                }
            }
        }
    }
}

@Composable
private fun QueueSectionLabel(title: String) {
    Text(title, Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun QueueSongRow(track: Track, active: Boolean = false, lifted: Boolean = false,
    onClick: () -> Unit, onOptions: () -> Unit, trailing: @Composable BoxScope.() -> Unit = {}) {
    val color = when { active -> MaterialTheme.colorScheme.surfaceContainerHigh
        lifted -> MaterialTheme.colorScheme.surfaceContainerHighest
        else -> MaterialTheme.colorScheme.surface }
    Row(Modifier.fillMaxWidth().height(72.dp).background(color).padding(horizontal = 12.dp)
        .combinedClickable(onClick = onClick, onLongClick = onOptions, onLongClickLabel = "Song options"),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
            AsyncImage(track.thumbnailUri, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (active) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Equalizer, "Playing", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onOptions, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.MoreVert, "Options for ${track.title}") }
        Box { trailing() }
    }
}
