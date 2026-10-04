package com.example.juke.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.juke.network.BrowseItem
import com.example.juke.ui.components.*
import com.example.juke.viewmodels.MusicViewModel
import com.example.juke.viewmodels.SearchViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistDetailScreen(
    searchViewModel: SearchViewModel = viewModel(),
    musicViewModel: MusicViewModel = viewModel(),
    onNavigateBack: () -> Unit,
    onNavigateToAlbum: (BrowseItem) -> Unit = {},
    onNavigateToArtist: (BrowseItem) -> Unit = { searchViewModel.loadArtistDetails(it) },
    bottomPadding: Dp = 0.dp
) {
    val state by searchViewModel.artistDetailState.collectAsStateWithLifecycle()
    val artist = state.artist
    var expandedDescription by remember(artist?.id) { mutableStateOf(false) }
    var allSongs by remember(artist?.id) { mutableStateOf(false) }
    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = { GlassTopAppBar(title = { Text(artist?.title ?: "Artist") }, navigationIcon = {
            IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        }) }
    ) { padding ->
        when {
            state.error != null && (artist == null || state.topTracks.isEmpty()) -> {
                Column(Modifier.padding(padding).padding(24.dp)) {
                    Text(state.error!!, color = MaterialTheme.colorScheme.error)
                    artist?.let { TextButton(onClick = { searchViewModel.loadArtistDetails(it) }) { Text("Retry") } }
                }
            }
            state.isLoading || artist == null -> MediaDetailSkeleton(Modifier.padding(padding), contentPadding = PaddingValues(20.dp))
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = 24.dp + bottomPadding),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item(key = "hero") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                        AsyncImage(state.imageUrl, artist.title, modifier = Modifier.fillMaxWidth().aspectRatio(1.8f).clip(RoundedCornerShape(20.dp)), contentScale = ContentScale.Crop)
                        Spacer(Modifier.height(16.dp))
                        Text(artist.title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                        if (state.subscribers.isNotBlank()) Text(state.subscribers + if (state.subscribers.contains("subscriber", true)) "" else " subscribers", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (state.description.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(state.description, maxLines = if (expandedDescription) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            if (state.description.length > 150) TextButton(onClick = { expandedDescription = !expandedDescription }) { Text(if (expandedDescription) "Less" else "More") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(enabled = state.topTracks.isNotEmpty(), onClick = { musicViewModel.setQueue(state.topTracks.shuffled(), 0) }) { Text("Shuffle") }
                            OutlinedButton(enabled = state.topTracks.isNotEmpty(), onClick = { musicViewModel.startRadio(state.topTracks.first()) }) { Text("Radio") }
                            TextButton(enabled = state.isSubscribed != null && !state.subscriptionBusy, onClick = searchViewModel::toggleSubscription) {
                                if (state.subscriptionBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                else Text(if (state.isSubscribed == true) "Subscribed" else "Subscribe")
                            }
                        }
                    }
                }
                state.error?.let { error -> item { Text(error, Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.error) } }
                if (state.topTracks.isNotEmpty()) {
                    item { Text("Top songs", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleLarge) }
                    itemsIndexed(if (allSongs) state.topTracks else state.topTracks.take(5), key = { index, track -> "$index-${track.ytVideoId}" }) { index, track ->
                        SwipeToAddNextContainer(onAddNext = { musicViewModel.addNext(track) }) {
                            FlatTrackRow(track.thumbnailUri, track.title, track.artist, if (track.durationSec > 0) "%d:%02d".format(track.durationSec / 60, track.durationSec % 60) else "", onClick = { musicViewModel.setQueue(state.topTracks, index) }, modifier = Modifier.padding(horizontal = 20.dp), track = track)
                        }
                    }
                    if (state.topTracks.size > 5) item { TextButton(onClick = { allSongs = !allSongs }, modifier = Modifier.padding(horizontal = 12.dp)) { Text(if (allSongs) "Show less" else "Show all songs") } }
                }
                fun releases(title: String, releases: List<BrowseItem>) {
                    if (releases.isEmpty()) return
                    item(key = title) {
                        Column {
                            Text(title, Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(12.dp))
                            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(releases, key = { it.id }) { album -> AlbumCard(album, onClick = { onNavigateToAlbum(album) }) }
                            }
                        }
                    }
                }
                releases("Albums", state.albums)
                releases("Singles", state.singles)
                if (state.related.isNotEmpty()) item(key = "related") {
                    Column {
                        Text("Related artists", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(12.dp))
                        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(state.related, key = { it.id }) { related -> ArtistCard(related, onClick = { onNavigateToArtist(related) }) }
                        }
                    }
                }
            }
        }
    }
}
