package com.example.juke.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState

// Existing component names are retained for source compatibility; all surfaces are flat and opaque.
enum class GlassLevel { Thin, Regular, Thick }
@Composable fun isGlassDark(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f
val LocalHazeState = compositionLocalOf<HazeState?> { null }
val LocalGlassAccent = compositionLocalOf { Color(0xFF8E7CFF) }
object GlassShapes {
    val Pill = RectangleShape
    val Control = RectangleShape
    val Card = RectangleShape
    val Sheet = RectangleShape
    val Bar = RectangleShape
}
object GlassPrefs { var solid by mutableStateOf(true) }
object GlassBackdrop {
    val Dark = Color.Black
    val Light = Color(0xFFF2F2F7)
    fun color(dark: Boolean): Color = if (dark) Dark else Light
}

@Composable
fun Modifier.glassPane(shape: Shape = GlassShapes.Card, level: GlassLevel = GlassLevel.Regular, tint: Color? = null): Modifier =
    clip(shape).background(MaterialTheme.colorScheme.surface)

@Composable
fun Modifier.glassFloat(shape: Shape = GlassShapes.Bar, level: GlassLevel = GlassLevel.Regular, tint: Color? = null, source: HazeState? = LocalHazeState.current): Modifier =
    clip(shape).background(MaterialTheme.colorScheme.surface)

// The tab group's spring/shift animation is independent of surface rendering.
@Composable
fun Modifier.glassLens(shape: Shape = GlassShapes.Control, tint: Color? = null, source: HazeState? = LocalHazeState.current, strength: () -> Float = { 0f }): Modifier =
    clip(shape).background(MaterialTheme.colorScheme.primaryContainer)

@Composable
fun GlassSurface(modifier: Modifier = Modifier, shape: Shape = GlassShapes.Card, level: GlassLevel = GlassLevel.Regular, floating: Boolean = false, tint: Color? = null, content: @Composable BoxScope.() -> Unit) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Box(modifier.glassPane(shape, level, tint), content = content)
    }
}

@Composable fun GlassWindowBlur(radius: Dp = 28.dp, dim: Float = 0.28f) { /* Standard platform dimming only. */ }
@Composable fun glassSheetColor(): Color = MaterialTheme.colorScheme.surface

@Composable
fun GlassCard(modifier: Modifier = Modifier, shape: Shape = GlassShapes.Card, level: GlassLevel = GlassLevel.Regular, accent: Color? = null, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Column(modifier.clip(shape).background(MaterialTheme.colorScheme.surface)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier), content = content)
    }
}
