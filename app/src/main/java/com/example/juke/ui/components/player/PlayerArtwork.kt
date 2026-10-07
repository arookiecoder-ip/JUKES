package com.example.juke.ui.components.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.SizeTransform
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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

    val dragged by pagerState.interactionSource.collectIsDraggedAsState()
    var userSwipe by remember { mutableStateOf(false) }
    LaunchedEffect(dragged) { if (dragged) userSwipe = true }
    var alignedSong by remember { mutableStateOf(currentTrack.ytVideoId ?: currentTrack.uuid) }
    LaunchedEffect(queueIndex, currentTrack.ytVideoId) {
        if (!dragged && queueIndex in queue.indices && pagerState.currentPage != queueIndex) {
            userSwipe = false
            // An output handoff may rebuild/reorder the same queue. A changed
            // page index alone is not a new song and must not animate artwork.
            if (alignedSong == (currentTrack.ytVideoId ?: currentTrack.uuid)) pagerState.scrollToPage(queueIndex)
            else pagerState.animateScrollToPage(queueIndex)
        }
        alignedSong = currentTrack.ytVideoId ?: currentTrack.uuid
    }
    LaunchedEffect(pagerState.isScrollInProgress, pagerState.settledPage) {
        if (!pagerState.isScrollInProgress && userSwipe) {
            userSwipe = false
            if (pagerState.settledPage != queueIndex && pagerState.settledPage in queue.indices) {
                musicViewModel.playTrackFromQueue(queue[pagerState.settledPage])
                haptic.click()
            }
        }
    }

    // Every page fills the same artwork slot, with no spacing or scale animation.
    val artworkModifier = modifier.fillMaxSize()

    if (queue.isEmpty() || queue.getOrNull(queueIndex)?.ytVideoId != currentTrack.ytVideoId) {
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

            ArtworkCardContent(
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
    AnimatedContent(targetState = track, contentKey = { it.ytVideoId }, modifier = modifier.fillMaxSize(),
        transitionSpec = { (slideInHorizontally { it } togetherWith slideOutHorizontally { -it }).using(SizeTransform(sizeAnimationSpec = { _, _ -> androidx.compose.animation.core.snap() })) },
        label = "Song artwork transition") { shownTrack ->
        ArtworkCardContent(shownTrack, showLyrics && shownTrack.ytVideoId == track.ytVideoId,
            currentPosition, musicViewModel, isTablet, onToggleLyrics, Modifier.fillMaxSize())
    }
}

@Composable
private fun ArtworkCardContent(
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
        com.example.juke.ui.components.TrackArtwork(track, Modifier.fillMaxSize(), large = true)

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
