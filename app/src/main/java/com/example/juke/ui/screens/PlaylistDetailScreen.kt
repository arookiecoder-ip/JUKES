package com.example.juke.ui.screens

import com.example.juke.ui.components.FlatTrackRow
import com.example.juke.ui.components.GlassButton
import com.example.juke.ui.components.GlassTopAppBar
import com.example.juke.ui.theme.GlassCard
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.juke.models.Track
import com.example.juke.ui.components.MediaDetailSkeleton
import com.example.juke.ui.components.SwipeToAddNextContainer
import com.example.juke.viewmodels.MusicViewModel
import com.example.juke.viewmodels.PlaylistDetailViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    playlistDetailViewModel: PlaylistDetailViewModel = viewModel(),
    musicViewModel: MusicViewModel = viewModel(),
    onNavigateBack: () -> Unit,
    bottomPadding: Dp = 0.dp
) {
    val uiState by playlistDetailViewModel.uiState.collectAsStateWithLifecycle()
    val playlist = uiState.playlist

    Scaffold(
        topBar = {
            GlassTopAppBar(
                title = {
                    Text(
                        uiState.title.ifBlank { "Playlist" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        },
        containerColor = androidx.compose.ui.graphics.Color.Transparent
    ) { paddingValues ->
        if (playlist == null || (uiState.isLoading && uiState.tracks.isEmpty())) {
            MediaDetailSkeleton(
                modifier = Modifier.padding(paddingValues),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    top = 16.dp,
                    end = 20.dp,
                    bottom = 16.dp + bottomPadding
                )
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    top = 16.dp,
                    end = 20.dp,
                    bottom = 16.dp + bottomPadding
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Playlist Header
                item {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        AsyncImage(
                            model = uiState.imageUrl,
                            contentDescription = uiState.title,
                            modifier = Modifier
                                .size(200.dp)
                                .clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = uiState.title,
                            style = MaterialTheme.typography.headlineMedium
                        )

                        if (uiState.author.isNotBlank()) {
                            Text(
                                text = "By ${uiState.author}",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Text(
                            text = "${uiState.trackCount} tracks",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (uiState.description.isNotBlank()) {
                            Text(
                                text = uiState.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }

                // Tracks Section
                if (uiState.tracks.isNotEmpty()) {
                    item {
                        Text(
                            text = "Tracks",
                            style = MaterialTheme.typography.titleLarge
                        )
                    }

                    itemsIndexed(uiState.tracks) { index, track ->
                        // Next page when the end of the loaded songs comes into view.
                        if (index >= uiState.tracks.size - 5) {
                            androidx.compose.runtime.LaunchedEffect(uiState.tracks.size) {
                                playlistDetailViewModel.loadMore()
                            }
                        }
                        SwipeToAddNextContainer(
                            onAddNext = { musicViewModel.addNext(track) }
                        ) {
                            TrackItem(
                                track = track,
                                onClick = {
                                    musicViewModel.playPlaylist(
                                        playlistDetailViewModel.playlistId,
                                        uiState.tracks,
                                        index
                                    )
                                }
                            )
                        }
                    }
                    if (uiState.isLoadingMore) {
                        item {
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackItem(
    track: Track,
    onClick: () -> Unit
) {
    FlatTrackRow(
        track = track,
        imageUrl = track.thumbnailUri,
        title = track.title,
        subtitle = track.artist,
        duration = formatDuration(track.durationSec),
        onClick = onClick
    )
}

private fun formatDuration(durationSec: Int): String =
    "%d:%02d".format(durationSec / 60, durationSec % 60)
