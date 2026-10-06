package com.example.juke.ui.screens

import com.example.juke.ui.components.GlassAlertDialog
import androidx.compose.material3.ModalBottomSheet
import com.example.juke.ui.components.GlassIconButton
import com.example.juke.ui.components.GlassModalBottomSheet
import com.example.juke.ui.theme.GlassBackdrop
import com.example.juke.ui.theme.isGlassDark
import com.example.juke.ui.theme.GlassLevel
import com.example.juke.ui.theme.GlassShapes
import com.example.juke.ui.theme.GlassSurface
import com.example.juke.ui.theme.JUKETheme
import com.example.juke.ui.theme.glassSheetColor
import androidx.compose.ui.draw.alpha
import com.example.juke.ui.theme.GlassCard
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.annotation.SuppressLint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.outlined.Refresh
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.surfaceColorAtElevation
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.juke.R
import com.example.juke.network.BrowseItem
import com.example.juke.network.Backend
import com.example.juke.network.objectOrEmpty
import com.example.juke.network.text
import com.example.juke.models.artistCredits
import com.example.juke.models.Track
import com.example.juke.ui.components.AddToPlaylistDialog
import com.example.juke.ui.components.CreatePlaylistDialog
import com.example.juke.ui.components.PlayerSkeleton
import com.example.juke.ui.components.player.PlayerArtwork
import com.example.juke.ui.components.player.PlayerControls
import com.example.juke.ui.components.player.PlayerProgress
import com.example.juke.ui.components.player.QueueBottomSheetContent
import com.example.juke.utils.LyricsRomanizer
import com.example.juke.utils.rememberJukeHaptics
import com.example.juke.viewmodels.LibraryViewModel
import com.example.juke.viewmodels.MusicViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Re-export LyricLine for compatibility if needed elsewhere,
// though it should ideally be in a model file.
data class LyricLine(
    val timeMs: Long,
    val text: String
)

// Helper moved to top level or util file, keeping here for now to avoid breaking changes if used elsewhere
fun parseSyncedLyrics(syncedLyrics: String, offsetMs: Long = 0L): List<LyricLine> {
    val lines = mutableListOf<LyricLine>()
    val regex = """\[(\d{2}):(\d{2})\.(\d{1,3})]\s*(.*)""".toRegex()

    syncedLyrics.lines().forEach { line ->
        regex.find(line)?.let { match ->
            val minutes = match.groupValues[1].toLongOrNull() ?: 0L
            val seconds = match.groupValues[2].toLongOrNull() ?: 0L
            val frac = match.groupValues[3]
            var text = match.groupValues[4]

            // Basic cleanup of lrc artifact chars
            text = text.trim()

            val millisFromFrac = when (frac.length) {
                3 -> frac.toLongOrNull() ?: 0L
                2 -> (frac.toLongOrNull() ?: 0L) * 10L
                1 -> (frac.toLongOrNull() ?: 0L) * 100L
                else -> frac.padEnd(3, '0').take(3).toLongOrNull() ?: 0L
            }

            val timeMs = (minutes * 60 * 1000) + (seconds * 1000) + millisFromFrac
            val adjustedTimeMs = (timeMs + offsetMs).coerceAtLeast(0L)
            if (text.isNotBlank()) {
                lines.add(LyricLine(adjustedTimeMs, text))
            }
        }
    }

    return lines.sortedBy { it.timeMs }
}

