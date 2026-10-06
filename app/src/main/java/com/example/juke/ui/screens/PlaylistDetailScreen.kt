package com.example.juke.ui.screens

import com.example.juke.ui.components.stableStatusBarsPadding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.juke.ui.components.*
import com.example.juke.viewmodels.PlaylistDetailViewModel
import com.example.juke.viewmodels.MusicViewModel

@Composable
fun PlaylistDetailScreen(playlistDetailViewModel: PlaylistDetailViewModel = viewModel(), musicViewModel: MusicViewModel = viewModel(),
    onNavigateBack: () -> Unit, bottomPadding: Dp = 0.dp) {
    val state by playlistDetailViewModel.uiState.collectAsStateWithLifecycle()
    val playlist = state.playlist
    Box(Modifier.fillMaxSize()) {
        if (state.error != null && state.tracks.isEmpty()) {
            ConnectionErrorState(state.error.orEmpty(), {
                playlist?.let { playlistDetailViewModel.loadPlaylistDetails(it) }
            }, Modifier.fillMaxSize().padding(bottom = bottomPadding))
        } else if (playlist == null || (state.isLoading && state.tracks.isEmpty())) {
            MediaDetailSkeleton(modifier = Modifier.stableStatusBarsPadding(), contentPadding = PaddingValues(20.dp))
        } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding + 16.dp)) {
            item(key = "hero") {
                CollectionDetailHero(playlist, state.title, state.imageUrl, state.author.takeIf { it.isNotBlank() }?.let { "By $it" }.orEmpty(),
                    "${state.trackCount} tracks", state.description,
                    onPlay = { musicViewModel.playCollection(playlist) }, onShuffle = { musicViewModel.playCollection(playlist, shuffle = true) },
                    onQueue = { musicViewModel.queueCollection(playlist, next = false) })
            }
            state.error?.let { message ->
                item(key = "load-error") {
                    Column(Modifier.fillMaxWidth().padding(20.dp)) {
                        Text(message, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { playlistDetailViewModel.loadPlaylistDetails(playlist) }) { Text("Retry") }
                    }
                }
            }
            if (state.tracks.isNotEmpty()) {
                item { Text("Tracks", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge) }
                itemsIndexed(state.tracks) { index, track ->
                    if (index >= state.tracks.size - 5) LaunchedEffect(state.tracks.size) { playlistDetailViewModel.loadMore() }
                    SwipeToAddNextContainer(onAddNext = { musicViewModel.addNext(track) }, onAddToQueue = { musicViewModel.addToQueue(listOf(track)) }) {
                        FlatTrackRow(track.thumbnailUri, track.title, track.artist, if (track.durationSec > 0) "%d:%02d".format(track.durationSec / 60, track.durationSec % 60) else "",
                            onClick = { musicViewModel.playPlaylist(playlistDetailViewModel.playlistId, state.tracks, index) },
                            modifier = Modifier.padding(horizontal = 20.dp), track = track, showMore = true)
                    }
                }
                if (state.isLoadingMore) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(12.dp)) }
            }
        }
        DetailBackButton(onNavigateBack, Modifier.align(Alignment.TopStart))
    }
}
