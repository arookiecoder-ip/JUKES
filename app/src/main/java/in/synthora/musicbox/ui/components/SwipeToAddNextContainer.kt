package `in`.synthora.musicbox.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import `in`.synthora.musicbox.utils.rememberJukeHaptics
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Horizontal intent must win before consuming movement from a vertical list. */
@Composable
fun SwipeToAddNextContainer(onAddNext: () -> Unit, onAddToQueue: () -> Unit,
    modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val next by rememberUpdatedState(onAddNext)
    val queue by rememberUpdatedState(onAddToQueue)
    val haptic = rememberJukeHaptics()
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var width by remember { mutableFloatStateOf(0f) }
    Box(modifier.fillMaxWidth().clipToBounds().onSizeChanged { width = it.width.toFloat() }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var dx = 0f; var dy = 0f; var horizontal = false
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.isConsumed) break
                    val delta = change.positionChange()
                    dx += delta.x; dy += delta.y
                    if (!horizontal) {
                        if (abs(dy) > viewConfiguration.touchSlop && abs(dy) >= abs(dx) / 1.8f) break
                        horizontal = abs(dx) > viewConfiguration.touchSlop * 2 && abs(dx) > abs(dy) * 1.8f
                    }
                    if (horizontal) {
                        change.consume()
                        val position = dx.coerceIn(-width, width)
                        scope.launch { offset.snapTo(position) }
                    }
                    if (!change.pressed) {
                        if (horizontal && abs(dx) > width * 0.35f) {
                            haptic.confirm()
                            if (dx > 0) queue() else next()
                        }
                        break
                    }
                }
                scope.launch { offset.animateTo(0f) }
            }
        }) {
        if (abs(offset.value) > 1f) {
            val right = offset.value > 0
            Row(Modifier.matchParentSize().background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (right) Arrangement.Start else Arrangement.End) {
                Icon(if (right) Icons.Default.QueueMusic else Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text(if (right) "Add to queue" else "Play next")
            }
        }
        Row(Modifier.fillMaxWidth().graphicsLayer { translationX = offset.value }
            .background(MaterialTheme.colorScheme.background), content = content)
    }
}
