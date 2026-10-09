package `in`.synthora.musicbox.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import kotlinx.coroutines.launch
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
import androidx.compose.ui.platform.testTag
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
import `in`.synthora.musicbox.network.BrowseItem
import `in`.synthora.musicbox.network.text
import `in`.synthora.musicbox.network.metadata
import `in`.synthora.musicbox.network.BrowseParser
import `in`.synthora.musicbox.ui.components.CreatePlaylistDialog
import `in`.synthora.musicbox.ui.components.GlassAlertDialog
import `in`.synthora.musicbox.ui.components.GlassFilterChip
import `in`.synthora.musicbox.ui.components.LocalMediaMenu
import `in`.synthora.musicbox.ui.components.SearchHeader
import `in`.synthora.musicbox.ui.components.TrackListSkeleton
import `in`.synthora.musicbox.viewmodels.LibraryViewModel
import `in`.synthora.musicbox.viewmodels.MusicViewModel

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
    onOpenDownloadedCollection: (`in`.synthora.musicbox.services.DownloadedCollection) -> Unit = {},
    downloadsOpenTrigger: Int = 0,
    bottomPadding: Dp = 0.dp
) {
    val state by libraryViewModel.uiState.collectAsStateWithLifecycle()
    val downloadedCollections by musicViewModel.downloadedCollections.collectAsStateWithLifecycle()
    val selection = remember { DownloadSelection() }
    val downloaded by musicViewModel.downloadedTracks.collectAsStateWithLifecycle()
    val progress by musicViewModel.downloadProgress.collectAsStateWithLifecycle()
    val downloading by musicViewModel.downloads.activeTracks.collectAsStateWithLifecycle()
    val online by `in`.synthora.musicbox.network.NetworkFeedback.online.collectAsStateWithLifecycle()
    val downloadedSongs = remember(downloaded, downloading, downloadedCollections, state.searchQuery) {
        `in`.synthora.musicbox.services.standaloneDownloads((downloaded + downloading).distinctBy { it.ytVideoId }, downloadedCollections).filter { it.title.contains(state.searchQuery, true) || it.artist.contains(state.searchQuery, true) }
    }
    val collectionDownloads = remember(downloadedCollections, state.searchQuery) {
        downloadedCollections.filter { it.title.contains(state.searchQuery, true) || it.subtitle.contains(state.searchQuery, true) }
    }
    val context = LocalContext.current
    val mediaMenu = LocalMediaMenu.current
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val viewPrefs = remember(context) { context.getSharedPreferences("library_view", android.content.Context.MODE_PRIVATE) }
    var downloadsReady by remember(musicViewModel.downloads) { mutableStateOf(false) }
    LaunchedEffect(musicViewModel.downloads) { musicViewModel.downloads.awaitReady(); downloadsReady = true }
    var grid by rememberSaveable { mutableStateOf(viewPrefs.getBoolean("grid", false)) }
    val pager = rememberPagerState { LibraryFilter.entries.size }
    val scope = rememberCoroutineScope()
    val filter = LibraryFilter.entries[pager.currentPage]
    LaunchedEffect(online) { if (!online) pager.scrollToPage(LibraryFilter.DOWNLOADS.ordinal) }
    LaunchedEffect(pager.currentPage) { selection.clear() }
    LaunchedEffect(downloadsOpenTrigger) { if (downloadsOpenTrigger > 0) pager.scrollToPage(LibraryFilter.DOWNLOADS.ordinal) }
    var createPlaylist by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<BrowseItem?>(null) }
    var delete by remember { mutableStateOf<BrowseItem?>(null) }
    LaunchedEffect(Unit) {
        libraryViewModel.start()
    }
    val allEntries = remember(state.playlists, state.albums, state.artists, state.searchQuery) {
        val query = state.searchQuery.trim()
        (state.playlists + state.albums + state.artists).filter { it.id.isNotBlank() }.distinctBy { "${it.kind}:${it.id}" }.filter {
            (query.isEmpty() || it.title.contains(query, ignoreCase = true) || librarySubtitle(it).contains(query, ignoreCase = true))
        }
    }
    fun showOptions(item: BrowseItem) {
        mediaMenu?.show(item, if (item.editable) listOf(
            `in`.synthora.musicbox.ui.components.ExtraSongOption(Icons.Default.Edit, "Rename") { rename = item },
            `in`.synthora.musicbox.ui.components.ExtraSongOption(Icons.Default.Delete, "Delete") { delete = item }) else emptyList())
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
                IconButton(onClick = { grid = !grid; viewPrefs.edit().putBoolean("grid", grid).apply() }) {
                    Icon(if (grid) Icons.Default.ViewList else Icons.Default.GridView,
                        contentDescription = if (grid) "Switch to list view" else "Switch to grid view")
                }
            }
        },
        floatingActionButton = {
            if (filter == LibraryFilter.DOWNLOADS) Row(Modifier.padding(bottom = bottomPadding), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                SmallFloatingActionButton(onClick = { if (downloaded.isNotEmpty()) musicViewModel.playDownloaded(downloaded.shuffled(), 0) }) { Icon(Icons.Default.Shuffle, "Shuffle downloads") }
                FloatingActionButton(onClick = { if (downloaded.isNotEmpty()) musicViewModel.playDownloaded(downloaded, 0) }) { Icon(Icons.Default.PlayArrow, "Play downloads") }
            } else FloatingActionButton(
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
                    FilterChip(selected = option == filter, onClick = { selection.clear(); scope.launch { pager.animateScrollToPage(option.ordinal) } }, label = { Text(option.label) },
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
            LibraryPages(pager) { page ->
            key(page, grid, selection.active) {
            val filter = LibraryFilter.entries[page]
            val entries = remember(allEntries, filter) { allEntries.filter { filter.kind == null || it.kind == filter.kind } }
            Column(Modifier.fillMaxSize()) {
            if (filter == LibraryFilter.DOWNLOADS) DownloadSelectionBar(selection, downloadedSongs, musicViewModel.downloads::removeAll)
            if (filter != LibraryFilter.DOWNLOADS && online && state.error != null && entries.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(state.error.orEmpty(), Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = libraryViewModel::refresh) { Text("Retry") }
            }
            PullToRefreshBox(isRefreshing = filter != LibraryFilter.DOWNLOADS && online && state.isLoading && entries.isNotEmpty(), onRefresh = { if (online && filter != LibraryFilter.DOWNLOADS) libraryViewModel.refresh() },
                modifier = Modifier.fillMaxSize()) {
                when {
                    filter == LibraryFilter.DOWNLOADS -> {
                        if (!downloadsReady) {
                            if (grid) `in`.synthora.musicbox.ui.components.MediaGridSkeleton(
                                contentPadding = PaddingValues(bottom = bottomPadding + 96.dp))
                            else TrackListSkeleton(contentPadding = PaddingValues(bottom = bottomPadding + 96.dp))
                        } else if (downloadedSongs.isEmpty() && collectionDownloads.isEmpty()) LibraryNotice("No downloads", "Download songs, albums or playlists from their options to listen without internet.")
                        else if (grid && !selection.active) LazyVerticalGrid(GridCells.Adaptive(132.dp),
                            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = bottomPadding + 96.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            gridItems(collectionDownloads, key = { "collection:${it.key}" }) { collection ->
                                LibraryEntryCard(collection.browseItem(), { onOpenDownloadedCollection(collection) },
                                    { mediaMenu?.show(collection.browseItem()) })
                            }
                            gridItems(downloadedSongs, key = { it.ytVideoId ?: it.uuid }) { song ->
                                val play = {
                                    if (song.ytVideoId in progress) `in`.synthora.musicbox.network.NetworkFeedback.notify("This song is still downloading")
                                    else {
                                        val ready = downloadedSongs.filter { it.ytVideoId !in progress }
                                        musicViewModel.playDownloaded(ready, ready.indexOf(song))
                                    }
                                }
                                LibraryEntryCard(BrowseParser.item(song.metadata()), play,
                                    { mediaMenu?.show(song, `in`.synthora.musicbox.ui.components.QueueSongActions(play = play, select = { selection.select(song) })) })
                            }
                        }
                        else LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = bottomPadding + 96.dp)) {
                            items(collectionDownloads, key = { "collection:${it.key}" }) { collection ->
                                val available = collection.available(downloaded)
                                `in`.synthora.musicbox.ui.components.FlatTrackRow(collection.image, collection.title,
                                    "${collection.kind.replaceFirstChar { it.uppercase() }} · ${available.size}/${collection.tracks.size} downloaded", "",
                                    onClick = { onOpenDownloadedCollection(collection) }, collection = collection.browseItem(),
                                    sharpArtwork = true, showMore = true,
                                    onOptions = { mediaMenu?.show(collection.browseItem()) },
                                    downloadStatus = {
                                        if (collection.tracks.any { it.ytVideoId in progress }) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                        else Icon(if (available.size == collection.tracks.size) Icons.Default.DownloadDone else Icons.Default.Download,
                                            "Downloaded collection", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                    })
                            }
                            items(downloadedSongs, key = { it.ytVideoId ?: it.uuid }) { song ->
                                DownloadTrackRow(song, selection, onPlay = {
                                    if (song.ytVideoId in progress) `in`.synthora.musicbox.network.NetworkFeedback.notify("This song is still downloading")
                                    else {
                                        val ready = downloadedSongs.filter { it.ytVideoId !in progress }
                                        musicViewModel.playDownloaded(ready, ready.indexOf(song))
                                    }
                                })
                            }
                        }
                    }
                    !online -> `in`.synthora.musicbox.ui.components.ConnectionErrorState("", {
                        `in`.synthora.musicbox.network.NetworkFeedback.refresh(context)
                    }, Modifier.fillMaxSize(), offline = true)
                    state.isLoading && entries.isEmpty() -> if (grid) `in`.synthora.musicbox.ui.components.MediaGridSkeleton(
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = bottomPadding + 96.dp))
                    else TrackListSkeleton(contentPadding = PaddingValues(bottom = bottomPadding + 96.dp))
                    state.needsYouTube -> LibraryNotice("Connect YouTube Music", "Your library comes from your connected account.", "Open Settings", onOpenSettings)
                    state.error != null && entries.isEmpty() -> `in`.synthora.musicbox.ui.components.ConnectionErrorState(state.error.orEmpty(), libraryViewModel::refresh, Modifier.fillMaxSize())
                    entries.isEmpty() -> LibraryNotice(
                        if (state.searchQuery.isNotBlank()) "No matches" else "No ${filter.label.lowercase()} yet",
                        if (state.searchQuery.isNotBlank()) "Try another search or filter." else "Saved items from your YouTube Music account appear here.")
                    grid -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(132.dp),
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = bottomPadding + 96.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        gridItems(entries, key = { "${it.kind}:${it.id}" }) { item ->
                            LibraryEntryCard(item, { open(item) }, { showOptions(item) })
                        }
                    }
                    else -> LazyColumn(
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = bottomPadding + 96.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(entries, key = { "${it.kind}:${it.id}" }, contentType = { "library-row" }) { item ->
                            Row(
                                Modifier.fillMaxWidth().combinedClickable(onClick = { open(item) }, onLongClick = { showOptions(item) })
                                    .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically
                            ) {
                                LibraryArtwork(item, Modifier.size(64.dp))
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) { LibraryLabels(item) }
                                LibraryItemOptions(item, { rename = item }, { delete = item }, { showOptions(item) })
                            }
                        }
                    }
                }
            }
            }
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
    if (item.kind == "track") return item.subtitle
    if (item.kind == "album") return listOf("Album", item.subtitle).filter { it.isNotBlank() }.joinToString(" · ")
    val count = item.raw.text("count", "trackCount", "track_count")
    return if (count.isNotBlank()) "$count songs" else "Playlist"
}

