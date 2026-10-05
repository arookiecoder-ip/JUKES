package com.example.juke.ui.screens

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
        if (playlist == null || (state.isLoading && state.tracks.isEmpty())) {
            MediaDetailSkeleton(modifier = Modifier.statusBarsPadding(), contentPadding = PaddingValues(20.dp))
        } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding + 16.dp)) {
            item(key = "hero") {
                DetailHero(state.imageUrl) {
                    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        AsyncImage(state.imageUrl, state.title, Modifier.size(200.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                        Spacer(Modifier.height(12.dp))
                        Text(state.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        if (state.author.isNotBlank()) Text("By ${state.author}", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${state.trackCount} tracks", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        if (state.description.isNotBlank()) Text(state.description, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    CollectionActions(playlist, { musicViewModel.playCollection(playlist) },
                        { musicViewModel.playCollection(playlist, shuffle = true) }, { musicViewModel.queueCollection(playlist, next = false) })
                }
            }
            if (state.tracks.isNotEmpty()) {
                item { Text("Tracks", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge) }
                itemsIndexed(state.tracks) { index, track ->
                    if (index >= state.tracks.size - 5) LaunchedEffect(state.tracks.size) { playlistDetailViewModel.loadMore() }
                    SwipeToAddNextContainer(onAddNext = { musicViewModel.addNext(track) }) {
                        FlatTrackRow(track.thumbnailUri, track.title, track.artist, "%d:%02d".format(track.durationSec / 60, track.durationSec % 60),
                            onClick = { musicViewModel.playPlaylist(playlistDetailViewModel.playlistId, state.tracks, index) },
                            modifier = Modifier.padding(horizontal = 20.dp), track = track)
                    }
                }
                if (state.isLoadingMore) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(12.dp)) }
            }
        }
        DetailBackButton(onNavigateBack, Modifier.align(Alignment.TopStart))
    }
}