@SuppressLint("ConfigurationScreenWidthHeight")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    musicViewModel: MusicViewModel,
    libraryViewModel: LibraryViewModel = viewModel(),
    onDismiss: () -> Unit,
    onNavigateToArtist: (String) -> Unit,
    onNavigateToAlbum: (String) -> Unit,
    onNavigateToArtistByName: (String) -> Unit = {}
) {
    BackHandler(onBack = onDismiss)

    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    val playerContext = LocalContext.current
    fun openArtist(artist: com.example.juke.models.ArtistCredit) {
        if (!artist.id.isNullOrBlank()) onNavigateToArtist(artist.id)
        else coroutineScope.launch {
            try {
                val id = Backend.get("/api/artist/resolve/", mapOf("name" to artist.name))
                    .objectOrEmpty().text("channel_id", "artist_id", "id")
                check(id.isNotBlank()) { "Artist not found" }
                onNavigateToArtist(id)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { android.widget.Toast.makeText(playerContext, e.message ?: "Artist not found", android.widget.Toast.LENGTH_SHORT).show() }
        }
    }
    val uiState by musicViewModel.uiState.collectAsStateWithLifecycle()
    val currentTrack = uiState.currentTrack
    var showQueue by remember { mutableStateOf(false) }
    LaunchedEffect(showQueue) { if (showQueue) musicViewModel.refreshQueue() }
    var showLyrics by remember { mutableStateOf(false) }
    var showArtists by remember { mutableStateOf(false) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var showOutputSheet by remember { mutableStateOf(false) }
    val output by musicViewModel.output.collectAsStateWithLifecycle()
    val isSwitching by musicViewModel.isSwitchingOutput.collectAsStateWithLifecycle()
    val echoVolume by musicViewModel.echoVolume.collectAsStateWithLifecycle()
    val echoDevices by musicViewModel.echo.devices.collectAsStateWithLifecycle()
    val echoSerial by musicViewModel.echo.serial.collectAsStateWithLifecycle()
    val isAlexa = output == com.example.juke.viewmodels.PlaybackOutput.ALEXA
    val romanizeLyrics by musicViewModel.isRomanizedLyricsEnabled.collectAsStateWithLifecycle()
    val sleepTimerRemaining by musicViewModel.sleepTimerRemaining.collectAsStateWithLifecycle()
    val haptic = rememberJukeHaptics()

    var showAddToPlaylistDialog by remember { mutableStateOf<Track?>(null) }
    var showNewPlaylistDialog by remember { mutableStateOf(false) }
    var editablePlaylists by remember { mutableStateOf<List<BrowseItem>?>(null) }

    // Fetch the account's editable playlists when the dialog opens
    var playlistLoadError by remember { mutableStateOf<String?>(null) }
    var playlistRetry by remember { mutableStateOf(0) }
    LaunchedEffect(showAddToPlaylistDialog, playlistRetry) {
        playlistLoadError = null
        editablePlaylists = null
        if (showAddToPlaylistDialog != null) {
            editablePlaylists = try {
                libraryViewModel.editablePlaylists()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                playlistLoadError = com.example.juke.network.networkErrorMessage(e) ?: e.message ?: "Couldn't load playlists"
                null
            }
        }
    }

    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    configuration.screenHeightDp.dp
    val isTablet = minOf(configuration.screenWidthDp, configuration.screenHeightDp) >= 600

    val lifecycleOwner = LocalLifecycleOwner.current
    // Poll the position only while playing; paused, one read is enough (no 300 ms wakeups).
    LaunchedEffect(lifecycleOwner, uiState.isPlaying) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            musicViewModel.updateProgress()
            while (uiState.isPlaying) {
                delay(300)
                musicViewModel.updateProgress()
            }
        }
    }

    if (currentTrack == null) {
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                PlayerSkeleton()
            }
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No track playing")
            }
        }
        return
    }

    var romanizedTrack by remember(
        currentTrack.uuid,
        romanizeLyrics,
        currentTrack.syncedLyrics,
        currentTrack.plainLyrics,
        currentTrack.romanizedSyncedLyrics,
        currentTrack.romanizedPlainLyrics
    ) { mutableStateOf<Track?>(null) }

    LaunchedEffect(
        currentTrack.uuid,
        currentTrack.syncedLyrics,
        currentTrack.plainLyrics,
        currentTrack.romanizedSyncedLyrics,
        currentTrack.romanizedPlainLyrics,
        romanizeLyrics
    ) {
        if (!romanizeLyrics) {
            romanizedTrack = null
            return@LaunchedEffect
        }

        // A saved result that still has non-Latin lines came from a failed request: redo it.
        val complete = LyricsRomanizer::isFullyRomanized
        // Failed lines (offline, rate-limited, network blocked in background) are retried a few
        // times; successful lines are cached in memory, so a retry only re-asks for the failures.
        for (attempt in 0..3) {
            if (attempt > 0) delay(5_000L * attempt)
            val romanizedSynced = currentTrack.romanizedSyncedLyrics?.takeIf(complete)
                ?: currentTrack.syncedLyrics?.let { LyricsRomanizer.romanizeSyncedLyrics(it) }
            val romanizedPlain = currentTrack.romanizedPlainLyrics?.takeIf(complete)
                ?: currentTrack.plainLyrics?.let { LyricsRomanizer.romanizeText(it) }
            romanizedTrack = currentTrack.copy(
                syncedLyrics = romanizedSynced ?: currentTrack.syncedLyrics,
                plainLyrics = romanizedPlain ?: currentTrack.plainLyrics,
                romanizedSyncedLyrics = romanizedSynced,
                romanizedPlainLyrics = romanizedPlain
            )

            val allDone = romanizedSynced?.let(complete) != false && romanizedPlain?.let(complete) != false
            if (!allDone) continue
            // Only a complete result is saved, so one bad fetch can't stick to the track forever.
            if (romanizedSynced != currentTrack.romanizedSyncedLyrics ||
                romanizedPlain != currentTrack.romanizedPlainLyrics
            ) {
                musicViewModel.persistRomanizedLyrics(
                    track = currentTrack,
                    romanizedSyncedLyrics = romanizedSynced,
                    romanizedPlainLyrics = romanizedPlain
                )
            }
            break
        }
    }

    val displayTrack = if (romanizeLyrics) romanizedTrack ?: currentTrack else currentTrack

    // Modal Sheet for Player
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.Transparent, // Transparent to show the ambient glass backdrop
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxSize(),
        // The sheet reserves the navigation-bar inset by default, which left a strip at the bottom
        // where the screen behind (mini player, tab bar) showed through. The backdrop must reach the
        // screen edge; the content already pads itself with safeDrawingPadding below.
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        dragHandle = null
    ) {
        JUKETheme(darkTheme = true) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Plain black backdrop; the artwork is the only color on the screen.
            Box(Modifier.fillMaxSize().background(GlassBackdrop.color(isGlassDark())))
            // Content — fully responsive, adapts to screen height
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
            ) {
                val compact = maxHeight < 680.dp
                com.example.juke.ui.components.player.ResponsivePlayerLayout(artwork = {
                    Box(Modifier.fillMaxSize()) {
                        PlayerArtwork(queue = uiState.queue, queueIndex = uiState.queueIndex, currentTrack = displayTrack,
                            currentPosition = uiState.position, showLyrics = showLyrics, musicViewModel = musicViewModel,
                            isTablet = isTablet, onToggleLyrics = { showLyrics = !showLyrics })
                        Box(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(horizontal = 20.dp)) {
                        PlayerHeader(
                            onDismiss = onDismiss,
                            onShowSleepTimer = { showSleepTimerDialog = true },
                            onNavigateToAlbum = { currentTrack.albumId?.let(onNavigateToAlbum) },
                            onRefreshLyrics = { musicViewModel.refreshLyrics(currentTrack) },
                            onToggleRomanizedLyrics = { musicViewModel.toggleRomanizedLyrics() },
                            isRomanizedLyricsEnabled = romanizeLyrics,
                            showMenuOption = true,
                            isAlbumAvailable = currentTrack.albumId != null,
                            track = currentTrack,
                            onAddToPlaylist = { showAddToPlaylistDialog = currentTrack },
                            onToggleLyrics = { showLyrics = !showLyrics },
                            showLyrics = showLyrics
                        )
                        }
                        if (!showLyrics) PlayerBannerMetadata(currentTrack, compact,
                            onArtistClick = {
                                val artists = currentTrack.artistCredits()
                                if (artists.size > 1) showArtists = true
                                else artists.firstOrNull()?.let { artist ->
                                    openArtist(artist)
                                }
                            }, modifier = Modifier.align(Alignment.BottomStart))
                    }
                }, controls = {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                            PlayerAction(icon = rememberVectorPainter(if (currentTrack.isFavourite) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp),
                                label = if (currentTrack.isFavourite) "Unlike" else "Like", active = currentTrack.isFavourite, iconSize = 24.dp) { musicViewModel.toggleFavorite(currentTrack) }
                            PlayerAction(icon = painterResource(R.drawable.baseline_mix), label = "Mix", iconSize = 24.dp) { musicViewModel.startRadio() }
                            PlayerAction(icon = rememberVectorPainter(if (isAlexa) Icons.Outlined.Speaker else Icons.Outlined.PhoneAndroid), label = if (isAlexa) "Alexa" else "Phone", active = isAlexa, iconSize = 24.dp) { showOutputSheet = true }
                            PlayerAction(icon = rememberVectorPainter(Icons.AutoMirrored.Filled.List), label = "Queue", iconSize = 24.dp) { showQueue = true }
                        }
                        PlayerProgress(currentPosition = uiState.position, uiState = uiState, musicViewModel = musicViewModel, modifier = Modifier.padding(top = 10.dp))
                        PlayerControls(uiState = uiState, musicViewModel = musicViewModel, isLarge = isTablet,
                            modifier = Modifier.padding(top = 8.dp), playButtonSize = if (compact) 60.dp else 72.dp, buttonSize = 48.dp, iconSize = 32.dp, smallIconSize = 24.dp, onSaveToPlaylist = { showAddToPlaylistDialog = currentTrack })
                        Spacer(Modifier.height(16.dp))
                        if (isAlexa) EchoVolumeRow(volume = echoVolume, enabled = musicViewModel.echo.serial.value.isNotBlank(), onVolumeChange = musicViewModel::setEchoVolume)
                        else PhoneVolumeRow()
                        Spacer(Modifier.height(8.dp))
                    }
                })
            }
        }
        }
    }

    if (showArtists) {
        com.example.juke.ui.components.ArtistPickerSheet(currentTrack.artistCredits(), onDismiss = { showArtists = false }) { artist ->
            showArtists = false
            openArtist(artist)
        }
    }

    // Queue Sheet
    if (showQueue) {
        ModalBottomSheet(
            shape = androidx.compose.ui.graphics.RectangleShape,
            containerColor = MaterialTheme.colorScheme.surface,
            contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
            onDismissRequest = { showQueue = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            modifier = Modifier.fillMaxWidth(),
            dragHandle = null
        ) {
            val rec by musicViewModel.recStatus.collectAsStateWithLifecycle()
            val queueError by musicViewModel.queueLoadError.collectAsStateWithLifecycle()
            Box(Modifier.fillMaxWidth().height(androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * 0.75f)) {
            QueueBottomSheetContent(
                statusText = when {
                    rec.reserve > 0 -> "${rec.reserve} more songs ready from the radio"
                    else -> null
                },
                error = queueError, onRetry = musicViewModel::refreshQueue,
                currentTrack = currentTrack,
                queue = uiState.queue,
                queueIndex = uiState.queueIndex,
                uiState = uiState,
                onClose = { showQueue = false },
                onMoveTrack = { from, to -> musicViewModel.moveInQueue(from, to) },
                onRemoveTrack = { id -> musicViewModel.removeFromQueue(id) },
                onPlayTrack = { track -> musicViewModel.playTrackFromQueue(track) },
                onShuffleUpcoming = { musicViewModel.applyQueueTool(com.example.juke.viewmodels.MusicViewModel.QueueTool.SHUFFLE_UPCOMING) },
                onSortUpcoming = { musicViewModel.applyQueueTool(com.example.juke.viewmodels.MusicViewModel.QueueTool.SORT_UPCOMING) },
                onClearPlayed = { musicViewModel.applyQueueTool(com.example.juke.viewmodels.MusicViewModel.QueueTool.CLEAR_PLAYED) },
                onSaveAsPlaylist = { musicViewModel.saveQueueAsPlaylist() }
            )
            }
        }
    }

    // Output picker: this phone or an Echo
    if (showOutputSheet) {
        GlassModalBottomSheet(
            onDismissRequest = { showOutputSheet = false },
            sheetState = rememberModalBottomSheetState()
        ) {
            OutputPickerContent(
                isAlexa = isAlexa,
                devices = echoDevices,
                selectedSerial = echoSerial,
                busy = isSwitching,
                onSelectPhone = { showOutputSheet = false; musicViewModel.switchOutput(null) },
                onSelectEcho = { serial -> showOutputSheet = false; musicViewModel.switchOutput(serial) },
                onRefresh = { musicViewModel.refreshDevices() }
            )
        }
    }

    // Sleep Timer Dialog
    if (showSleepTimerDialog) {
        SleepTimerDialog(
            currentTimerRemaining = sleepTimerRemaining,
            onDismiss = { showSleepTimerDialog = false },
            onSetTimer = { minutes ->
                if (minutes > 0) musicViewModel.startSleepTimer(minutes)
                else musicViewModel.cancelSleepTimer()
                showSleepTimerDialog = false
            },
            onCancelTimer = {
                musicViewModel.cancelSleepTimer()
                showSleepTimerDialog = false
            }
        )
    }

    showAddToPlaylistDialog?.let { track ->
        AddToPlaylistDialog(
            playlists = editablePlaylists,
            error = playlistLoadError,
            onRetry = { playlistRetry++ },
            tracks = listOf(track),
            onDismiss = { showAddToPlaylistDialog = null },
            onAddToPlaylist = { playlist ->
                libraryViewModel.addTracksToPlaylist(playlist, listOf(track))
                showAddToPlaylistDialog = null
            },
            onCreatePlaylist = { showNewPlaylistDialog = true }
        )
    }

    if (showNewPlaylistDialog) {
        CreatePlaylistDialog(
            onDismiss = { showNewPlaylistDialog = false },
            onCreate = { name ->
                libraryViewModel.createPlaylist(name, listOfNotNull(showAddToPlaylistDialog))
                showNewPlaylistDialog = false
                showAddToPlaylistDialog = null
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerHeader(
    onDismiss: () -> Unit, onShowSleepTimer: () -> Unit, onNavigateToAlbum: () -> Unit,
    onRefreshLyrics: () -> Unit, onToggleRomanizedLyrics: () -> Unit,
    isRomanizedLyricsEnabled: Boolean, showMenuOption: Boolean, isAlbumAvailable: Boolean,
    track: Track? = null,
    onAddToPlaylist: () -> Unit = {}, onToggleLyrics: () -> Unit = {}, showLyrics: Boolean = false
) {
    val mediaMenu = com.example.juke.ui.components.LocalMediaMenu.current
    var showMenu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onDismiss) { Icon(Icons.Default.KeyboardArrowDown, "Close player") }
        Text("Now playing", style = MaterialTheme.typography.labelLarge)
        if (showLyrics) IconButton(onClick = onToggleLyrics) { Icon(Icons.Default.Close, "Hide lyrics") }
        else if (showMenuOption) IconButton(onClick = {
            if (track != null && mediaMenu != null) {
                val extras = listOf(
                    com.example.juke.ui.components.ExtraSongOption(Icons.Outlined.Lyrics, "Lyrics", onToggleLyrics),
                    com.example.juke.ui.components.ExtraSongOption(Icons.Outlined.Timer, "Sleep timer", onShowSleepTimer),
                    com.example.juke.ui.components.ExtraSongOption(Icons.Outlined.Refresh, "Refresh lyrics", onRefreshLyrics),
                    com.example.juke.ui.components.ExtraSongOption(Icons.Outlined.Translate, if (isRomanizedLyricsEnabled) "Romanized lyrics: On" else "Romanized lyrics: Off", onToggleRomanizedLyrics)
                )
                mediaMenu.show(track, extras = extras, playerOnly = true)
            } else showMenu = true
        }) { Icon(Icons.Default.MoreVert, "Song options") }
        else Spacer(Modifier.size(48.dp))
    }
    if (showMenu) {
        ModalBottomSheet(sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), onDismissRequest = { showMenu = false }, shape = androidx.compose.ui.graphics.RectangleShape,
            containerColor = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().testTag("Player options").verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
                track?.let { com.example.juke.ui.components.MusicMenuHeader(it.title, it.artist, it.thumbnailUri) }
                fun run(action: () -> Unit) { showMenu = false; action() }
                com.example.juke.ui.components.MusicMenuOption(Icons.Outlined.Lyrics, if (showLyrics) "Hide lyrics" else "Lyrics") { run(onToggleLyrics) }
                if (isAlbumAvailable) com.example.juke.ui.components.MusicMenuOption(Icons.Outlined.Album, "Go to album") { run(onNavigateToAlbum) }
                com.example.juke.ui.components.MusicMenuOption(Icons.Outlined.Timer, "Sleep timer") { run(onShowSleepTimer) }
                com.example.juke.ui.components.MusicMenuOption(Icons.Outlined.Refresh, "Refresh lyrics") { run(onRefreshLyrics) }
                com.example.juke.ui.components.MusicMenuOption(Icons.Outlined.Translate, if (isRomanizedLyricsEnabled) "Romanized lyrics: On" else "Romanized lyrics: Off") { run(onToggleRomanizedLyrics) }
            }
        }
    }
}

