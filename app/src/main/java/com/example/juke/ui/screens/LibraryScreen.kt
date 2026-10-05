package com.example.juke.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.juke.network.BrowseItem
import com.example.juke.network.text
import com.example.juke.ui.components.CreatePlaylistDialog
import com.example.juke.ui.components.GlassAlertDialog
import com.example.juke.ui.components.GlassFilterChip
import com.example.juke.ui.components.LocalMediaMenu
import com.example.juke.ui.components.SearchHeader
import com.example.juke.ui.components.TrackListSkeleton
import com.example.juke.viewmodels.LibraryViewModel
import com.example.juke.viewmodels.MusicViewModel

private enum class LibraryFilter(val label: String, val kind: String?) {
    ALL("All", null), PLAYLISTS("Playlists", "playlist"), ALBUMS("Albums", "album"), ARTISTS("Artists", "artist"), DOWNLOADS("Downloads", "download")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    musicViewModel: MusicViewModel,
    libraryViewModel: LibraryViewModel = viewModel(),
    onOpenArtist: (BrowseItem) -> Unit = {},
    onOpenCollection: (BrowseItem) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    bottomPadding: Dp = 0.dp
) {
    val state by libraryViewModel.uiState.collectAsStateWithLifecycle()
    val downloadedCollections by musicViewModel.downloadedCollections.collectAsStateWithLifecycle()
    var downloadedCollection by remember { mutableStateOf<com.example.juke.services.DownloadedCollection?>(null) }
    val downloaded by musicViewModel.downloadedTracks.collectAsStateWithLifecycle()
    val online by com.example.juke.network.NetworkFeedback.online.collectAsStateWithLifecycle()
    val downloadedSongs = remember(downloaded, state.searchQuery) {
        downloaded.filter { it.title.contains(state.searchQuery, true) || it.artist.contains(state.searchQuery, true) }
    }
    val collectionDownloads = downloadedCollections.filter { it.title.contains(state.searchQuery, true) || it.subtitle.contains(state.searchQuery, true) }
    val context = LocalContext.current
    val mediaMenu = LocalMediaMenu.current
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var grid by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf(LibraryFilter.ALL) }
    var createPlaylist by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<BrowseItem?>(null) }
    var delete by remember { mutableStateOf<BrowseItem?>(null) }
    LaunchedEffect(Unit) {
        libraryViewModel.start()
    }
    val entries = remember(state.playlists, state.albums, state.artists, filter, state.searchQuery) {
        val query = state.searchQuery.trim()
        (state.playlists + state.albums + state.artists).filter {
            (filter.kind == null || it.kind == filter.kind) &&
                (query.isEmpty() || it.title.contains(query, ignoreCase = true) || librarySubtitle(it).contains(query, ignoreCase = true))
        }
    }
    fun open(item: BrowseItem) {
        if (item.kind == "artist") onOpenArtist(item) else onOpenCollection(item)
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            SearchHeader(
                title = "Your Library", query = state.searchQuery,
                onQueryChange = libraryViewModel::updateSearchQuery,
                open = searchOpen || state.searchQuery.isNotEmpty(),
                onOpenChange = { searchOpen = it }, placeholder = "Search your library"
            ) {
                IconButton(onClick = onOpenHistory) { Icon(Icons.Default.History, "Listening history") }
                IconButton(onClick = { grid = !grid }) {
                    Icon(if (grid) Icons.Default.ViewList else Icons.Default.GridView,
                        contentDescription = if (grid) "Switch to list view" else "Switch to grid view")
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { createPlaylist = true },
                modifier = Modifier.padding(bottom = bottomPadding),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) { Icon(Icons.Default.Add, "Create playlist") }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(LibraryFilter.entries) { option ->
                    FilterChip(selected = option == filter, onClick = { filter = option }, label = { Text(option.label) },
                        shape = androidx.compose.ui.graphics.RectangleShape,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = option == filter,
                            borderColor = MaterialTheme.colorScheme.outlineVariant,
                            selectedBorderColor = MaterialTheme.colorScheme.primary,
                            borderWidth = 1.dp, selectedBorderWidth = 1.dp))
                }
            }
            if (filter != LibraryFilter.DOWNLOADS && online && state.error != null && entries.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(state.error.orEmpty(), Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = libraryViewModel::refresh) { Text("Retry") }
            }
            PullToRefreshBox(isRefreshing = filter != LibraryFilter.DOWNLOADS && online && state.isLoading && entries.isNotEmpty(), onRefresh = libraryViewModel::refresh,
                modifier = Modifier.fillMaxSize()) {
                when {
                    filter == LibraryFilter.DOWNLOADS -> {
                        if (downloadedSongs.isEmpty() && collectionDownloads.isEmpty()) LibraryNotice("No downloads", "Download songs, albums or playlists from their options to listen without internet.")
                        else LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = bottomPadding + 96.dp)) {
                            items(collectionDownloads, key = { "collection:${it.key}" }) { collection ->
                                val available = collection.available(downloaded)
                                Row(Modifier.fillMaxWidth().combinedClickable(
                                    onClick = { downloadedCollection = collection },
                                    onLongClick = { mediaMenu?.show(collection.browseItem()) }
                                ).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    LibraryArtwork(collection.browseItem(), Modifier.size(64.dp))
                                    Spacer(Modifier.width(16.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(collection.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                        Text("${collection.kind.replaceFirstChar { it.uppercase() }} · ${available.size}/${collection.tracks.size} downloaded",
                                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Icon(Icons.Default.DownloadDone, "Downloaded collection", tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                            items(downloadedSongs, key = { it.ytVideoId ?: it.uuid }) { song ->
                                com.example.juke.ui.components.FlatTrackRow(song.thumbnailUri, song.title, song.artist,
                                    if (song.durationSec > 0) "%d:%02d".format(song.durationSec / 60, song.durationSec % 60) else "",
                                    onClick = { musicViewModel.playDownloaded(downloadedSongs, downloadedSongs.indexOf(song)) }, track = song, showMore = true)
                            }
                        }
                    }
                    !online -> com.example.juke.ui.components.ConnectionErrorState("", {
                        com.example.juke.network.NetworkFeedback.refresh(context)
                    }, Modifier.fillMaxSize(), offline = true)
                    state.isLoading && entries.isEmpty() -> TrackListSkeleton(modifier = Modifier.fillMaxSize())
                    state.needsYouTube -> LibraryNotice("Connect YouTube Music", "Your library comes from your connected account.", "Open Settings", onOpenSettings)
                    state.error != null && entries.isEmpty() -> LibraryNotice("Couldn't load your library", state.error.orEmpty(), "Try again", libraryViewModel::refresh)
                    entries.isEmpty() -> LibraryNotice(
                        if (state.searchQuery.isNotBlank()) "No matches" else "No ${filter.label.lowercase()} yet",
                        if (state.searchQuery.isNotBlank()) "Try another search or filter." else "Saved items from your YouTube Music account appear here.")
                    grid -> LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = bottomPadding + 96.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        gridItems(entries, key = { "${it.kind}:${it.id}" }) { item ->
                            Column(Modifier.fillMaxWidth().combinedClickable(onClick = { open(item) }, onLongClick = { mediaMenu?.show(item) })) {
                                LibraryArtwork(item, Modifier.fillMaxWidth().aspectRatio(1f))
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) { LibraryLabels(item) }
                                    LibraryItemOptions(item, { rename = item }, { delete = item }, { mediaMenu?.show(item) })
                                }
                            }
                        }
                    }
                    else -> LazyColumn(
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = bottomPadding + 96.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(entries, key = { "${it.kind}:${it.id}" }) { item ->
                            Row(
                                Modifier.fillMaxWidth().combinedClickable(onClick = { open(item) }, onLongClick = { mediaMenu?.show(item) })
                                    .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically
                            ) {
                                LibraryArtwork(item, Modifier.size(64.dp))
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) { LibraryLabels(item) }
                                LibraryItemOptions(item, { rename = item }, { delete = item }, { mediaMenu?.show(item) })
                            }
                        }
                    }
                }
            }
        }
    }
    downloadedCollection?.let { collection ->
        val available = collection.available(downloaded)
        ModalBottomSheet(onDismissRequest = { downloadedCollection = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), shape = androidx.compose.ui.graphics.RectangleShape) {
            Text(collection.title, Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleLarge)
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(enabled = available.isNotEmpty(), onClick = { musicViewModel.playDownloaded(available, 0); downloadedCollection = null }) { Text("Play") }
                OutlinedButton(enabled = available.isNotEmpty(), onClick = { musicViewModel.playDownloaded(available.shuffled(), 0); downloadedCollection = null }) { Text("Shuffle") }
                TextButton(onClick = { musicViewModel.downloads.removeCollection(collection); downloadedCollection = null }) { Text("Remove") }
            }
            if (available.isEmpty()) Text("Songs are still downloading. Completed songs will appear here.", Modifier.padding(20.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp), contentPadding = PaddingValues(20.dp)) {
                items(available, key = { it.ytVideoId ?: it.uuid }) { song ->
                    com.example.juke.ui.components.FlatTrackRow(song.thumbnailUri, song.title, song.artist,
                        if (song.durationSec > 0) "%d:%02d".format(song.durationSec / 60, song.durationSec % 60) else "",
                        onClick = { musicViewModel.playDownloaded(available, available.indexOf(song)); downloadedCollection = null }, track = song, showMore = true)
                }
            }
        }
    }
    rename?.let { item ->
        var name by remember(item.id) { mutableStateOf(item.title) }
        GlassAlertDialog(onDismissRequest = { rename = null }, title = { Text("Rename playlist") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { libraryViewModel.renamePlaylist(item, name); rename = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { rename = null }) { Text("Cancel") } })
    }
    delete?.let { item ->
        GlassAlertDialog(onDismissRequest = { delete = null }, title = { Text("Delete playlist") },
            text = { Text("Delete '${item.title}' from your YouTube Music account?") },
            confirmButton = { TextButton(onClick = { libraryViewModel.deletePlaylist(item); delete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { delete = null }) { Text("Cancel") } })
    }
    if (createPlaylist) CreatePlaylistDialog(
        onDismiss = { createPlaylist = false },
        onCreate = { libraryViewModel.createPlaylist(it); createPlaylist = false }
    )
}

