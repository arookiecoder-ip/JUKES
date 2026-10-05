package com.example.juke.ui.screens

import androidx.compose.foundation.clickable
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
import com.example.juke.viewmodels.AlbumDetailViewModel
import com.example.juke.viewmodels.MusicViewModel

@Composable
fun AlbumDetailScreen(albumDetailViewModel: AlbumDetailViewModel = viewModel(), musicViewModel: MusicViewModel = viewModel(),
    onNavigateBack: () -> Unit, onNavigateToArtist: (String) -> Unit = {}, bottomPadding: Dp = 0.dp) {
    val state by albumDetailViewModel.uiState.collectAsStateWithLifecycle()
    val album = state.album
    Box(Modifier.fillMaxSize()) {
        if (album == null || (state.isLoading && state.tracks.isEmpty())) {
            MediaDetailSkeleton(modifier = Modifier.statusBarsPadding(), contentPadding = PaddingValues(20.dp))
        } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding + 16.dp)) {
            item(key = "hero") {
                DetailHero(state.imageUrl) {
                    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        AsyncImage(state.imageUrl, state.title, Modifier.size(200.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                        Spacer(Modifier.height(12.dp))
                        Text(state.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(state.artist, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable(enabled = state.artistId.isNotBlank()) { onNavigateToArtist(state.artistId) })
                        Text(listOf("Album", state.year, "${state.tracks.size} tracks").filter { it.isNotBlank() }.joinToString(" · "),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    }
                    CollectionActions(album, { musicViewModel.playCollection(album) },
                        { musicViewModel.playCollection(album, shuffle = true) }, { musicViewModel.queueCollection(album, next = false) })
                }
            }
            state.error?.let { message ->
                item(key = "load-error") {
                    Column(Modifier.fillMaxWidth().padding(20.dp)) {
                        Text(message, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { albumDetailViewModel.loadAlbumDetails(album) }) { Text("Retry") }
                    }
                }
            }
            if (state.tracks.isNotEmpty()) {
                item { Text("Tracks", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge) }
                itemsIndexed(state.tracks) { index, track ->
                    SwipeToAddNextContainer(onAddNext = { musicViewModel.addNext(track) }, onAddToQueue = { musicViewModel.addToQueue(listOf(track)) }) {
                        FlatTrackRow(track.thumbnailUri, track.title, track.artist, "%d:%02d".format(track.durationSec / 60, track.durationSec % 60),
                            onClick = { musicViewModel.setQueue(state.tracks, index) }, modifier = Modifier.padding(horizontal = 20.dp), track = track)
                    }
                }
            }
        }
        DetailBackButton(onNavigateBack, Modifier.align(Alignment.TopStart))
    }
}
