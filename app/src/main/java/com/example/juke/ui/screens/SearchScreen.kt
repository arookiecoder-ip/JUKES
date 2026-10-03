package com.example.juke.ui.screens

import com.example.juke.ui.theme.glassPane
import com.example.juke.ui.theme.GlassShapes
import com.example.juke.ui.theme.GlassLevel
import com.example.juke.ui.components.GlassFilterChip
import com.example.juke.ui.theme.GlassCard
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.juke.models.SpotifyAlbum
import com.example.juke.models.SpotifyArtist
import com.example.juke.models.SpotifyPlaylist
import com.example.juke.models.Track
import com.example.juke.network.SpotifyApi
import com.example.juke.ui.components.AlbumCard
import com.example.juke.ui.components.ArtistCard
import com.example.juke.ui.components.PlaylistCard
import com.example.juke.ui.components.SearchResultItemM3
import com.example.juke.ui.components.SwipeToAddNextContainer
import com.example.juke.ui.components.GlassButton
import com.example.juke.ui.components.SearchHeader
import com.example.juke.ui.components.TrackListSkeleton
import com.example.juke.utils.rememberJukeHaptics
import com.example.juke.viewmodels.MusicViewModel
import com.example.juke.viewmodels.SearchViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    musicViewModel: MusicViewModel,
    searchViewModel: SearchViewModel = viewModel(),
    searchResetTrigger: Int = 0,
    searchFocusTrigger: Int = 0,
    onNavigateToArtist: (SpotifyArtist) -> Unit = {},
    onNavigateToPlaylist: (SpotifyPlaylist) -> Unit = {},
    onNavigateToAlbum: (SpotifyAlbum) -> Unit = {},
    bottomPadding: Dp = 0.dp
) {
    val uiState by searchViewModel.uiState.collectAsStateWithLifecycle()
    val isStreamMode by musicViewModel.isStreamMode.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val haptic = rememberJukeHaptics()

    var previousTrigger by remember { mutableIntStateOf(searchResetTrigger) }
    var previousFocusTrigger by remember { mutableIntStateOf(searchFocusTrigger) }
    var selectedFilter by rememberSaveable { mutableStateOf("All") }
    var active by rememberSaveable { mutableStateOf(false) }
    val filters = listOf("All", "Tracks", "Artists", "Playlists", "Albums")

    // Warm the YT suggestions connection once when search screen is opened.
    LaunchedEffect(Unit) {
        searchViewModel.warmSuggestionsConnection()
    }

    // 2nd tap: reset query, open bar, show keyboard
    LaunchedEffect(searchResetTrigger) {
        if (searchResetTrigger != previousTrigger && searchResetTrigger > 0) {
            previousTrigger = searchResetTrigger
            searchViewModel.updateQuery("")
            active = true
        }
    }

    // 3rd tap: keep current state, just show keyboard
    LaunchedEffect(searchFocusTrigger) {
        if (searchFocusTrigger != previousFocusTrigger && searchFocusTrigger > 0) {
            previousFocusTrigger = searchFocusTrigger
            active = true
        }
    }

    Scaffold(containerColor = Color.Transparent) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(bottom = paddingValues.calculateBottomPadding())
        ) {
            SearchHeader(
                title = "Search",
                query = uiState.query,
                onQueryChange = { searchViewModel.updateQuery(it) },
                open = active,
                onOpenChange = { active = it },
                placeholder = "Songs, artists, albums",
                onSearch = {
                    if (uiState.query.isNotBlank() && !uiState.isSearching) {
                        keyboardController?.hide()
                        searchViewModel.search(uiState.query)
                    }
                }
            )

            Column(modifier = Modifier.weight(1f).fillMaxWidth()) {

                    // ── Suggestions view (shown while the user is typing) ────────────
                    if (uiState.isShowingSuggestions && uiState.query.isNotBlank()) {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(uiState.suggestions) { suggestion ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            haptic.click()
                                            keyboardController?.hide()
                                            searchViewModel.search(suggestion)
                                        }
                                        .padding(horizontal = 20.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Search,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Text(
                                        text = suggestion,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                HorizontalDivider(
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                                )
                            }
                        }
                    }
                    // ── Full results view (shown after the user submits a search) ────
                    else {
                        AnimatedVisibility(
                            visible = uiState.query.isNotEmpty(),
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            LazyRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(filters) { filter ->
                                    GlassFilterChip(
                                        selected = selectedFilter == filter,
                                        onClick = { selectedFilter = filter },
                                        label = {
                                            Text(
                                                filter,
                                                style = MaterialTheme.typography.labelLarge
                                            )
                                        })
                                }
                            }
                        }

                        Box(modifier = Modifier.weight(1f)) {
                            if (hasResults(uiState) && !uiState.isPlaylistUrl) {
                                SearchResultsList(
                                    uiState = uiState,
                                    selectedFilter = selectedFilter,
                                    musicViewModel = musicViewModel,
                                    searchViewModel = searchViewModel,
                                    scope = scope,
                                    isStreamMode = isStreamMode,
                                    onNavigateToArtist = onNavigateToArtist,
                                    onNavigateToPlaylist = onNavigateToPlaylist,
                                    onNavigateToAlbum = onNavigateToAlbum,
                                    bottomPadding = bottomPadding,
                                    keyboardController = keyboardController
                                )
                            } else if (uiState.isPlaylistUrl && uiState.playlists.isNotEmpty() && !uiState.isImportingPlaylist) {
                                val playlist = uiState.playlists.first()
                                ImportPlaylistCard(
                                    playlist = playlist,
                                    onImport = {
                                        scope.launch {
                                            searchViewModel.importPlaylist(uiState.playlistId!!) { track ->
                                                musicViewModel.downloadSong(
                                                    SpotifyApi.spotifyTrackToSong(
                                                        track
                                                    )
                                                )
                                            }
                                        }
                                    }
                                )
                            } else if (uiState.isImportingPlaylist && uiState.query.isBlank()) {
                                ImportProgressCard(
                                    progress = uiState.importProgress,
                                    total = uiState.importTotal
                                )
                            } else if (!hasResults(uiState) && !uiState.isImportingPlaylist) {
                                if (uiState.query.isBlank() && uiState.recentSearches.isNotEmpty()) {
                                    RecentSearches(
                                        searches = uiState.recentSearches,
                                        onSearchClick = {
                                            haptic.click()
                                            keyboardController?.hide()
                                            searchViewModel.search(it)
                                        },
                                        onRemoveClick = { searchViewModel.removeRecentSearch(it) },
                                        onClearAll = {
                                            uiState.recentSearches.forEach {
                                                searchViewModel.removeRecentSearch(it)
                                            }
                                        },
                                        bottomPadding = bottomPadding
                                    )
                                } else {
                                    EmptySearchState(
                                        isQueryEmpty = uiState.query.isBlank(),
                                        isSearching = uiState.isSearching,
                                        bottomPadding = bottomPadding
                                    )
                                }
                            }

                            if (uiState.error != null) {
                                GlassCard(
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .padding(12.dp)
                                        .fillMaxWidth(),
        accent = MaterialTheme.colorScheme.errorContainer) {
                                    Row(
                                        modifier = Modifier.padding(
                                            horizontal = 16.dp,
                                            vertical = 12.dp
                                        ),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Warning,
                                            null,
                                            tint = MaterialTheme.colorScheme.onErrorContainer,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Text(
                                            uiState.error!!,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            }

                            if (uiState.isSearching && hasResults(uiState)) {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .align(Alignment.TopCenter),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = Color.Transparent
                                )
                            }
                        }
                    }
            }
        }
    }
}

