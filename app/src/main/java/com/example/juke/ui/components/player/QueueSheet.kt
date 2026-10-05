package com.example.juke.ui.components.player

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
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

/** The web queue: one numbered list, left drag grip, artwork, duration and a separate more button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueBottomSheetContent(currentTrack: Track, queue: List<Track>, queueIndex: Int, uiState: MusicUiState,
    onClose: () -> Unit, onMoveTrack: (Int, Int) -> Unit, onRemoveTrack: (String) -> Unit,
    onPlayTrack: (Track) -> Unit, statusText: String? = null, onShuffleUpcoming: () -> Unit = {},
    onSortUpcoming: () -> Unit = {}, onClearPlayed: () -> Unit = {}, onSaveAsPlaylist: () -> Unit = {}) {
    val menu = LocalMediaMenu.current
    val rows = queue.ifEmpty { listOf(currentTrack) }
    val currentIndex = if (queue.isEmpty()) 0 else queueIndex.takeIf { it in queue.indices }
        ?: queue.indexOfFirst { it.uuid == currentTrack.uuid || (it.ytVideoId != null && it.ytVideoId == currentTrack.ytVideoId) }
    val upcoming = (rows.size - currentIndex - 1).coerceAtLeast(0)
    val list = rememberLazyListState()
    val rowHeight = with(LocalDensity.current) { 64.dp.toPx() }
    var toolsOpen by remember { mutableStateOf(false) }
    // Anchor once when opened. Polling must not move the list while the user is scrolling it.
    LaunchedEffect(Unit) { if (currentIndex > 0) list.scrollToItem((currentIndex - 1).coerceAtLeast(0)) }
    val occurrences = mutableMapOf<String, Int>()
    val keys = rows.map { track ->
        val id = track.ytVideoId ?: track.uuid
        val occurrence = occurrences.getOrDefault(id, 0)
        occurrences[id] = occurrence + 1
        "$id:$occurrence"
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Queue", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("${rows.size} songs", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { toolsOpen = true }) { Icon(Icons.Default.MoreVert, "Queue options") }
                DropdownMenu(toolsOpen, { toolsOpen = false }) {
                    DropdownMenuItem(text = { Text("Shuffle upcoming") }, enabled = upcoming > 1, onClick = { toolsOpen = false; onShuffleUpcoming() })
                    DropdownMenuItem(text = { Text("Sort upcoming") }, enabled = upcoming > 1, onClick = { toolsOpen = false; onSortUpcoming() })
                    DropdownMenuItem(text = { Text("Clear played songs") }, enabled = currentIndex > 0, onClick = { toolsOpen = false; onClearPlayed() })
                    DropdownMenuItem(text = { Text("Save queue to playlist") }, onClick = { toolsOpen = false; onSaveAsPlaylist() })
                }
            }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close queue") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (uiState.isQueueOperationInProgress) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!statusText.isNullOrBlank()) Text(statusText, Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(bottom = 12.dp)) {
            itemsIndexed(rows, key = { index, _ -> keys[index] }) { index, track ->
                val active = index == currentIndex
                val editable = queue.isNotEmpty() && !uiState.isQueueOperationInProgress
                var offset by remember { mutableFloatStateOf(0f) }
                var dragging by remember { mutableStateOf(false) }
                var options by remember { mutableStateOf(false) }
                val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
                    if (editable && !active && !dragging && value == SwipeToDismissBoxValue.EndToStart) {
                        onRemoveTrack(track.uuid); true
                    } else false
                })
                val grip = Modifier.size(width = 28.dp, height = 48.dp).pointerInput(index, queue.size, editable) {
                    if (editable) detectDragGestures(onDragStart = { dragging = true },
                        onDragCancel = { dragging = false; offset = 0f },
                        onDragEnd = {
                            val target = (index + (offset / rowHeight).roundToInt()).coerceIn(0, queue.lastIndex)
                            dragging = false; offset = 0f
                            if (target != index) onMoveTrack(index, target)
                        }, onDrag = { change, delta ->
                            change.consume()
                            offset = (offset + delta.y).coerceIn(-index * rowHeight, (queue.lastIndex - index) * rowHeight)
                        })
                }
                SwipeToDismissBox(dismiss, enableDismissFromStartToEnd = false,
                    enableDismissFromEndToStart = editable && !active && !dragging,
                    modifier = Modifier.zIndex(if (dragging) 2f else 0f).graphicsLayer { translationY = offset },
                    backgroundContent = {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(end = 20.dp), contentAlignment = Alignment.CenterEnd) {
                            Icon(Icons.Default.Delete, "Remove from queue", tint = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }) {
                    QueueWebRow(track, index + 1, active, dragging, grip,
                        onPlay = { onPlayTrack(track) }, onLongClick = { menu?.show(track) }, onOptions = { options = true }) {
                        DropdownMenu(options, { options = false }) {
                            DropdownMenuItem(text = { Text("Play now") }, onClick = { options = false; onPlayTrack(track) })
                            DropdownMenuItem(text = { Text("Move up") }, enabled = editable && index > 0, onClick = { options = false; onMoveTrack(index, index - 1) })
                            DropdownMenuItem(text = { Text("Move down") }, enabled = editable && index < queue.lastIndex, onClick = { options = false; onMoveTrack(index, index + 1) })
                            DropdownMenuItem(text = { Text("Remove from queue") }, enabled = editable && !active, onClick = { options = false; onRemoveTrack(track.uuid) })
                            DropdownMenuItem(text = { Text("Song options") }, onClick = { options = false; menu?.show(track) })
                        }
                    }
                }
            }
            if (upcoming == 0) item { Text("No upcoming songs", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun QueueWebRow(track: Track, number: Int, active: Boolean, lifted: Boolean, grip: Modifier,
    onPlay: () -> Unit, onLongClick: () -> Unit, onOptions: () -> Unit, options: @Composable BoxScope.() -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val divider = MaterialTheme.colorScheme.outlineVariant
    Row(Modifier.fillMaxWidth().height(64.dp).background(if (active || lifted) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface)
        .drawBehind {
            if (active) drawRect(accent, size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height))
            drawLine(divider, androidx.compose.ui.geometry.Offset(0f, size.height), androidx.compose.ui.geometry.Offset(size.width, size.height), 1.dp.toPx())
        }.combinedClickable(onClick = onPlay, onLongClick = onLongClick, onLongClickLabel = "Song options").padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.DragIndicator, "Drag to reorder ${track.title}", grip, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        Text("$number", Modifier.width(20.dp), style = MaterialTheme.typography.labelSmall, color = if (active) accent else MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            AsyncImage(track.thumbnailUri, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (active) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Equalizer, "Playing", Modifier.size(24.dp), tint = accent)
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold, color = if (active) accent else MaterialTheme.colorScheme.onSurface)
            Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (track.durationSec > 0) Text("%d:%02d".format(track.durationSec / 60, track.durationSec % 60), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            IconButton(onClick = onOptions, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.MoreVert, "Options for ${track.title}") }
            options()
        }
    }
}
