package com.example.juke.ui.components.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.juke.ui.theme.GlassLevel
import com.example.juke.ui.theme.glassPane
import coil.request.ImageRequest
import com.example.juke.models.Track
import com.example.juke.utils.rememberJukeHaptics
import com.example.juke.viewmodels.MusicViewModel

@Composable
fun PlayerArtwork(
    queue: List<Track>,
    queueIndex: Int,
    currentTrack: Track,
    currentPosition: Long,
    showLyrics: Boolean,
    musicViewModel: MusicViewModel,
    isTablet: Boolean,
    onToggleLyrics: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = rememberJukeHaptics()

    val pagerState = rememberPagerState(
        initialPage = queueIndex.coerceAtLeast(0),
        pageCount = { queue.size.coerceAtLeast(1) }
    )

    // Sync external playback changes (next button, track end) to the Pager
    LaunchedEffect(queueIndex) {
        if (queueIndex in queue.indices && pagerState.currentPage != queueIndex) {
            pagerState.animateScrollToPage(queueIndex)
        }
    }

    // Sync manual user swipes to the ViewModel
    LaunchedEffect(pagerState.settledPage) {
        if (pagerState.settledPage != queueIndex && pagerState.settledPage in queue.indices) {
            val swipedTrack = queue[pagerState.settledPage]
            musicViewModel.playTrackFromQueue(swipedTrack)
            haptic.click()
        }
    }

    // Every page fills the same artwork slot, with no spacing or scale animation.
    val artworkModifier = modifier.fillMaxSize()

    if (queue.isEmpty()) {
        // Fallback if queue is empty for some reason
        ArtworkCard(
            track = currentTrack,
            showLyrics = showLyrics,
            currentPosition = currentPosition,
            musicViewModel = musicViewModel,
            isTablet = isTablet,
            onToggleLyrics = onToggleLyrics,
            modifier = artworkModifier
        )
    } else {
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(0.dp),
            pageSpacing = 0.dp,
            modifier = artworkModifier
        ) { page ->
            val pageTrack = if (page == queueIndex) {
                currentTrack
            } else {
                queue.getOrNull(page) ?: currentTrack
            }

            ArtworkCard(
                track = pageTrack,
                // Only show lyrics on the active page
                showLyrics = showLyrics && page == pagerState.currentPage,
                currentPosition = currentPosition,
                musicViewModel = musicViewModel,
                isTablet = isTablet,
                onToggleLyrics = onToggleLyrics,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun ArtworkCard(
    track: Track,
    showLyrics: Boolean,
    currentPosition: Long,
    musicViewModel: MusicViewModel,
    isTablet: Boolean,
    onToggleLyrics: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = rememberJukeHaptics()
    val context = LocalContext.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .clickable {
                haptic.click()
                onToggleLyrics()
            },
        contentAlignment = Alignment.Center
    ) {
        if (track.thumbnailUri != null) {
            // Use an explicit ImageRequest so Coil can key the memory/disk cache by URI
            // and immediately serve from cache when the composable is re-entered after
            // a track switch (avoids the blank-frame flash on already-rendered components).
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(track.thumbnailUri)
                    .memoryCacheKey(track.thumbnailUri)
                    .diskCacheKey(track.thumbnailUri)
                    .crossfade(true)
                    .build(),
                contentDescription = track.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(80.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        run {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxSize().background(
                androidx.compose.ui.graphics.Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color.Transparent, androidx.compose.ui.graphics.Color.Transparent, MaterialTheme.colorScheme.background))))
        }

        Box(Modifier.align(Alignment.TopCenter).fillMaxSize().background(
            androidx.compose.ui.graphics.Brush.verticalGradient(
                0f to androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.32f),
                0.28f to androidx.compose.ui.graphics.Color.Transparent,
                1f to androidx.compose.ui.graphics.Color.Transparent)))

        if (showLyrics) {
            LyricsOverlay(
                currentTrack = track,
                currentPosition = currentPosition,
                musicViewModel = musicViewModel,
                isTablet = isTablet,
                onDismiss = onToggleLyrics
            )
        }
    }
}