private fun hasResults(uiState: com.example.juke.viewmodels.SearchUiState): Boolean {
    return uiState.tracks.isNotEmpty() ||
            uiState.localTracks.isNotEmpty() ||
            uiState.artists.isNotEmpty() ||
            uiState.playlists.isNotEmpty() ||
            uiState.albums.isNotEmpty()
}

@Composable
private fun ImportPlaylistCard(
    playlist: SpotifyPlaylist,
    onImport: () -> Unit
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AsyncImage(
                model = playlist.images.firstOrNull()?.url ?: "",
                contentDescription = null,
                modifier = Modifier
                    .size(132.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${playlist.tracks?.total ?: 0} tracks ready to download",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
            GlassButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Import playlist", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ImportProgressCard(progress: Int, total: Int) {
    val fraction = if (total > 0) progress.toFloat() / total else 0f
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp)
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "${(fraction * 100).toInt()}%",
                    style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "$progress / $total tracks",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            Text(
                "Importing playlist…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                gapSize = 0.dp,
                drawStopIndicator = {}
            )
        }
    }
}

@Composable
private fun SearchResultsList(
    uiState: com.example.juke.viewmodels.SearchUiState,
    selectedFilter: String,
    musicViewModel: MusicViewModel,
    searchViewModel: SearchViewModel,
    scope: kotlinx.coroutines.CoroutineScope,
    isStreamMode: Boolean,
    onNavigateToArtist: (SpotifyArtist) -> Unit,
    onNavigateToPlaylist: (SpotifyPlaylist) -> Unit,
    onNavigateToAlbum: (SpotifyAlbum) -> Unit,
    bottomPadding: Dp,
    keyboardController: androidx.compose.ui.platform.SoftwareKeyboardController? = null
) {
    val hideKeyboardOnScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -5f) keyboardController?.hide()
                return Offset.Zero
            }
        }
    }
    LazyColumn(
        contentPadding = PaddingValues(bottom = bottomPadding + 24.dp),
        modifier = Modifier.nestedScroll(hideKeyboardOnScrollConnection)
    ) {
        // ── In Your Library ──────────────────────────────────────────────
        if ((selectedFilter == "All" || selectedFilter == "Tracks") && uiState.localTracks.isNotEmpty()) {
            item { SectionHeader("In Your Library") }
            items(uiState.localTracks.distinctBy { it.uuid }, key = { it.uuid }) { track ->
                SwipeToAddNextContainer(
                    onAddNext = {
                        musicViewModel.addNext(track)
                    }
                ) {
                    LocalTrackItem(
                        track = track,
                        onClick = { musicViewModel.setQueue(listOf(track), 0) },
                        showAccentBar = false
                    )
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }

        // ── Songs ────────────────────────────────────────────────────────
        if ((selectedFilter == "All" || selectedFilter == "Tracks") && uiState.tracks.isNotEmpty()) {
            item { SectionHeader("Songs") }
            items(
                uiState.tracks.distinctBy { it.id ?: it.uri },
                key = { it.id ?: it.uri }) { track ->
                SwipeToAddNextContainer(
                    onAddNext = {
                        scope.launch {
                            searchViewModel.setDownloading(track.id)
                            try {
                                musicViewModel.queueSpotifyTrackNext(
                                    spotifyTrack = track,
                                    useStreamMode = isStreamMode
                                )
                            } finally {
                                searchViewModel.setDownloading(null)
                            }
                        }
                    }
                ) {
                    SearchResultItemM3(
                        track = track,
                        isDownloading = uiState.downloadingId == track.id,
                        onClick = {
                            scope.launch {
                                searchViewModel.setDownloading(track.id)
                                musicViewModel.downloadAndPlay(SpotifyApi.spotifyTrackToSong(track))
                                kotlinx.coroutines.delay(2000)
                                searchViewModel.setDownloading(null)
                            }
                        }
                    )
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }

        // ── Artists ──────────────────────────────────────────────────────
        if ((selectedFilter == "All" || selectedFilter == "Artists") && uiState.artists.isNotEmpty()) {
            item { SectionHeader("Artists") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(
                        uiState.artists.distinctBy { it.id ?: it.uri ?: it.name },
                        key = { it.id ?: it.uri ?: it.name }) { artist ->
                        ArtistCard(artist = artist, onClick = { onNavigateToArtist(artist) })
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }

        // ── Playlists ────────────────────────────────────────────────────
        if ((selectedFilter == "All" || selectedFilter == "Playlists") && uiState.playlists.isNotEmpty()) {
            item { SectionHeader("Playlists") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(uiState.playlists.distinctBy { it.id }, key = { it.id }) { playlist ->
                        PlaylistCard(
                            playlist = playlist,
                            onClick = { onNavigateToPlaylist(playlist) }
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }

        // ── Albums ───────────────────────────────────────────────────────
        if ((selectedFilter == "All" || selectedFilter == "Albums") && uiState.albums.isNotEmpty()) {
            item { SectionHeader("Albums") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(
                        uiState.albums.distinctBy { it.id ?: it.uri ?: it.name },
                        key = { it.id ?: it.uri ?: it.name }) { album ->
                        AlbumCard(album = album, onClick = { onNavigateToAlbum(album) })
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

// ── Section header ──────────────────────────────────────────────────────────
@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge.copy(
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.2).sp
        ),
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp)
    )
}

// ── Library track row (flat + left accent) ───────────────────────────────────
@Composable
private fun LocalTrackItem(
    track: Track,
    onClick: () -> Unit,
    showAccentBar: Boolean = true
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Primary-colored left accent bar
            if (showAccentBar) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(64.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                )
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = track.thumbnailUri ?: "",
                    contentDescription = null,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "%d:%02d".format(track.durationSec / 60, track.durationSec % 60),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 82.dp, end = 20.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        )
    }
}

// ── Empty / idle state ───────────────────────────────────────────────────────
@Composable
private fun EmptySearchState(
    isQueryEmpty: Boolean,
    isSearching: Boolean,
    bottomPadding: Dp
) {
    if (isSearching && !isQueryEmpty) {
        TrackListSkeleton(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 16.dp, bottom = 100.dp + bottomPadding)
        )
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = bottomPadding),
        contentAlignment = BiasAlignment(0f, -0.25f)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 40.dp)
        ) {
            val icon = if (isQueryEmpty) Icons.Default.MusicNote else Icons.Outlined.SearchOff
            val title = if (isQueryEmpty) "Find your next song" else "No results found"
            val subtitle = if (isQueryEmpty) "Search for songs, artists, playlists or albums"
            else "Try a different spelling or keyword"

            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = if (isQueryEmpty) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ── Recent searches ──────────────────────────────────────────────────────────
@Composable
private fun RecentSearches(
    searches: List<String>,
    onSearchClick: (String) -> Unit,
    onRemoveClick: (String) -> Unit,
    onClearAll: () -> Unit,
    bottomPadding: Dp
) {
    LazyColumn(
        contentPadding = PaddingValues(
            start = 20.dp, top = 4.dp, end = 12.dp, bottom = bottomPadding + 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recent",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onClearAll) {
                    Text(
                        "Clear all",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        items(searches) { query ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSearchClick(query) }
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = query,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { onRemoveClick(query) },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Remove",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            )
        }
    }
}

private fun formatDuration(durationMs: Int): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