@Composable
private fun LibraryEntryCard(item: BrowseItem, onOpen: () -> Unit, onOptions: () -> Unit) {
    Column(Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = onOptions)) {
        Box {
            LibraryArtwork(item, Modifier.fillMaxWidth().aspectRatio(1f))
            if (item.raw["offline"]?.toString() == "true") Icon(Icons.Default.DownloadDone, "Downloaded collection",
                Modifier.align(Alignment.BottomEnd).padding(8.dp).size(20.dp), tint = MaterialTheme.colorScheme.primary)
            else `in`.synthora.musicbox.ui.components.DownloadedBadge(item.videoId, Modifier.align(Alignment.BottomEnd).padding(8.dp))
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { LibraryLabels(item) }
            IconButton(onClick = onOptions) { Icon(Icons.Default.MoreVert, "Options for ${item.title}") }
        }
    }
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
        else BoxWithConstraints(Modifier.fillMaxSize()) {
            `in`.synthora.musicbox.ui.components.ListArtwork(item.image, maxWidth, Modifier.fillMaxSize())
        }
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
    IconButton(onClick = onMore) { Icon(Icons.Default.MoreVert, "Options for ${item.title}") }
}

/** Content follows the finger: swipe left advances, swipe right returns to the previous filter. */
@Composable
fun LibraryPages(pager: PagerState, content: @Composable (Int) -> Unit) {
    HorizontalPager(state = pager, reverseLayout = false, modifier = Modifier.fillMaxSize().testTag("Library pages")) { content(it) }
}
