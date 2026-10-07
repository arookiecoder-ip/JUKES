package com.example.juke.ui.components

import com.example.juke.ui.components.stableStatusBarsPadding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

internal val LocalDetailPaneHeight = compositionLocalOf<androidx.compose.ui.unit.Dp?> { null }

/** Artwork behind the entire hero, starting underneath the status bar and fading at the tracks. */
@Composable
fun DetailHero(image: String?, artist: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val surface = MaterialTheme.colorScheme.background
    val paneHeight = LocalDetailPaneHeight.current
    Box(Modifier.fillMaxWidth().clip(androidx.compose.ui.graphics.RectangleShape)) {
        AsyncImage(coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
            .data(image).size(if (artist) 960 else 256).crossfade(false).build(), null,  (if (artist) Modifier.fillMaxWidth().height(paneHeight ?: 380.dp) else Modifier.matchParentSize()).then(
            if (artist) Modifier.testTag("Artist banner") else Modifier.blur(22.dp).alpha(0.55f)), contentScale = ContentScale.Crop, alignment = Alignment.Center)
        Box((if (artist) Modifier.fillMaxWidth().height(paneHeight ?: 380.dp) else Modifier.matchParentSize()).background(Brush.verticalGradient(listOf(
            surface.copy(alpha = if (artist) 0.05f else 0.18f),
            surface.copy(alpha = 0.45f), surface))))
        if (paneHeight != null) Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
            0f to Color.Transparent, 0.72f to Color.Transparent, 1f to surface)))
        Column(Modifier.fillMaxWidth().then(paneHeight?.let { Modifier.heightIn(min = it) } ?: Modifier), content = content)
    }
}

@Composable
fun DetailBackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onBack, modifier = modifier.stableStatusBarsPadding().padding(8.dp)
        .size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.38f))) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
    }
}
