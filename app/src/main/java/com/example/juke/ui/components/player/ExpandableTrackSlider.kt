package com.example.juke.ui.components.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A thumb-free web-style track with a full finger-sized touch target. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExpandableTrackSlider(
    value: Float,
    label: String,
    onValueChangeFinished: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onValueChange: (Float) -> Unit = {},
    restingHeight: Dp = 3.dp,
    draggingHeight: Dp = 9.dp
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val dragged by interaction.collectIsDraggedAsState()
    val active = pressed || dragged
    var local by remember { mutableFloatStateOf(value.coerceIn(0f, 1f)) }
    LaunchedEffect(value, active) { if (!active) local = value.coerceIn(0f, 1f) }
    val thickness by animateDpAsState(if (active) draggingHeight else restingHeight,
        animationSpec = tween(120), label = "trackThickness")
    val fill = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.38f)
    val background = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.24f else 0.12f)
    Slider(
        value = if (active) local else value.coerceIn(0f, 1f),
        onValueChange = { local = it; onValueChange(it) },
        onValueChangeFinished = { onValueChangeFinished(local) },
        enabled = enabled,
        interactionSource = interaction,
        thumb = { Spacer(Modifier.size(0.dp)) },
        track = { state ->
            Canvas(Modifier.fillMaxWidth().height(thickness).testTag("$label track").clip(RoundedCornerShape(50))) {
                drawRect(background)
                drawRect(fill, size = Size(size.width * state.value.coerceIn(0f, 1f), size.height))
            }
        },
        modifier = modifier.fillMaxWidth().height(40.dp).semantics {
            contentDescription = label
            setProgress { target ->
                if (!enabled) false else { onValueChangeFinished(target.coerceIn(0f, 1f)); true }
            }
        }
    )
}
