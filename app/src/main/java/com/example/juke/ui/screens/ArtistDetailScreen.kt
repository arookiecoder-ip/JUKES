package com.example.juke.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
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
    onNavigateToPlaylist: (BrowseItem) -> Unit = {},
    onNavigateToArtist: (BrowseItem) -> Unit = { searchViewModel.loadArtistDetails(it) },
    onShowAllSongs: () -> Unit = {},
    onShowAllReleases: (String) -> Unit = {},
    bottomPadding: Dp = 0.dp
) {
    val state by searchViewModel.artistDetailState.collectAsStateWithLifecycle()
    val artist = state.artist
    var expandedDescription by remember(artist?.id) { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        when {
            state.error != null && (artist == null || state.topTracks.isEmpty()) -> {
                ConnectionErrorState(state.error.orEmpty(), {
                    artist?.let { searchViewModel.loadArtistDetails(it) }
                }, Modifier.fillMaxSize().padding(bottom = bottomPadding))
            }
            state.isLoading || artist == null -> MediaDetailSkeleton(modifier = Modifier.statusBarsPadding(), contentPadding = PaddingValues(20.dp))
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp + bottomPadding),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item(key = "hero") {
                    DetailHero(state.imageUrl, artist = true) {
                    Column(Modifier.fillMaxWidth().heightIn(min = 380.dp).padding(top = 170.dp, start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.Bottom) {
                        Text(artist.title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                        if (state.subscribers.isNotBlank()) Text(state.subscribers + if (state.subscribers.contains("subscriber", true)) "" else " subscribers", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (state.description.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(state.description, maxLines = if (expandedDescription) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            if (state.description.length > 150) TextButton(onClick = { expandedDescription = !expandedDescription }) { Text(if (expandedDescription) "Less" else "More") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(enabled = state.topTracks.isNotEmpty(), onClick = { musicViewModel.setQueue(state.topTracks.shuffled(), 0) }) { Icon(Icons.Default.Shuffle, "Shuffle artist songs") }
                            OutlinedButton(enabled = state.topTracks.isNotEmpty(), onClick = { musicViewModel.startRadio(state.topTracks.first()) }) { Icon(Icons.Default.Radio, "Artist radio") }
                            TextButton(enabled = state.isSubscribed != null && !state.subscriptionBusy, onClick = searchViewModel::toggleSubscription) {
                                if (state.subscriptionBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                else Text(if (state.isSubscribed == true) "Subscribed" else "Subscribe")
                            }
                        }
                    }
                    }
                }
                state.error?.let { error -> item { Text(error, Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.error) } }
                if (state.topTracks.isNotEmpty()) {
                    item { Text("Top songs", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleLarge) }
                    itemsIndexed(state.topTracks.take(5), key = { index, track -> "$index-${track.ytVideoId}" }) { index, track ->
                        SwipeToAddNextContainer(onAddNext = { musicViewModel.addNext(track) }, onAddToQueue = { musicViewModel.addToQueue(listOf(track)) }) {
                            FlatTrackRow(track.thumbnailUri, track.title, track.artist, if (track.durationSec > 0) "%d:%02d".format(track.durationSec / 60, track.durationSec % 60) else "", onClick = { musicViewModel.setQueue(state.topTracks, index) }, modifier = Modifier.padding(horizontal = 20.dp), track = track)
                        }
                    }
                    if (state.topTracks.size > 5 || state.topSongsBrowseId.isNotBlank()) item {
                        TextButton(onClick = onShowAllSongs, modifier = Modifier.padding(horizontal = 12.dp)) {
                            Text("Show all songs")
                        }
                    }
                }
                fun releases(title: String, releases: List<BrowseItem>, kind: String, hasMore: Boolean) {
                    if (releases.isEmpty()) return
                    item(key = title) {
                        Column {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                                IconButton(onClick = { onShowAllReleases(kind) }) {
                                    Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowForward, "Show all $title")
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(releases, key = { it.id }) { item ->
                                    if (item.kind == "playlist") PlaylistCard(item, onClick = { onNavigateToPlaylist(item) }, onPlay = { musicViewModel.playCollection(item) })
                                    else AlbumCard(item, onClick = { onNavigateToAlbum(item) }, onPlay = { musicViewModel.playCollection(item) })
                                }
                            }
                        }
                    }
                }
                releases("Albums", state.albums, "albums", state.albumsHasMore)
                releases("Singles", state.singles, "singles", state.singlesHasMore)
                releases("Playlists", state.playlists, "playlists", state.playlists.size > 5)
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
        DetailBackButton(onNavigateBack, Modifier.align(Alignment.TopStart))
    }
}
