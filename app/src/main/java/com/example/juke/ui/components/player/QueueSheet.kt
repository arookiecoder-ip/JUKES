package com.example.juke.ui.components.player

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val density = LocalDensity.current
    val rowHeight = with(density) { 72.dp.toPx() }
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Queue", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close queue") }
        }
        HorizontalDivider()
        if (!statusText.isNullOrBlank()) Text(statusText, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.weight(1f)) {
            item(key = "now-playing") {
                Text("Now playing", Modifier.padding(horizontal = 20.dp, vertical = 10.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                QueueSongRow(currentTrack, active = true, onClick = { onPlayTrack(currentTrack) }, onOptions = { menu?.show(currentTrack) })
            }
            item(key = "next-label") {
                Text("Up next · ${(queue.size - queueIndex - 1).coerceAtLeast(0)}", Modifier.padding(horizontal = 20.dp, vertical = 10.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (queueIndex >= queue.lastIndex) item { Text("No upcoming songs", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            itemsIndexed(queue.drop((queueIndex + 1).coerceAtLeast(0)), key = { index, track -> "$index-${track.uuid}" }) { index, track ->
                val actualIndex = queueIndex + 1 + index
                var dragOffset by remember { mutableFloatStateOf(0f) }
                val dragging = dragOffset != 0f
                val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
                    if (!dragging && value == SwipeToDismissBoxValue.EndToStart) { onRemoveTrack(track.uuid); true } else false
                })
                SwipeToDismissBox(state = dismiss, enableDismissFromStartToEnd = false, enableDismissFromEndToStart = !dragging,
                    modifier = Modifier.zIndex(if (dragging) 1f else 0f).graphicsLayer { translationY = dragOffset },
                    backgroundContent = {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(end = 20.dp), contentAlignment = Alignment.CenterEnd) {
                            Icon(Icons.Filled.Delete, "Remove from queue", tint = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }) {
                    QueueSongRow(track, onClick = { onPlayTrack(track) }, onOptions = { menu?.show(track) }, trailing = {
                        Icon(Icons.Filled.DragHandle, "Drag to reorder", modifier = Modifier.size(40.dp).padding(8.dp).pointerInput(actualIndex, queue.size, uiState.isQueueOperationInProgress) {
                            if (!uiState.isQueueOperationInProgress) detectDragGestures(
                                onDragStart = { dragOffset = 0f },
                                onDragCancel = { dragOffset = 0f },
                                onDragEnd = {
                                    val target = (actualIndex + (dragOffset / rowHeight).roundToInt()).coerceIn(queueIndex + 1, queue.lastIndex)
                                    dragOffset = 0f
                                    if (target != actualIndex) onMoveTrack(actualIndex, target)
                                }, onDrag = { change, delta -> change.consume(); dragOffset += delta.y })
                        }, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    })
                }
            }
        }
    }
}

@Composable
private fun QueueSongRow(track: Track, active: Boolean = false, onClick: () -> Unit, onOptions: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().background(if (active) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().height(72.dp).combinedClickable(onClick = onClick, onLongClick = onOptions, onLongClickLabel = "Song options"), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(if (active) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent))
            Spacer(Modifier.width(13.dp))
            AsyncImage(track.thumbnailUri, null, Modifier.size(48.dp), contentScale = ContentScale.Crop)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (track.durationSec > 0) Text("%d:%02d".format(track.durationSec / 60, track.durationSec % 60), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = onOptions, modifier = Modifier.size(40.dp)) { Icon(Icons.Filled.MoreVert, "Song options") }
            trailing()
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
