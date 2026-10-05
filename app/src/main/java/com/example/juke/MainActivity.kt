package com.example.juke

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import com.example.juke.ui.components.GlassAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.juke.viewmodels.HomeViewModel
import com.example.juke.viewmodels.LibraryViewModel
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.juke.analytics.AnalyticsManager
import com.example.juke.models.GithubRelease
import com.example.juke.services.DownloadedUpdate
import com.example.juke.services.UpdateManager
import com.example.juke.services.UpdateDownloadState
import com.example.juke.ui.components.GlassNavBar
import com.example.juke.ui.components.GlassNavItem
import com.example.juke.ui.components.GlassNavRail
import com.example.juke.ui.components.MiniPlayer
import com.example.juke.ui.theme.GlassBackdrop
import com.example.juke.ui.theme.isGlassDark
import com.example.juke.ui.theme.LocalHazeState
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.CompositionLocalProvider
import com.example.juke.ui.screens.AlbumDetailScreen
import com.example.juke.ui.screens.ArtistSongsScreen
import com.example.juke.ui.screens.ArtistDetailScreen
import com.example.juke.ui.screens.AccountCheckScreen
import com.example.juke.ui.screens.SettingsScreen
import com.example.juke.ui.screens.SignInScreen
import com.example.juke.ui.screens.HomeScreen
import com.example.juke.ui.screens.LibraryScreen
import com.example.juke.ui.screens.PlayerScreen
import com.example.juke.ui.screens.PlaylistDetailScreen
import com.example.juke.ui.screens.SearchScreen
import com.example.juke.ui.theme.JUKETheme
import com.example.juke.viewmodels.AccountViewModel
import com.example.juke.viewmodels.AlbumDetailViewModel
import com.example.juke.viewmodels.AuthStage
import com.example.juke.viewmodels.MusicViewModel
import com.example.juke.viewmodels.PlaylistDetailViewModel
import com.example.juke.viewmodels.SearchViewModel

