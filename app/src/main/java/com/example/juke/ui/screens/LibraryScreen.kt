package com.example.juke.ui.screens

import com.example.juke.ui.components.GlassButton
import com.example.juke.ui.components.SearchHeader
import com.example.juke.ui.components.GlassFilterChip
import com.example.juke.ui.components.GlassModalBottomSheet
import com.example.juke.ui.components.GlassAlertDialog
import com.example.juke.ui.theme.GlassCard
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.juke.models.Track
import com.example.juke.network.BrowseItem
import com.example.juke.ui.components.AddToPlaylistDialog
import com.example.juke.ui.components.CreatePlaylistDialog
import com.example.juke.ui.components.LibraryTrackItem
import com.example.juke.ui.components.SwipeToAddNextContainer
import com.example.juke.ui.components.TrackListSkeleton
import com.example.juke.utils.rememberJukeHaptics
import com.example.juke.viewmodels.LibraryView
import com.example.juke.viewmodels.LibraryViewModel
import com.example.juke.viewmodels.MusicViewModel
import com.example.juke.viewmodels.SortOption
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    musicViewModel: MusicViewModel,
    libraryViewModel: LibraryViewModel = viewModel(),
    onOpenArtist: (BrowseItem) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    bottomPadding: Dp = 0.dp
) {
    val coroutineScope = rememberCoroutineScope()
    val uiState by libraryViewModel.uiState.collectAsStateWithLifecycle()
    val haptic = rememberJukeHaptics()
    val context = LocalContext.current
    var searchOpen by rememberSaveable { mutableStateOf(false) }

    fun performHapticFeedback() {
        haptic.heavyClick()
    }

    LaunchedEffect(Unit) {
        libraryViewModel.start()
        libraryViewModel.messages.collect { message ->
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // Handle back press to exit selection mode
    androidx.activity.compose.BackHandler(enabled = uiState.isSelectionMode) {
        libraryViewModel.toggleSelectionMode(false)
    }

    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var playlistToRename by remember { mutableStateOf<BrowseItem?>(null) }
    var tracksForPlaylistDialog by remember { mutableStateOf<List<Track>?>(null) }
    var editablePlaylists by remember { mutableStateOf<List<BrowseItem>?>(null) }
    var isLibraryShufflePrepared by rememberSaveable { mutableStateOf(false) }

    // Playlists that songs can be added to, loaded when the dialog opens
    LaunchedEffect(tracksForPlaylistDialog) {
        if (tracksForPlaylistDialog != null) {
            editablePlaylists = null
            editablePlaylists = runCatching { libraryViewModel.editablePlaylists() }.getOrDefault(emptyList())
        }
    }

    val playableTracks = remember(uiState.tracks, isLibraryShufflePrepared) {
        if (isLibraryShufflePrepared) uiState.tracks.shuffled() else uiState.tracks
    }
    val showsTracks = uiState.view != LibraryView.ARTISTS

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        topBar = {
            if (!uiState.isSelectionMode) {
                SearchHeader(
                    title = "Your Library",
                    query = uiState.searchQuery,
                    onQueryChange = { libraryViewModel.updateSearchQuery(it) },
                    open = searchOpen || uiState.searchQuery.isNotEmpty(),
                    onOpenChange = { searchOpen = it },
                    placeholder = "Search your library"
                ) {
                    IconButton(
                        onClick = {
                            haptic.click()
                            libraryViewModel.toggleSortSheet()
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (uiState.isSelectionMode) {
                // Selection Top Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { libraryViewModel.toggleSelectionMode(false) }) {
                        Icon(Icons.Default.Clear, contentDescription = "Close selection")
                    }

                    Text(
                        text = "${uiState.selectedTrackUuids.size}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(0.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        IconButton(onClick = {
                            musicViewModel.addNext(uiState.tracks.filter { it.uuid in uiState.selectedTrackUuids })
                            libraryViewModel.clearSelection()
                            performHapticFeedback()
                        }) {
                            Icon(
                                Icons.AutoMirrored.Filled.QueueMusic,
                                contentDescription = "Play Next",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        IconButton(onClick = {
                            musicViewModel.addToQueue(uiState.tracks.filter { it.uuid in uiState.selectedTrackUuids })
                            libraryViewModel.clearSelection()
                            performHapticFeedback()
                        }) {
                            Icon(
                                Icons.AutoMirrored.Filled.QueueMusic,
                                contentDescription = "Add to Queue"
                            )
                        }

                        IconButton(onClick = {
                            val selectedTracks = uiState.tracks.filter { it.uuid in uiState.selectedTrackUuids }
                            if (selectedTracks.isNotEmpty()) {
                                tracksForPlaylistDialog = selectedTracks
                                libraryViewModel.clearSelection()
                            }
                        }) {
                            Icon(Icons.Default.AddCircle, contentDescription = "Add to Playlist")
                        }
                    }

                    var showMenu by remember { mutableStateOf(false) }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }

                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (uiState.selectedTrackUuids.size == uiState.tracks.size && uiState.tracks.isNotEmpty()) "Deselect All" else "Select All") },
                            onClick = {
                                showMenu = false
                                if (uiState.selectedTrackUuids.size == uiState.tracks.size && uiState.tracks.isNotEmpty()) {
                                    libraryViewModel.clearSelection()
                                } else {
                                    libraryViewModel.selectAll()
                                }
                            }
                        )
                    }
                }
            }

            // Filter Row: liked songs, artists, history, then the account's playlists
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                item {
                    GlassFilterChip(
                        selected = uiState.view == LibraryView.LIKED,
                        onClick = { libraryViewModel.showLiked() },
                        label = { Text("Liked songs") },
                        leadingIcon = {
                            Icon(
                                if (uiState.view == LibraryView.LIKED) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                }
                item {
                    GlassFilterChip(
                        selected = uiState.view == LibraryView.ARTISTS,
                        onClick = { libraryViewModel.showArtists() },
                        label = { Text("Artists") },
                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                }
                item {
                    GlassFilterChip(
                        selected = uiState.view == LibraryView.HISTORY,
                        onClick = { libraryViewModel.showHistory() },
                        label = { Text("History") },
                        leadingIcon = { Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                }

                items(uiState.playlists, key = { it.id }) { playlist ->
                    var showMenu by remember { mutableStateOf(false) }
                    var showDeleteDialog by remember { mutableStateOf(false) }

                    GlassFilterChip(
                        selected = uiState.selectedPlaylist?.id == playlist.id,
                        onClick = { libraryViewModel.showPlaylist(playlist) },
                        label = { Text(playlist.title) },
                        leadingIcon = if (uiState.selectedPlaylist?.id == playlist.id) {
                            {
                                Icon(
                                    Icons.AutoMirrored.Filled.QueueMusic,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        } else null,
                        trailingIcon = {
                            Box {
                                IconButton(
                                    onClick = { showMenu = true },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Default.MoreVert,
                                        contentDescription = "Options",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                DropdownMenu(
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Play") },
                                        onClick = {
                                            showMenu = false
                                            musicViewModel.playPlaylist(playlist.playlistId.ifBlank { playlist.id }, emptyList())
                                        },
                                        leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) }
                                    )
                                    if (playlist.editable) {
                                        DropdownMenuItem(
                                            text = { Text("Rename") },
                                            onClick = {
                                                showMenu = false
                                                playlistToRename = playlist
                                            },
                                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Delete") },
                                            onClick = {
                                                showMenu = false
                                                showDeleteDialog = true
                                            },
                                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) }
                                        )
                                    }
                                }
                            }
                        }
                    )

                    if (showDeleteDialog) {
                        GlassAlertDialog(
                            onDismissRequest = { showDeleteDialog = false },
                            title = { Text("Delete Playlist") },
                            text = { Text("Delete '${playlist.title}' from your YouTube Music account?") },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        libraryViewModel.deletePlaylist(playlist)
                                        showDeleteDialog = false
                                    }
                                ) {
                                    Text("Delete", color = MaterialTheme.colorScheme.error)
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showDeleteDialog = false }) {
                                    Text("Cancel")
                                }
                            }
                        )
                    }
                }

                item {
                    GlassFilterChip(
                        selected = false,
                        onClick = { showCreatePlaylistDialog = true },
                        label = { Text("New") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                }
            }

            // Header with count and controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.Center, modifier = Modifier.weight(1f)) {
                    val title = when (uiState.view) {
                        LibraryView.LIKED -> "Liked songs"
                        LibraryView.ARTISTS -> "Artists"
                        LibraryView.HISTORY -> "History"
                        LibraryView.PLAYLIST -> uiState.selectedPlaylist?.title.orEmpty()
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (showsTracks) "${uiState.trackCount.coerceAtLeast(uiState.tracks.size)} songs"
                        else "${uiState.artists.size} artists",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (showsTracks) {
                    LibraryPlaybackActions(
                        musicViewModel = musicViewModel,
                        tracks = playableTracks,
                        isShufflePrepared = isLibraryShufflePrepared,
                        onToggleShuffle = { isLibraryShufflePrepared = !isLibraryShufflePrepared }
                    )
                }
            }

            PullToRefreshBox(
                isRefreshing = false,
                onRefresh = { libraryViewModel.refresh() },
                modifier = Modifier.fillMaxSize()
            ) {
                when {
                    uiState.isLoading -> TrackListSkeleton(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp + bottomPadding)
                    )
                    uiState.needsYouTube -> LibraryMessage(
                        icon = Icons.Outlined.LibraryMusic,
                        title = "Connect YouTube Music",
                        text = "Your library comes from the YouTube Music account on your server.",
                        action = "Open Settings",
                        onAction = onOpenSettings
                    )
                    uiState.error != null && uiState.tracks.isEmpty() && uiState.artists.isEmpty() -> LibraryMessage(
                        icon = Icons.Outlined.LibraryMusic,
                        title = "Couldn't load your library",
                        text = uiState.error.orEmpty(),
                        action = "Try again",
                        onAction = { libraryViewModel.refresh() }
                    )
                    !showsTracks -> ArtistList(
                        artists = uiState.artists,
                        onOpenArtist = onOpenArtist,
                        bottomPadding = bottomPadding
                    )
                    uiState.tracks.isEmpty() -> LibraryMessage(
                        icon = if (uiState.searchQuery.isNotEmpty()) Icons.Outlined.SearchOff else Icons.Outlined.FavoriteBorder,
                        title = when {
                            uiState.searchQuery.isNotEmpty() -> "No tracks found"
                            uiState.view == LibraryView.LIKED -> "No liked songs yet"
                            uiState.view == LibraryView.HISTORY -> "Nothing played yet"
                            else -> "This playlist is empty"
                        },
                        text = if (uiState.searchQuery.isNotEmpty()) "Try a different search term"
                        else "Songs you like on YouTube Music show up here"
                    )
                    else -> LazyColumn(
                        contentPadding = PaddingValues(
                            start = 20.dp,
                            top = 8.dp,
                            end = 20.dp,
                            bottom = 100.dp + bottomPadding
                        ),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        itemsIndexed(
                            items = uiState.tracks,
                            key = { index, track -> "${track.uuid}_$index" }
                        ) { index, track ->
                            // Next page when the end of the loaded songs comes into view.
                            if (index >= uiState.tracks.size - 5 && uiState.hasMore) {
                                LaunchedEffect(uiState.tracks.size) { libraryViewModel.loadMore() }
                            }
                            SwipeToAddNextContainer(
                                onAddNext = { musicViewModel.addNext(track) }
                            ) {
                                LibraryTrackItem(
                                    track = track,
                                    isSelectionMode = uiState.isSelectionMode,
                                    isSelected = uiState.selectedTrackUuids.contains(track.uuid),
                                    onPlay = {
                                        if (uiState.isSelectionMode) {
                                            performHapticFeedback()
                                            libraryViewModel.toggleTrackSelection(track.uuid)
                                        } else {
                                            musicViewModel.setQueue(uiState.tracks, index)
                                        }
                                    },
                                    onLongClick = {
                                        performHapticFeedback()
                                        libraryViewModel.toggleTrackSelection(track.uuid)
                                    },
                                    onToggleFavorite = { libraryViewModel.toggleFavorite(track) },
                                    trailingIcon = Icons.Default.AddCircle,
                                    onTrailingIconClick = { tracksForPlaylistDialog = listOf(track) }
                                )
                            }
                        }
                        if (uiState.isLoadingMore) {
                            item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp)) }
                        }
                    }
                }
            }
        }
    }

    if (showCreatePlaylistDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreatePlaylistDialog = false },
            onCreate = { name ->
                libraryViewModel.createPlaylist(name, thenAdd = tracksForPlaylistDialog.orEmpty())
                tracksForPlaylistDialog = null
                showCreatePlaylistDialog = false
            }
        )
    }

    playlistToRename?.let { playlist ->
        var newName by remember(playlist.id) { mutableStateOf(playlist.title) }
        GlassAlertDialog(
            onDismissRequest = { playlistToRename = null },
            title = { Text("Rename Playlist") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newName.isNotBlank()) {
                            libraryViewModel.renamePlaylist(playlist, newName)
                            playlistToRename = null
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { playlistToRename = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    tracksForPlaylistDialog?.takeIf { !showCreatePlaylistDialog }?.let { tracks ->
        AddToPlaylistDialog(
            playlists = editablePlaylists,
            tracks = tracks,
            onDismiss = { tracksForPlaylistDialog = null },
            onAddToPlaylist = { playlist ->
                coroutineScope.launch {
                    libraryViewModel.addTracksToPlaylist(playlist, tracks)
                    tracksForPlaylistDialog = null
                }
            },
            onCreatePlaylist = { showCreatePlaylistDialog = true }
        )
    }

    // Sort Bottom Sheet
    if (uiState.showSortSheet) {
        SortBottomSheet(
            currentSortStyle = uiState.sortOption,
            onSortSelected = { libraryViewModel.updateSortOption(it) },
            onDismissRequest = { libraryViewModel.toggleSortSheet() }
        )
    }
}

@Composable
private fun ArtistList(
    artists: List<BrowseItem>,
    onOpenArtist: (BrowseItem) -> Unit,
    bottomPadding: Dp
) {
    if (artists.isEmpty()) {
        LibraryMessage(
            icon = Icons.Default.Person,
            title = "No artists yet",
            text = "Artists you subscribe to on YouTube Music show up here"
        )
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 100.dp + bottomPadding)
    ) {
        items(artists, key = { it.id }) { artist ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenArtist(artist) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = artist.image,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp).clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    artist.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(start = 72.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            )
        }
    }
}

@Composable
private fun LibraryMessage(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    text: String,
    action: String? = null,
    onAction: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(32.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = title,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = text,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (action != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    GlassButton(onClick = onAction) { Text(action) }
                }
            }
        }
    }
}

