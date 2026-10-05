package com.example.juke.ui.screens

import com.example.juke.ui.components.LocalMediaMenu

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
import androidx.compose.foundation.combinedClickable
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
import com.example.juke.models.Track
import com.example.juke.network.toTrack
import com.example.juke.network.BrowseItem
import com.example.juke.ui.components.AlbumCard
import com.example.juke.ui.components.ArtistCard
import com.example.juke.ui.components.PlaylistCard
import com.example.juke.ui.components.SwipeToAddNextContainer
import com.example.juke.ui.components.GlassButton
import com.example.juke.ui.components.SearchHeader
import com.example.juke.ui.components.TrackListSkeleton
import com.example.juke.utils.rememberJukeHaptics
import com.example.juke.viewmodels.MusicViewModel
import com.example.juke.viewmodels.SearchViewModel
import kotlinx.coroutines.launch

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
internal fun hasResults(uiState: com.example.juke.viewmodels.SearchUiState): Boolean {
    return uiState.topResult != null || uiState.tracks.isNotEmpty() ||
            uiState.artists.isNotEmpty() ||
            uiState.playlists.isNotEmpty() ||
            uiState.albums.isNotEmpty()
}

@Composable
internal fun SearchResultsList(
    uiState: com.example.juke.viewmodels.SearchUiState,
    selectedFilter: String,
    musicViewModel: MusicViewModel,
    onNavigateToArtist: (BrowseItem) -> Unit,
    onNavigateToPlaylist: (BrowseItem) -> Unit,
    onNavigateToAlbum: (BrowseItem) -> Unit,
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
        if (selectedFilter == "All") {
            val hero = uiState.topResult
            if (hero != null) item(key = "top-result") {
                SectionHeader("Top result")
                if (hero.kind == "track") {
                    com.example.juke.ui.components.HeroTrackCard(track = hero.toTrack(com.example.juke.services.AccountRepository.liked.value), onClick = { musicViewModel.playTrack(hero.toTrack()) }, modifier = Modifier.padding(horizontal = 20.dp))
                } else {
                    val menu = LocalMediaMenu.current
                    val open = { when (hero.kind) {
                        "artist" -> onNavigateToArtist(hero)
                        "album" -> onNavigateToAlbum(hero)
                        else -> onNavigateToPlaylist(hero)
                    } }
                    Column(Modifier.padding(horizontal = 20.dp).combinedClickable(onClick = open, onLongClick = { menu?.show(hero) }, onLongClickLabel = "More options")) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(120.dp).clip(if (hero.kind == "artist") CircleShape else androidx.compose.ui.graphics.RectangleShape)) {
                                AsyncImage(hero.image, hero.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                if (hero.kind in listOf("album", "playlist")) com.example.juke.ui.components.CollectionPlayButton(
                                    "Play ${hero.title}", { musicViewModel.playCollection(hero) }, Modifier.align(Alignment.BottomEnd).padding(8.dp))
                            }
                            Column(Modifier.padding(start = 16.dp).weight(1f)) {
                                Text(hero.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(hero.subtitle.ifBlank { hero.kind.replaceFirstChar { it.uppercase() } }, style = MaterialTheme.typography.bodyMedium)
                                androidx.compose.material3.TextButton(onClick = open) { Text("Open ${hero.kind}") }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
        // ── Songs ────────────────────────────────────────────────────────
        if ((selectedFilter == "All" || selectedFilter == "Tracks") && uiState.tracks.isNotEmpty()) {
            item { SectionHeader("Songs") }
            items(uiState.tracks.distinctBy { it.uuid }, key = { it.uuid }) { track ->
                SwipeToAddNextContainer(
                    onAddNext = { musicViewModel.addNext(track) }
                ) {
                    LocalTrackItem(
                        track = track,
                        onClick = { musicViewModel.playTrack(track) },
                        showAccentBar = false
                    )
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }

        // ── Artists ─────────────────────────────────────────────────────
        if ((selectedFilter == "All" || selectedFilter == "Artists") && uiState.artists.isNotEmpty()) {
            item { SectionHeader("Artists") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(uiState.artists.distinctBy { it.id }, key = { it.id }) { artist ->
                        ArtistCard(artist = artist, onClick = { onNavigateToArtist(artist) })
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }

        // ── Playlists ───────────────────────────────────────────────────
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
                            onClick = { onNavigateToPlaylist(playlist) },
                            onPlay = { musicViewModel.playCollection(playlist) }
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }

        // ── Albums ──────────────────────────────────────────────────────
        if ((selectedFilter == "All" || selectedFilter == "Albums") && uiState.albums.isNotEmpty()) {
            item { SectionHeader("Albums") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(uiState.albums.distinctBy { it.id }, key = { it.id }) { album ->
                        AlbumCard(album = album, onClick = { onNavigateToAlbum(album) }, onPlay = { musicViewModel.playCollection(album) })
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

// ── Section header ──────────────────────────────────────────────────────────
@Composable
internal fun SectionHeader(title: String) {
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
internal fun LocalTrackItem(
    track: Track,
    onClick: () -> Unit,
    showAccentBar: Boolean = true
) {
    val menu = LocalMediaMenu.current
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = { menu?.show(track) }, onLongClickLabel = "Song options"),
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
internal fun EmptySearchState(
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
internal fun RecentSearches(
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
