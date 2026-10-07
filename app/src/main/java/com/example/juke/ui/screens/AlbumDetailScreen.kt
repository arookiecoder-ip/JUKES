package com.example.juke.ui.screens

import com.example.juke.ui.components.stableStatusBarsPadding

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
        if (state.error != null && state.tracks.isEmpty()) {
            ConnectionErrorState(state.error.orEmpty(), {
                album?.let { albumDetailViewModel.loadAlbumDetails(it) }
            }, Modifier.fillMaxSize().padding(bottom = bottomPadding))
        } else if (album == null || (state.isLoading && state.tracks.isEmpty())) {
            MediaDetailSkeleton(modifier = Modifier.stableStatusBarsPadding(), contentPadding = PaddingValues(20.dp))
        } else AdaptiveDetailLayout(bottomPadding = bottomPadding, hero = {
                CollectionDetailHero(album, state.title, state.imageUrl, state.artist,
                    listOf("Album", state.year, "${state.tracks.size} tracks").filter { it.isNotBlank() }.joinToString(" · "),
                    onCreditClick = if (state.artistId.isNotBlank()) ({ onNavigateToArtist(state.artistId) }) else null,
                    onPlay = { musicViewModel.playCollection(album) }, onShuffle = { musicViewModel.playCollection(album, shuffle = true) },
                    onQueue = { musicViewModel.queueCollection(album, next = false) })

            }) {
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
                itemsIndexed(state.tracks, key = { index, track -> "${track.uuid}:$index" }, contentType = { _, _ -> "track" }) { index, track ->
                    SwipeToAddNextContainer(onAddNext = { musicViewModel.addNext(track) }, onAddToQueue = { musicViewModel.addToQueue(listOf(track)) }) {
                        FlatTrackRow(track.thumbnailUri, track.title, track.artist, if (track.durationSec > 0) "%d:%02d".format(track.durationSec / 60, track.durationSec % 60) else "",
                            onClick = { musicViewModel.setQueue(state.tracks, index) }, modifier = Modifier.padding(horizontal = 20.dp), track = track, showMore = true, number = index + 1)
                    }
                }
            }
        }
        DetailBackButton(onNavigateBack, Modifier.align(Alignment.TopStart))
    }
}