@Composable
private fun PhoneVolumeRow() {
    val context = LocalContext.current
    val audio = remember(context) { context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager }
    val maximum = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    var volume by remember { mutableIntStateOf(audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)) }
    DisposableEffect(context, audio) {
        val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { volume = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) }
        }
        context.contentResolver.registerContentObserver(android.provider.Settings.System.CONTENT_URI, true, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    EchoVolumeRow(volume = (volume * 100f / maximum).toInt(), onVolumeChange = { percent ->
        volume = (percent * maximum / 100f).roundToInt().coerceIn(0, maximum)
        audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, volume, 0)
    })
}

@Composable
fun SleepTimerDialog(
    currentTimerRemaining: Long?,
    onDismiss: () -> Unit,
    onSetTimer: (Int) -> Unit,
    onCancelTimer: () -> Unit
) {
    var sliderValue by remember {
        mutableFloatStateOf(currentTimerRemaining?.let { it / 60000f } ?: 0f)
    }

    GlassAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep Timer") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (currentTimerRemaining != null) {
                    val minutes = (currentTimerRemaining / 1000 / 60).toInt()
                    val seconds = ((currentTimerRemaining / 1000) % 60).toInt()

                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
        accent = MaterialTheme.colorScheme.primaryContainer) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "Playback will pause in:",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                String.format(
                                    java.util.Locale.getDefault(),
                                    "%02d:%02d",
                                    minutes,
                                    seconds
                                ),
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                } else {
                    Text(
                        if (sliderValue > 0) "${sliderValue.toInt()} minutes" else "Timer off",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                    Slider(
                        value = sliderValue,
                        onValueChange = { sliderValue = it },
                        valueRange = 0f..180f,
                        steps = 179,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            if (currentTimerRemaining != null) {
                TextButton(onClick = onCancelTimer) { Text("Cancel Timer") }
            } else {
                TextButton(
                    onClick = { onSetTimer(sliderValue.toInt()) },
                    enabled = sliderValue > 0
                ) { Text("Set Timer") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (currentTimerRemaining != null) "Close" else "Cancel")
            }
        }
    )
}

