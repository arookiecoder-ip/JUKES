package com.example.juke.ui.screens

import com.example.juke.ui.components.stableStatusBarsPadding

import com.example.juke.ui.components.LocalMediaMenu

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.os.Build
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Person
import androidx.compose.foundation.border
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.juke.models.Track
import com.example.juke.ui.components.HeroTrackCard
import com.example.juke.ui.components.HomeSkeleton
import com.example.juke.ui.components.GlassIconButton
import com.example.juke.ui.components.GlassPillButton
import com.example.juke.ui.theme.GlassLevel
import com.example.juke.ui.theme.GlassShapes
import com.example.juke.ui.theme.glassPane
import com.example.juke.utils.rememberJukeHaptics
import com.example.juke.viewmodels.HomeViewModel
import com.example.juke.viewmodels.MusicViewModel
import com.example.juke.network.BrowseItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    musicViewModel: MusicViewModel,
    homeViewModel: HomeViewModel = viewModel(),
    onSettingsClick: () -> Unit = {},
    onOpenItem: (BrowseItem) -> Unit = {},
    onSearchClick: () -> Unit = {},
    bottomPadding: Dp = 0.dp,
    jam: com.example.juke.viewmodels.JamViewModel = viewModel()
) {
    val uiState by homeViewModel.uiState.collectAsStateWithLifecycle()
    val jamState by jam.state.collectAsStateWithLifecycle()
    LaunchedEffect(jam) { jam.refresh() }
    val connected by musicViewModel.echo.amazonConnected.collectAsStateWithLifecycle()
    val devices by musicViewModel.echo.devices.collectAsStateWithLifecycle()
    val serial by musicViewModel.echo.serial.collectAsStateWithLifecycle()
    val device = devices.firstOrNull { it.serial == serial }
    val alexaStatus = when {
        connected == null -> "Alexa · Checking"
        connected == false -> "Alexa · Disconnected"
        device == null -> "Alexa · No device"
        !device.online -> "Alexa · Offline"
        else -> "Alexa · Online"
    }
    val haptic = rememberJukeHaptics()

    LaunchedEffect(Unit) {
        homeViewModel.loadHomeData()
    }

    if (uiState.isLoading && uiState.shelves.isEmpty()) {
        HomeSkeleton(bottomPadding = bottomPadding)
        return
    }

    HomeContent(
        uiState = uiState, alexaStatus = alexaStatus, bottomPadding = bottomPadding, jamActive = jamState.active,
        onSettingsClick = { haptic.click(); onSettingsClick() }, onRefresh = homeViewModel::refresh,
        onSearchClick = onSearchClick, onOpenItem = onOpenItem,
        onPlayTracks = { tracks, index -> musicViewModel.setQueue(tracks, index) },
        onPlayCollection = musicViewModel::playCollection
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeContent(
    uiState: com.example.juke.viewmodels.HomeUiState,
    alexaStatus: String,
    bottomPadding: Dp = 0.dp,
    onSettingsClick: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onSearchClick: () -> Unit = {},
    onOpenItem: (BrowseItem) -> Unit = {},
    onPlayTracks: (List<Track>, Int) -> Unit = { _, _ -> },
    onPlayCollection: (BrowseItem) -> Unit = {},
    jamActive: Boolean = false
) {
    PullToRefreshBox(
        isRefreshing = uiState.isRefreshing,
        onRefresh = { onRefresh() },
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("Home feed"),
            contentPadding = PaddingValues(bottom = 16.dp + bottomPadding)
        ) {
            item(key = "home-header") {
                HomeHeader(alexaStatus = alexaStatus, onSettingsClick = { onSettingsClick() }, jamActive = jamActive)
            }
            if (uiState.shelves.isEmpty()) {
                item(key = "empty-home") {
                    EmptyHomeState(
                        message = uiState.error ?: "No recommendations yet. Pull down to try again.",
                        onRetry = { onRefresh() },
                        onSearchClick = onSearchClick,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 360.dp)
                    )
                }
            }
            uiState.shelves.forEachIndexed { index, shelf ->
                item(key = "shelf_${index}_${shelf.id}") {
                    when {
                        shelf.tracks.isNotEmpty() && shelf.items.all { it.kind == "track" } -> SongOnlyShelf(
                            title = shelf.title, tracks = shelf.tracks,
                            onTrackClick = { onPlayTracks(shelf.tracks, it) }
                        )
                        shelf.items.all { it.kind == "artist" } -> ArtistShelf(shelf.title, shelf.items, onOpenItem)
                        else -> BrowseShelfRow(
                            title = shelf.title, items = shelf.items, tracks = shelf.tracks,
                            onTrackClick = { onPlayTracks(shelf.tracks, it) },
                            onOpen = onOpenItem, onPlayCollection = { onPlayCollection(it) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeHeader(
    alexaStatus: String,
    onSettingsClick: () -> Unit,
    jamActive: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .stableStatusBarsPadding()
            .padding(start = 24.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(com.example.juke.R.drawable.music_box_pwa),
                contentDescription = null, modifier = Modifier.size(32.dp)
            )
            Text("Music Box", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(44.dp).clip(androidx.compose.ui.graphics.RectangleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh, androidx.compose.ui.graphics.RectangleShape)
                .then(Modifier.border(1.dp, if (jamActive) Color(0xFF66BB6A) else MaterialTheme.colorScheme.outlineVariant, androidx.compose.ui.graphics.RectangleShape))
                .clickable(role = Role.Button, onClick = onSettingsClick)
        ) {
            Icon(Icons.Filled.Person, contentDescription = "Profile", modifier = Modifier.size(24.dp))
        }
    }
}

/** Four compact songs per horizontally scrolling column, matching web Home shelves. */
@Composable
internal fun SongOnlyShelf(title: String, tracks: List<Track>, onTrackClick: (Int) -> Unit) {
    val menu = LocalMediaMenu.current
    val columns = remember(tracks) { tracks.chunked(4) }
    Column(Modifier.padding(top = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            androidx.compose.material3.OutlinedButton(onClick = { onTrackClick(0) }, enabled = tracks.isNotEmpty(),
                modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("Play all") }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columnWidth = (if (maxWidth >= 600.dp && androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) (maxWidth - 64.dp) / 2 else maxWidth - 48.dp).coerceAtLeast(240.dp)
            LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                itemsIndexed(columns, key = { index, songs -> "$index:${songs.firstOrNull()?.uuid}" }) { columnIndex, songs ->
                    Column(Modifier.width(columnWidth)) {
                        songs.forEachIndexed { row, track ->
                            Row(Modifier.fillMaxWidth().height(64.dp).combinedClickable(
                                onClick = { onTrackClick(columnIndex * 4 + row) },
                                onLongClick = { menu?.show(track) }, onLongClickLabel = "Song options"), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(56.dp).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                                    com.example.juke.ui.components.ListArtwork(track.thumbnailUri, 56.dp, Modifier.fillMaxSize())
                                    Icon(Icons.Default.PlayArrow, null, Modifier.size(28.dp), tint = Color.White)
                                }
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(track.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(track.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}

/** A shelf of songs, albums, playlists and stations. Songs play the shelf; the rest open. */
@Composable
private fun BrowseShelfRow(
    title: String,
    items: List<BrowseItem>,
    tracks: List<Track>,
    onTrackClick: (Int) -> Unit,
    onOpen: (BrowseItem) -> Unit,
    onPlayCollection: (BrowseItem) -> Unit
) {
    val trackIndices = remember(tracks) { buildMap {
        tracks.forEachIndexed { index, track -> if (!containsKey(track.ytVideoId)) put(track.ytVideoId, index) }
    } }
    Column {
        SectionHeader(title = title)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(items, key = { index, item -> "$index:${item.kind}:${item.id}" }) { _, item ->
                if (item.kind == "track") {
                    val trackIndex = trackIndices[item.videoId] ?: -1
                    val track = tracks.getOrNull(trackIndex) ?: return@itemsIndexed
                    MusicCard(track = track, onClick = { onTrackClick(trackIndex) })
                } else if (item.kind == "artist") {
                    ArtistCircle(artist = item, onClick = { onOpen(item) })
                } else {
                    CollectionCard(item = item, onClick = { onOpen(item) }, onPlay = { onPlayCollection(item) })
                }
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
    }
}

@Composable
private fun ArtistShelf(
    title: String,
    artists: List<BrowseItem>,
    onClick: (BrowseItem) -> Unit
) {
    Column {
        SectionHeader(title = title)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(artists) { _, artist ->
                ArtistCircle(artist = artist, onClick = { onClick(artist) })
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
    }
}

@Composable
private fun SectionHeader(
    title: String,
    onActionClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (onActionClick != null) {
            TextButton(
                onClick = onActionClick,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(
                    "See All",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun MusicCard(track: Track, onClick: () -> Unit) {
    val menu = LocalMediaMenu.current
    Column(Modifier.width(160.dp).combinedClickable(onClick = onClick, onLongClick = { menu?.show(track) }, onLongClickLabel = "Song options")) {
        Box(Modifier.size(160.dp).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            com.example.juke.ui.components.ListArtwork(track.thumbnailUri, 160.dp, Modifier.fillMaxSize())
            androidx.compose.material3.FilledIconButton(onClick = onClick, modifier = Modifier.size(48.dp), colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(containerColor = Color.White.copy(alpha = 0.24f))) {
                Icon(Icons.Filled.PlayArrow, "Play ${track.title}", Modifier.size(30.dp), tint = Color.White)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(track.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.heightIn(min = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        com.example.juke.ui.components.DownloadedBadge(track.ytVideoId, Modifier.padding(end = 4.dp))
        Text(track.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CollectionCard(item: BrowseItem, onClick: () -> Unit, onPlay: () -> Unit) {
    val menu = LocalMediaMenu.current
    Column(Modifier.width(160.dp).combinedClickable(onClick = onClick, onLongClick = { menu?.show(item) }, onLongClickLabel = "Collection options")) {
        Box(Modifier.size(160.dp).testTag("home-artwork-${item.id}").background(MaterialTheme.colorScheme.surfaceVariant)) {
            com.example.juke.ui.components.ListArtwork(item.image, 160.dp, Modifier.fillMaxSize())
            if (item.kind in listOf("album", "playlist")) {
                com.example.juke.ui.components.CollectionPlayButton("Play ${item.title}", onPlay, Modifier.align(Alignment.BottomEnd).padding(8.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(item.subtitle.ifBlank { item.kind.replaceFirstChar { it.uppercase() } }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ArtistCircle(
    artist: BrowseItem,
    onClick: () -> Unit
) {
    val haptic = rememberJukeHaptics()
    val menu = LocalMediaMenu.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val cardScale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = tween(durationMillis = 140),
        label = "artistCircleScale"
    )

    Column(
        modifier = Modifier
            .width(160.dp)
            .graphicsLayer {
                scaleX = cardScale
                scaleY = cardScale
            }
            .semantics(mergeDescendants = true) {
                contentDescription = "Artist ${artist.title}"
            }
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onLongClick = { haptic.heavyClick(); menu?.show(artist) },
                onLongClickLabel = "Artist options",
                interactionSource = interactionSource,
                role = Role.Button,
                onClickLabel = "Open ${artist.title}"
            ) {
                haptic.click()
                onClick()
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(160.dp).testTag("home-artwork-${artist.id}")
                .glassPane(CircleShape, GlassLevel.Regular),
            contentAlignment = Alignment.Center
        ) {
            if (artist.image.isNotBlank()) {
                com.example.juke.ui.components.ListArtwork(artist.image, 160.dp, Modifier.fillMaxSize().clip(CircleShape))
            } else {
                Icon(
                    Icons.Filled.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = artist.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Text("Artist", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun EmptyHomeState(
    message: String,
    onRetry: () -> Unit,
    onSearchClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val isShort = maxHeight < 400.dp
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(if (isShort) 16.dp else 32.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(if (isShort) 48.dp else 80.dp)
                    .glassPane(CircleShape, GlassLevel.Thick),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(if (isShort) 28.dp else 40.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(if (isShort) 12.dp else 24.dp))
            Text(
                "Welcome to Music Box",
                style = if (isShort) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                message,
                style = if (isShort) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 24.sp
            )
            Spacer(modifier = Modifier.height(if (isShort) 12.dp else 24.dp))
            GlassPillButton(text = "Try again", onClick = onRetry)
            TextButton(onClick = onSearchClick) { Text("Search music") }
        }
    }
}