private fun librarySubtitle(item: BrowseItem): String {
    if (item.playlistId == "LM" || item.id == "LM") return "Auto playlist"
    if (item.kind == "artist") return "Artist"
    if (item.kind == "album") return listOf("Album", item.subtitle).filter { it.isNotBlank() }.joinToString(" · ")
    val count = item.raw.text("count", "trackCount", "track_count")
    return if (count.isNotBlank()) "$count songs" else "Playlist"
}

@Composable
private fun LibraryLabels(item: BrowseItem) {
    Text(item.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.height(3.dp))
    Text(librarySubtitle(item), style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun LibraryArtwork(item: BrowseItem, modifier: Modifier) {
    val liked = item.playlistId == "LM" || item.id == "LM"
    Box(modifier.clip(if (item.kind == "artist") CircleShape else RoundedCornerShape(6.dp))
        .background(if (liked) Brush.linearGradient(listOf(Color(0xFF9167EE), Color(0xFFFF54B2)))
            else Brush.linearGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surfaceVariant))),
        contentAlignment = Alignment.Center) {
        if (liked || item.image.isBlank()) Icon(
            if (liked) Icons.Default.ThumbUp else if (item.kind == "artist") Icons.Default.Person else Icons.Default.LibraryMusic,
            contentDescription = null, modifier = Modifier.fillMaxSize(0.48f), tint = Color.White)
        else AsyncImage(model = item.image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun LibraryNotice(title: String, message: String, action: String? = null, onAction: () -> Unit = {}) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (action != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun LibraryItemOptions(item: BrowseItem, onRename: () -> Unit, onDelete: () -> Unit, onMore: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { if (item.editable) expanded = true else onMore() }) { Icon(Icons.Default.MoreVert, "Options for ${item.title}") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("More options") }, onClick = { expanded = false; onMore() })
            DropdownMenuItem(text = { Text("Rename") }, onClick = { expanded = false; onRename() })
            DropdownMenuItem(text = { Text("Delete") }, onClick = { expanded = false; onDelete() })
        }
    }
}
