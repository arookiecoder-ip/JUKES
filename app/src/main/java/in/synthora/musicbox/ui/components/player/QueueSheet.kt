package `in`.synthora.musicbox.ui.components.player

import `in`.synthora.musicbox.ui.components.stableNavigationBarsPadding

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import `in`.synthora.musicbox.models.Track
import `in`.synthora.musicbox.ui.components.QueueSongActions
import `in`.synthora.musicbox.ui.components.LocalMediaMenu
import `in`.synthora.musicbox.viewmodels.MusicUiState
import kotlin.math.roundToInt

/** The web queue: one numbered list, left drag grip, artwork, duration and a separate more button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueBottomSheetContent(currentTrack: Track, queue: List<Track>, queueIndex: Int, uiState: MusicUiState,
    onClose: () -> Unit, onMoveTrack: (Int, Int) -> Unit, onRemoveTrack: (String, (Boolean) -> Unit) -> Unit,
    onPlayTrack: (Track) -> Unit, statusText: String? = null, onShuffleUpcoming: () -> Unit = {},
    error: String? = null, onRetry: () -> Unit = {}, onSortUpcoming: () -> Unit = {}, onClearPlayed: () -> Unit = {}, onSaveAsPlaylist: () -> Unit = {}, showClose: Boolean = true) {
    val haptics = `in`.synthora.musicbox.utils.rememberJukeHaptics()
    val menu = LocalMediaMenu.current
    val scope = rememberCoroutineScope()
    val rows = remember(queue, currentTrack) { queue.ifEmpty { listOf(currentTrack) } }
    val currentIndex = if (queue.isEmpty()) 0 else queueIndex.takeIf { it in queue.indices }
        ?: queue.indexOfFirst { it.uuid == currentTrack.uuid || (it.ytVideoId != null && it.ytVideoId == currentTrack.ytVideoId) }
    val upcoming = (rows.size - currentIndex - 1).coerceAtLeast(0)
    val list = rememberLazyListState()
    val rowHeight = with(LocalDensity.current) { 64.dp.toPx() }
    var toolsOpen by remember { mutableStateOf(false) }
    // Anchor once when opened. Polling must not move the list while the user is scrolling it.
    LaunchedEffect(Unit) { if (currentIndex > 0) list.scrollToItem((currentIndex - 1).coerceAtLeast(0)) }
    val keys = remember(rows) {
        val occurrences = mutableMapOf<String, Int>()
        val videoCounts = rows.groupingBy { it.ytVideoId ?: it.uuid }.eachCount()
        rows.map { track ->
            val occurrence = occurrences.getOrDefault(track.uuid, 0)
            occurrences[track.uuid] = occurrence + 1
            "${track.uuid}:$occurrence:${videoCounts[track.ytVideoId ?: track.uuid]}"
        }
    }
    val byKey = remember(keys, rows) { keys.zip(rows).toMap() }
    val keyIndices = remember(keys) { keys.withIndex().associate { it.value to it.index } }
    var order by remember(keys) { mutableStateOf(keys) }
    var draggedKey by remember { mutableStateOf<String?>(null) }
    var origin by remember { mutableIntStateOf(-1) }
    var dragCenter by remember { mutableFloatStateOf(0f) }
    var dragDelta by remember { mutableFloatStateOf(0f) }
    var initialTop by remember { mutableFloatStateOf(0f) }
    fun updateTarget() {
        val key = draggedKey ?: return
        val from = order.indexOf(key)
        val neighbor = list.layoutInfo.visibleItemsInfo.firstOrNull {
            it.key != key && it.index < order.size &&
                ((it.index > from && dragCenter > it.offset + it.size / 2f) ||
                 (it.index < from && dragCenter < it.offset + it.size / 2f))
        } ?: return
        val target = neighbor.index
        if (from >= 0 && from != target) order = order.toMutableList().apply { add(target, removeAt(from)) }
    }
    LaunchedEffect(keys) { draggedKey = null; order = keys }
    LaunchedEffect(draggedKey) {
        while (draggedKey != null) {
            val layout = list.layoutInfo
            val edge = rowHeight
            val speed = when {
                dragCenter < layout.viewportStartOffset + edge -> -rowHeight / 6f
                dragCenter > layout.viewportEndOffset - edge -> rowHeight / 6f
                else -> 0f
            }
            if (speed != 0f) { list.scrollBy(speed); updateTarget() }
            delay(16)
        }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).stableNavigationBarsPadding()) {
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
            if (showClose) IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close queue") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (uiState.isQueueOperationInProgress) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!statusText.isNullOrBlank()) Text(statusText, Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let { Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(it, Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onRetry) { Text("Retry") }
        } }
        LazyColumn(Modifier.weight(1f).testTag("Queue rows"), state = list, contentPadding = PaddingValues(bottom = 12.dp)) {
            itemsIndexed(order, key = { _, key -> key }, contentType = { _, _ -> "queue-track" }) { position, key ->
                val track = byKey.getValue(key)
                val index = keyIndices.getValue(key)
                val active = index == currentIndex
                val editable = queue.isNotEmpty() && !uiState.isQueueOperationInProgress
                val dragging = draggedKey == key
                var hidden by remember(key) { mutableStateOf(false) }
                val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
                    if (editable && !active && !dragging && value == SwipeToDismissBoxValue.EndToStart) {
                        true
                    } else false
                })
                LaunchedEffect(dismiss.currentValue) {
                    if (dismiss.currentValue == SwipeToDismissBoxValue.EndToStart && !hidden) {
                        hidden = true
                        onRemoveTrack(track.uuid) { success ->
                            if (!success) scope.launch { dismiss.snapTo(SwipeToDismissBoxValue.Settled); hidden = false }
                        }
                    }
                }
                val grip = Modifier.size(width = 36.dp, height = 56.dp).pointerInput(key, editable) {
                    if (editable) detectDragGestures(onDragStart = {
                        haptics.gestureStart(); origin = index; draggedKey = key; dragDelta = 0f
                        initialTop = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.offset?.toFloat() ?: 0f
                        dragCenter = initialTop + rowHeight / 2
                    }, onDragCancel = { if (draggedKey == key) { draggedKey = null; order = keys } }, onDragEnd = {
                        if (draggedKey == key) {
                            haptics.gestureEnd()
                            val target = order.indexOf(key)
                            draggedKey = null
                            if (target >= 0 && target != origin) onMoveTrack(origin, target)
                        }
                    }, onDrag = { change, delta ->
                        change.consume(); dragDelta += delta.y
                        dragCenter = initialTop + rowHeight / 2 + dragDelta
                        updateTarget()
                    })
                }
                androidx.compose.animation.AnimatedVisibility(visible = !hidden,
                    modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = if (dragging) null else androidx.compose.animation.core.spring())
                        .zIndex(if (dragging) 2f else 0f).graphicsLayer { translationY = if (dragging) initialTop + dragDelta - (list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.offset ?: initialTop.toInt()) else 0f },
                    enter = androidx.compose.animation.expandVertically(expandFrom = Alignment.Top),
                    exit = androidx.compose.animation.shrinkVertically(animationSpec = androidx.compose.animation.core.tween(250), shrinkTowards = Alignment.Top)) {
                SwipeToDismissBox(dismiss, enableDismissFromStartToEnd = false,
                    enableDismissFromEndToStart = editable && !active && !dragging,
                    modifier = Modifier.testTag("Queue row ${track.uuid}").shadow(if (dragging) 8.dp else 0.dp),
                    backgroundContent = {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(end = 20.dp), contentAlignment = Alignment.CenterEnd) {
                            Icon(Icons.Default.Delete, "Remove from queue", tint = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }) {
                    fun songOptions() {
                        menu?.show(track, QueueSongActions(
                            play = { onPlayTrack(track) },
                            remove = if (editable && !active) ({ onRemoveTrack(track.uuid) {} }) else null
                        ))
                    }
                    QueueWebRow(track, position + 1, active, dragging, grip,
                        onPlay = { onPlayTrack(track) }, onLongClick = ::songOptions, onOptions = ::songOptions) {}

                }
                }
            }
            if (uiState.isLoading && queue.isEmpty()) item { `in`.synthora.musicbox.ui.components.TrackRowsSkeleton(4) }
            if (upcoming == 0 && !uiState.isLoading) item { Text("No upcoming songs", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
        Box(grip, contentAlignment = Alignment.Center) { Icon(Icons.Default.DragHandle, "Drag to reorder ${track.title}", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) }
        Text("$number", Modifier.width(20.dp), style = MaterialTheme.typography.labelSmall, color = if (active) accent else MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            `in`.synthora.musicbox.ui.components.ListArtwork(track.thumbnailUri, 40.dp, Modifier.fillMaxSize())
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