sealed class Screen(
    val route: String,
    val title: String,
    val filledIcon: @Composable () -> Unit,
    val outlinedIcon: @Composable () -> Unit
) {
    object Home : Screen(
        "home",
        "Home",
        { Icon(Icons.Filled.Home, contentDescription = "Home") },
        { Icon(Icons.Outlined.Home, contentDescription = "Home") })

    object Search : Screen(
        "search",
        "Search",
        { Icon(Icons.Filled.Search, contentDescription = "Search") },
        { Icon(Icons.Outlined.Search, contentDescription = "Search") })

    object Library : Screen(
        "library",
        "Library",
        {
            Icon(
                painter = painterResource(R.drawable.library_outlined),
                contentDescription = "Library"
            )
        },
        { Icon(painter = painterResource(R.drawable.library), contentDescription = "Library") })
}

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    private val showPlayerOnLaunch = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        com.example.juke.ui.theme.GlassPrefs.solid =
            getSharedPreferences("ui_prefs", MODE_PRIVATE).getBoolean("solid_surfaces", false)

        // Check intent immediately
        handlePlayerIntent(intent)

        setContent {
            val musicViewModel: MusicViewModel = viewModel()
            val uiState by musicViewModel.uiState.collectAsStateWithLifecycle()
            val homeViewModel: HomeViewModel = viewModel()
            val libraryViewModel: LibraryViewModel = viewModel()
            val searchViewModel: SearchViewModel = viewModel()
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner, musicViewModel) {
                val observer = LifecycleEventObserver { _, _ ->
                    musicViewModel.setForeground(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                musicViewModel.setForeground(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                    musicViewModel.setForeground(false)
                }
            }
            val account: AccountViewModel = viewModel()
            val accountState by account.state.collectAsStateWithLifecycle()

            JUKETheme(
                extractedColors = uiState.extractedColors
            ) {
                LaunchedEffect(accountState.stage) {
                    when (accountState.stage) {
                        AuthStage.SIGNED_IN -> musicViewModel.onSignedIn()
                        AuthStage.SIGNED_OUT -> {
                            musicViewModel.onSignedOut()
                            homeViewModel.clear()
                            libraryViewModel.clear()
                        }
                        else -> {}
                    }
                }
                LaunchedEffect(Unit) { musicViewModel.signedOut.collect { account.sessionEnded() } }
                LaunchedEffect(Unit) { homeViewModel.signedOut.collect { account.sessionEnded() } }
                LaunchedEffect(Unit) { libraryViewModel.signedOut.collect { account.sessionEnded() } }
                LaunchedEffect(Unit) { searchViewModel.signedOut.collect { account.sessionEnded() } }
                LaunchedEffect(Unit) {
                    com.example.juke.network.NetworkFeedback.messages.collect { Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show() }
                }
                LaunchedEffect(Unit) {
                    musicViewModel.messages.collect { Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show() }
                }
                LaunchedEffect(Unit) {
                    account.accountsChanged.collect {
                        musicViewModel.onSignedIn()
                        homeViewModel.reload()
                        libraryViewModel.refresh()
                    }
                }

                if (accountState.stage == AuthStage.CHECKING) {
                    Box(Modifier.fillMaxSize().background(GlassBackdrop.color(isGlassDark())))
                } else if (accountState.stage != AuthStage.SIGNED_IN) {
                    SignInScreen(accountState, account)
                } else if (accountState.showAccountCheck) {
                    AccountCheckScreen(accountState, account)
                } else {
                val navController = rememberNavController()
                val activityViewModelProvider = remember(this@MainActivity) {
                    ViewModelProvider(this@MainActivity)
                }

                val context = LocalContext.current
                var showPlayerModal by remember { mutableStateOf(false) }
                var searchResetTrigger by remember { mutableIntStateOf(0) }
                var searchFocusTrigger by remember { mutableIntStateOf(0) }

                // --- UPDATE CHECK LOGIC ---
                var updateAvailable by remember { mutableStateOf<GithubRelease?>(null) }
                val updateDownloadState by UpdateManager.downloadState.collectAsStateWithLifecycle()
                val isUpdateDownloading = updateDownloadState is UpdateDownloadState.Downloading

                LaunchedEffect(Unit) {
                    // Yield the first frame before optional launch work.
                    withFrameNanos { }
                    musicViewModel.startDeferredStartupWork()
                    AnalyticsManager.getInstance(context).trackAppOpened()
                    updateAvailable = UpdateManager.checkForUpdates()


                }

                LaunchedEffect(updateDownloadState) {
                    val errorMessage =
                        (updateDownloadState as? UpdateDownloadState.Error)?.message ?: return@LaunchedEffect
                    Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
                    UpdateManager.clearDownloadState()
                }

                if (updateAvailable != null) {
                    val release = updateAvailable!!

                    // Determine if the update is an emergency update (e.g., contains "emergency" or "hotfix" in tags or body)
                    val isEmergency = release.tagName.contains("emergency", ignoreCase = true) ||
                            release.tagName.contains("hotfix", ignoreCase = true) ||
                            (release.body?.contains("emergency", ignoreCase = true) == true) ||
                            (release.body?.contains("critical", ignoreCase = true) == true) ||
                            (release.body?.contains("hotfix", ignoreCase = true) == true)

                    GlassAlertDialog(
                        onDismissRequest = {
                            // Only allow dismiss if not emergency
                            if (!isEmergency) {
                                updateAvailable = null
                            }
                        },
                        title = {
                            androidx.compose.foundation.layout.Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (isEmergency) Icons.Filled.Warning else Icons.Filled.SystemUpdate,
                                    contentDescription = "Update Icon",
                                    tint = if (isEmergency) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                                Text(
                                    text = if (isEmergency) "Critical Update Required" else "Update Available",
                                    color = if (isEmergency) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                    style = MaterialTheme.typography.titleLarge
                                )
                            }
                        },
                        text = {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    // Make the content scrollable
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text(
                                    text = "Version ${release.tagName} is now available.",
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )

                                if (isEmergency) {
                                    Text(
                                        text = "This update contains critical bug fixes. Please update immediately to continue using the app smoothly.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier
                                            .padding(bottom = 8.dp)
                                            .background(
                                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                                                shape = RoundedCornerShape(
                                                    8.dp
                                                )
                                            )
                                            .padding(8.dp)
                                    )
                                }

                                if (release.isPrerelease) {
                                    Text(
                                        text = "Note: This is a pre-release (beta) version.",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )
                                }

                                if (!release.body.isNullOrBlank()) {
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        shape = RoundedCornerShape(
                                            8.dp
                                        )
                                    ) {
                                        Text(
                                            text = release.body.trim(),
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(12.dp)
                                        )
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    if (UpdateManager.startUpdateDownload(context, release)) {
                                        updateAvailable = null
                                    }
                                },
                                enabled = !isUpdateDownloading,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isEmergency) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Text(if (isUpdateDownloading) "Downloading..." else "Download Update")
                            }
                        },
                        dismissButton = {
                            if (!isEmergency) {
                                TextButton(onClick = { updateAvailable = null }) {
                                    Text("Maybe Later")
                                }
                            }
                        }
                    )
                }

                val readyUpdate = (updateDownloadState as? UpdateDownloadState.Ready)?.update
                if (readyUpdate != null) {
                    UpdateReadyDialog(
                        downloadedUpdate = readyUpdate,
                        onDismiss = { UpdateManager.clearDownloadState() }
                    )
                }
                // --- END UPDATE CHECK LOGIC ---

                // Listen for changes to showPlayerOnLaunch
                LaunchedEffect(showPlayerOnLaunch.value) {
                    if (showPlayerOnLaunch.value) {
                        showPlayerModal = true
                        showPlayerOnLaunch.value = false
                    }
                }

                val items = listOf(
                    Screen.Home,
                    Screen.Search,
                    Screen.Library
                )

                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route

                var currentMainTab by remember { mutableStateOf(Screen.Home.route) }

                LaunchedEffect(currentRoute) {
                    if (
                        currentRoute == Screen.Home.route ||
                        currentRoute == Screen.Search.route ||
                        currentRoute == Screen.Library.route
                    ) {
                        currentMainTab = currentRoute
                    }
                }

                val isExpanded = LocalConfiguration.current.screenWidthDp >= 600
                // Artist/album/playlist are child screens: they can be opened on top of any tab
                // (and on top of each other), so a tab tap must first dispose of all of them.
                fun isDetailRoute(route: String?) =
                    route != null && (route.startsWith("artist/") ||
                        route.startsWith("album/") || route.startsWith("playlist/"))

                val onNavigate: (Screen) -> Unit = { screen ->
                    val wasDetail = isDetailRoute(currentRoute)
                    if (wasDetail) {
                        activityViewModelProvider[SearchViewModel::class.java].clearArtistDetail()
                        activityViewModelProvider[AlbumDetailViewModel::class.java].clearAlbumDetail()
                        activityViewModelProvider[PlaylistDetailViewModel::class.java].clearPlaylistDetail()
                        // Pop before navigating so saveState never captures a child screen.
                        while (isDetailRoute(navController.currentDestination?.route) &&
                            navController.popBackStack()
                        ) { /* keep popping */ }
                    }

                    if (navController.currentDestination?.route == screen.route) {
                        // Already on Search: select the query and open the keyboard for typing.
                        if (!wasDetail && screen == Screen.Search) searchFocusTrigger++
                    } else {
                        navController.navigate(screen.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }

                }

                val hazeState = remember { HazeState() }
                val navItems = items.map { screen ->
                    GlassNavItem(
                        label = screen.title,
                        selected = currentMainTab == screen.route,
                        onClick = { onNavigate(screen) },
                        icon = { if (currentMainTab == screen.route) screen.filledIcon() else screen.outlinedIcon() }
                    )
                }

                val mediaMenu = remember { com.example.juke.ui.components.MediaMenuController() }
                CompositionLocalProvider(LocalHazeState provides hazeState, com.example.juke.ui.components.LocalMediaMenu provides mediaMenu) {
                com.example.juke.ui.components.MediaActionMenuHost(mediaMenu, musicViewModel, libraryViewModel, onOpen = { item ->
                    showPlayerModal = false
                    when (item.kind) {
                        "artist" -> { searchViewModel.loadArtistDetails(item); navController.navigate("artist/${item.id}") }
                        "album" -> { activityViewModelProvider[AlbumDetailViewModel::class.java].loadAlbumDetails(item); navController.navigate("album/${item.id}") }
                        "playlist" -> { activityViewModelProvider[PlaylistDetailViewModel::class.java].loadPlaylistDetails(item); navController.navigate("playlist/${item.id}") }
                    }
                })
                Scaffold(
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                    bottomBar = {
                        if (currentRoute != "settings") {
                            Column(
                                modifier = Modifier.fillMaxWidth().background(com.example.juke.ui.theme.dockColor()),
                                verticalArrangement = Arrangement.spacedBy(0.dp)
                            ) {
                                MiniPlayer(musicViewModel = musicViewModel, onExpand = { showPlayerModal = true })
                                if (!isExpanded) {
                                    GlassNavBar(items = navItems)
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                    val layoutDirection = LocalLayoutDirection.current
                    val bottomPadding = innerPadding.calculateBottomPadding()
                    val contentPadding = PaddingValues(
                        start = innerPadding.calculateStartPadding(layoutDirection),
                        top = 0.dp,
                        end = innerPadding.calculateEndPadding(layoutDirection),
                        bottom = 0.dp
                    )

                    Box(modifier = Modifier.fillMaxSize()) {
                    // Backdrop source: the ambient light field and every screen scroll inside it,
                    // so the floating glass (tab bar, mini player) blurs what is really behind it.
                    Box(modifier = Modifier.fillMaxSize().hazeSource(hazeState)) {
                    Box(Modifier.fillMaxSize().background(GlassBackdrop.color(isGlassDark())))
                    Row(modifier = Modifier.fillMaxSize()) {
                        if (isExpanded && currentRoute != "settings") {
                            GlassNavRail(items = navItems, modifier = Modifier.statusBarsPadding())
                        }
                        NavHost(
                            navController = navController,
                            startDestination = Screen.Home.route,
                            modifier = Modifier.weight(1f).padding(contentPadding)
                        ) {
                            composable(Screen.Home.route) {
                                HomeScreen(
                                    musicViewModel = musicViewModel,
                                    homeViewModel = homeViewModel,
                                    onSettingsClick = { navController.navigate("settings") },
                                    onSearchClick = { onNavigate(Screen.Search) },
                                    onOpenItem = { item ->
                                        when (item.kind) {
                                            "artist" -> {
                                                activityViewModelProvider[SearchViewModel::class.java]
                                                    .loadArtistDetails(item)
                                                navController.navigate("artist/${item.id}")
                                            }
                                            "album" -> {
                                                activityViewModelProvider[AlbumDetailViewModel::class.java]
                                                    .loadAlbumDetails(item)
                                                navController.navigate("album/${item.id}")
                                            }
                                            "playlist" -> {
                                                activityViewModelProvider[PlaylistDetailViewModel::class.java]
                                                    .loadPlaylistDetails(item)
                                                navController.navigate("playlist/${item.id}")
                                            }
                                        }
                                    },
                                    bottomPadding = bottomPadding
                                )
                            }
                            composable(Screen.Search.route) {
                                val searchViewModel =
                                    activityViewModelProvider[SearchViewModel::class.java]
                                SearchScreen(
                                    musicViewModel = musicViewModel,
                                    searchViewModel = searchViewModel,
                                    searchResetTrigger = searchResetTrigger,
                                    searchFocusTrigger = searchFocusTrigger,
                                    onNavigateToArtist = { artist ->
                                        searchViewModel.loadArtistDetails(artist)
                                        navController.navigate("artist/${artist.id}")
                                    },
                                    onNavigateToPlaylist = { playlist ->
                                        activityViewModelProvider[PlaylistDetailViewModel::class.java]
                                            .loadPlaylistDetails(playlist)
                                        navController.navigate("playlist/${playlist.id}")
                                    },
                                    onNavigateToAlbum = { album ->
                                        activityViewModelProvider[AlbumDetailViewModel::class.java]
                                            .loadAlbumDetails(album)
                                        navController.navigate("album/${album.id}")
                                    },
                                    bottomPadding = bottomPadding
                                )
                            }
                            composable(Screen.Library.route) {
                                LibraryScreen(
                                    musicViewModel = musicViewModel,
                                    libraryViewModel = libraryViewModel,
                                    onOpenCollection = { item ->
                                        if (item.kind == "album") {
                                            activityViewModelProvider[AlbumDetailViewModel::class.java].loadAlbumDetails(item)
                                            navController.navigate("album/${item.id}")
                                        } else {
                                            activityViewModelProvider[PlaylistDetailViewModel::class.java].loadPlaylistDetails(item)
                                            navController.navigate("playlist/${item.id}")
                                        }
                                    },
                                    onOpenSettings = { navController.navigate("settings") },
                                    onOpenArtist = { artist ->
                                        searchViewModel.loadArtistDetails(artist)
                                        navController.navigate("artist/${artist.id}")
                                    },
                                    bottomPadding = bottomPadding
                                )
                            }
                            composable("settings") {
                                SettingsScreen(
                                    account = account,
                                    music = musicViewModel,
                                    onNavigateBack = { navController.popBackStack() },
                                    onNavigateToPowerTools = { navController.navigate("settings/power") },
                                    bottomPadding = bottomPadding
                                )
                            }
                            composable("settings/power") {
                                com.example.juke.ui.screens.PowerToolsScreen(
                                    onNavigateBack = { navController.popBackStack() },
                                    bottomPadding = bottomPadding
                                )
                            }
                            composable("artist/{artistId}") { entry ->
                                val artistId = entry.arguments?.getString("artistId").orEmpty()
                                LaunchedEffect(artistId) {
                                    if (searchViewModel.artistDetailState.value.artist?.id != artistId) searchViewModel.loadArtistDetailsById(artistId)
                                }
                                val searchViewModel =
                                    activityViewModelProvider[SearchViewModel::class.java]
                                ArtistDetailScreen(
                                    onNavigateToPlaylist = { playlist ->
                                        activityViewModelProvider[PlaylistDetailViewModel::class.java].loadPlaylistDetails(playlist)
                                        navController.navigate("playlist/${playlist.id}")
                                    },
                                    onNavigateToArtist = { artist -> searchViewModel.loadArtistDetails(artist); navController.navigate("artist/${artist.id}") },
                                    searchViewModel = searchViewModel,
                                    musicViewModel = musicViewModel,
                                    onNavigateBack = {
                                        searchViewModel.clearArtistDetail()
                                        navController.popBackStack()
                                    },
                                    onShowAllSongs = { navController.navigate("artist-songs/$artistId") },
                                    onNavigateToAlbum = { album ->
                                        activityViewModelProvider[AlbumDetailViewModel::class.java]
                                            .loadAlbumDetails(album)
                                        navController.navigate("album/${album.id}")
                                    },
                                    bottomPadding = bottomPadding
                                )
                            }
                            composable("artist-songs/{artistId}") { entry ->
                                ArtistSongsScreen(
                                    artistId = entry.arguments?.getString("artistId").orEmpty(),
                                    searchViewModel = searchViewModel,
                                    musicViewModel = musicViewModel,
                                    onNavigateBack = { navController.popBackStack() },
                                    bottomPadding = bottomPadding
                                )
                            }
                            composable("playlist/{playlistId}") {
                                val playlistDetailViewModel =
                                    activityViewModelProvider[PlaylistDetailViewModel::class.java]
                                PlaylistDetailScreen(
                                    playlistDetailViewModel = playlistDetailViewModel,
                                    musicViewModel = musicViewModel,
                                    onNavigateBack = {
                                        playlistDetailViewModel.clearPlaylistDetail()
                                        navController.popBackStack()
                                    },
                                    bottomPadding = bottomPadding
                                )
                            }
                            composable("album/{albumId}") {
                                val albumDetailViewModel =
                                    activityViewModelProvider[AlbumDetailViewModel::class.java]
                                AlbumDetailScreen(
                                    albumDetailViewModel = albumDetailViewModel,
                                    musicViewModel = musicViewModel,
                                    onNavigateBack = {
                                        albumDetailViewModel.clearAlbumDetail()
                                        navController.popBackStack()
                                    },
                                    bottomPadding = bottomPadding
                                )
                            }
                        }
                    }
                    }
                    }
                }
                }

                // Player Modal
                if (showPlayerModal) {
                    CompositionLocalProvider(com.example.juke.ui.components.LocalMediaMenu provides mediaMenu) {
                    PlayerScreen(
                        musicViewModel = musicViewModel,
                        onDismiss = { showPlayerModal = false },
                        onNavigateToArtist = { artistId ->
                            showPlayerModal = false
                            activityViewModelProvider[SearchViewModel::class.java]
                                .loadArtistDetailsById(artistId)
                            navController.navigate("artist/$artistId")
                        },
                        onNavigateToAlbum = { albumId ->
                            showPlayerModal = false
                            activityViewModelProvider[AlbumDetailViewModel::class.java]
                                .loadAlbumDetailsById(albumId)
                            navController.navigate("album/$albumId")
                        }
                    )
                    }
                }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePlayerIntent(intent)
    }

    private fun handlePlayerIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("open_player", false) == true) {
            showPlayerOnLaunch.value = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Track app closed and end session
        AnalyticsManager.getIfInitialized()?.let { analytics ->
            analytics.trackAppClosed()
            analytics.endSession()
        }
    }
}

@Composable
private fun UpdateReadyDialog(
    downloadedUpdate: DownloadedUpdate,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    GlassAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Update Downloaded") },
        text = {
            Text(
                "${downloadedUpdate.fileName} is ready. Install ${downloadedUpdate.releaseTag} now or open Downloads to manage the APK manually."
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    if (UpdateManager.installDownloadedUpdate(context, downloadedUpdate)) {
                        onDismiss()
                    }
                }
            ) {
                Text("Install Now")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = {
                    if (UpdateManager.openDownloadsFolder(context)) {
                        onDismiss()
                    }
                }
            ) {
                Text("Open Folder")
            }
        }
    )
}