/** Echo volume slider; drags are sent to the Echo a moment after you pause (the controller debounces). */
@Composable
private fun EchoVolumeRow(volume: Int?, enabled: Boolean = true, onVolumeChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Filled.VolumeDown, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        com.example.juke.ui.components.player.ExpandableTrackSlider(
            value = (volume ?: 50) / 100f,
            label = "Volume",
            enabled = enabled,
            onValueChange = { onVolumeChange((it * 100).toInt()) },
            onValueChangeFinished = { onVolumeChange((it * 100).toInt()) },
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
        )
        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${volume ?: "—"}", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(32.dp), textAlign = TextAlign.End)
    }
}

@Composable
private fun OutputPickerContent(
    isAlexa: Boolean,
    devices: List<com.example.juke.services.EchoDevice>,
    selectedSerial: String,
    busy: Boolean,
    onSelectPhone: () -> Unit,
    onSelectEcho: (String) -> Unit,
    onRefresh: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
        Text(
            "Play on",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
        )
        OutputRow(
            icon = Icons.Outlined.PhoneAndroid, title = "This phone", detail = null,
            selected = !isAlexa, enabled = true, onClick = onSelectPhone
        )
        if (devices.isEmpty()) {
            Text(
                "No Echo found. Connect Amazon in Settings to play on an Echo.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
            )
        }
        devices.forEach { device ->
            OutputRow(
                icon = Icons.Outlined.Speaker, title = device.name,
                detail = if (device.online) null else "Offline",
                selected = isAlexa && device.serial == selectedSerial,
                enabled = device.online,
                onClick = { onSelectEcho(device.serial) }
            )
        }
        TextButton(onClick = onRefresh, modifier = Modifier.padding(horizontal = 12.dp)) { Text("Refresh devices") }
        if (busy) {
            androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp))
        }
    }
}

@Composable
private fun OutputRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String?,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(
                title,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
        },
        supportingContent = detail?.let { { Text(it) } },
        leadingContent = {
            Icon(icon, contentDescription = null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingContent = {
            if (selected) Icon(Icons.Default.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
        },
        colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.5f).clickable(enabled = enabled, onClick = onClick)
    )
}

@Composable
private fun PlayerAction(
    icon: androidx.compose.ui.graphics.painter.Painter,
    label: String,
    iconSize: androidx.compose.ui.unit.Dp,
    active: Boolean = false,
    onClick: () -> Unit
) {
    val tint = if (active) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 64.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp)
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(iconSize))
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
    }
}

/** The title stays on the artwork, above its fade into the controls. */
@Composable
internal fun PlayerBannerMetadata(track: Track, compact: Boolean, onArtistClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(track.title, style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Text(track.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onArtistClick).padding(vertical = 4.dp))
    }
}