@Composable
private fun LibraryPlaybackActions(
    musicViewModel: MusicViewModel,
    tracks: List<Track>,
    isShufflePrepared: Boolean,
    onToggleShuffle: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onToggleShuffle
        ) {
            Icon(
                imageVector = Icons.Default.Shuffle,
                contentDescription = "Shuffle",
                tint = if (isShufflePrepared) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }

        IconButton(
            onClick = {
                if (tracks.isNotEmpty()) {
                    musicViewModel.addToQueue(tracks)
                }
            },
            enabled = tracks.isNotEmpty()
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                contentDescription = "Add all to Queue",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        GlassButton(
            onClick = {
                if (tracks.isNotEmpty()) {
                    musicViewModel.setQueue(tracks, startIndex = 0)
                }
            },
            enabled = tracks.isNotEmpty()
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Play all",
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("Play")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortBottomSheet(
    currentSortStyle: SortOption,
    onSortSelected: (SortOption) -> Unit,
    onDismissRequest: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    GlassModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "Sort by",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                color = MaterialTheme.colorScheme.onSurface
            )

            val sortOptions = listOf(
                SortOption.DEFAULT to "Default order",
                SortOption.TITLE to "Title",
                SortOption.ARTIST to "Artist"
            )

            sortOptions.forEach { (option, label) ->
                val isSelected = currentSortStyle == option

                ListItem(
                    headlineContent = {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                            ),
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    trailingContent = {
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSortSelected(option) }
                )
            }
        }
    }
}
