@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package `in`.synthora.musicbox

import androidx.activity.viewModels
import `in`.synthora.musicbox.ui.components.stableStatusBarsPadding
import `in`.synthora.musicbox.ui.components.stableNavigationBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
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
import `in`.synthora.musicbox.ui.components.GlassAlertDialog
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
import `in`.synthora.musicbox.viewmodels.HomeViewModel
import `in`.synthora.musicbox.viewmodels.LibraryViewModel
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
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
import `in`.synthora.musicbox.analytics.AnalyticsManager
import `in`.synthora.musicbox.models.GithubRelease
import `in`.synthora.musicbox.services.DownloadedUpdate
import `in`.synthora.musicbox.services.UpdateManager
import `in`.synthora.musicbox.services.UpdateDownloadState
import `in`.synthora.musicbox.ui.components.GlassNavBar
import `in`.synthora.musicbox.ui.components.GlassNavItem
import `in`.synthora.musicbox.ui.components.GlassNavRail
import `in`.synthora.musicbox.ui.components.MiniPlayer
import `in`.synthora.musicbox.ui.theme.GlassBackdrop
import `in`.synthora.musicbox.ui.theme.isGlassDark
import `in`.synthora.musicbox.ui.theme.LocalHazeState
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.CompositionLocalProvider
import `in`.synthora.musicbox.ui.screens.AlbumDetailScreen
import `in`.synthora.musicbox.ui.screens.ArtistSongsScreen
import `in`.synthora.musicbox.ui.screens.ArtistDetailScreen
import `in`.synthora.musicbox.ui.screens.AccountCheckScreen
import `in`.synthora.musicbox.ui.screens.SettingsScreen
import `in`.synthora.musicbox.ui.screens.SignInScreen
import `in`.synthora.musicbox.ui.screens.HomeScreen
import `in`.synthora.musicbox.ui.screens.LibraryScreen
import `in`.synthora.musicbox.ui.screens.PlayerScreen
import `in`.synthora.musicbox.ui.screens.PlaylistDetailScreen
import `in`.synthora.musicbox.ui.screens.SearchScreen
import `in`.synthora.musicbox.ui.theme.JUKETheme
import `in`.synthora.musicbox.viewmodels.AccountViewModel
import `in`.synthora.musicbox.viewmodels.AlbumDetailViewModel
import `in`.synthora.musicbox.viewmodels.AuthStage
import `in`.synthora.musicbox.viewmodels.MusicViewModel
import `in`.synthora.musicbox.viewmodels.PlaylistDetailViewModel
import `in`.synthora.musicbox.viewmodels.SearchViewModel

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

    private val musicViewModel: MusicViewModel by lazy { (application as JukeApplication).playbackModel() }
    private val remoteVolumeKeys = mutableSetOf<Int>()

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        val direction = when (keyCode) {
            android.view.KeyEvent.KEYCODE_VOLUME_UP -> 1
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> -1
            else -> 0
        }
        if (direction != 0 && musicViewModel.adjustSelectedDeviceVolume(direction)) {
            remoteVolumeKeys += keyCode
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (remoteVolumeKeys.remove(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    private val showPlayerOnLaunch = mutableStateOf(false)
    private var downloadsOpenTrigger by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        run {
            val icon = checkNotNull(androidx.core.content.ContextCompat.getDrawable(this, R.drawable.music_box_pwa))
            val bitmap = android.graphics.Bitmap.createBitmap(192, 192, android.graphics.Bitmap.Config.ARGB_8888)
            icon.setBounds(0, 0, 192, 192)
            icon.draw(android.graphics.Canvas(bitmap))
            setTaskDescription(android.app.ActivityManager.TaskDescription(getString(R.string.app_name), bitmap, android.graphics.Color.rgb(10, 10, 10)))
        }

        enableEdgeToEdge()
        `in`.synthora.musicbox.ui.theme.GlassPrefs.solid =
            getSharedPreferences("ui_prefs", MODE_PRIVATE).getBoolean("solid_surfaces", false)

        // Check intent immediately
        handlePlayerIntent(intent)

        setContent {
            val themeColorsFlow = remember(musicViewModel) {
                musicViewModel.uiState.map { it.extractedColors }.distinctUntilChanged()
            }
            val themeColors by themeColorsFlow.collectAsStateWithLifecycle(initialValue = musicViewModel.uiState.value.extractedColors)
            val homeViewModel: HomeViewModel = viewModel()
            val libraryViewModel: LibraryViewModel = viewModel()
            val searchViewModel: SearchViewModel = viewModel()
            val jamViewModel: `in`.synthora.musicbox.viewmodels.JamViewModel = viewModel()
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner, musicViewModel) {
                val observer = LifecycleEventObserver { _, _ ->
                    val foreground = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                    musicViewModel.setForeground(foreground)
                    `in`.synthora.musicbox.network.NetworkFeedback.setForeground(
                        lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                    )
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                musicViewModel.setForeground(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
                `in`.synthora.musicbox.network.NetworkFeedback.setForeground(
                    lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                )
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                    musicViewModel.setForeground(false)
                    `in`.synthora.musicbox.network.NetworkFeedback.setForeground(false)
                }
            }
            val account: AccountViewModel = viewModel()
            val accountState by account.state.collectAsStateWithLifecycle()

            JUKETheme(
                extractedColors = themeColors
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
                    `in`.synthora.musicbox.network.NetworkFeedback.messages.collect {
                        // Never toast while minimized: collectors stay alive in background.
                        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show()
                        }
                    }
                }
                LaunchedEffect(Unit) {
                    libraryViewModel.messages.collect {
                        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show()
                        }
                    }
                }
                LaunchedEffect(Unit) {
                    musicViewModel.messages.collect {
                        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show()
                        }
                    }
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
                var showProfile by remember { mutableStateOf(false) }
                val profileConfig = androidx.compose.ui.platform.LocalConfiguration.current
                val tabletProfilePopup = profileConfig.smallestScreenWidthDp >= 600 &&
                    profileConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                val openProfile: () -> Unit = {
                    if (tabletProfilePopup) showProfile = true else navController.navigate("settings") { launchSingleTop = true }
                }
                LaunchedEffect(tabletProfilePopup) {
                    if (!tabletProfilePopup && showProfile) { showProfile = false; navController.navigate("settings") { launchSingleTop = true } }
                }
                LaunchedEffect(downloadsOpenTrigger) {
                    if (downloadsOpenTrigger > 0) {
                        showPlayerModal = false
                        navController.navigate(Screen.Library.route) { popUpTo(navController.graph.findStartDestination().id); launchSingleTop = true }
                    }
                }
                val notificationPermission = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
                val visualizerPermission = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
                val playingFlow = remember(musicViewModel) { musicViewModel.uiState.map { it.isPlaying }.distinctUntilChanged() }
                val watchdogPlaying by playingFlow.collectAsStateWithLifecycle(initialValue = musicViewModel.uiState.value.isPlaying)
                LaunchedEffect(watchdogPlaying) {
                    if (watchdogPlaying && musicViewModel.output.value == `in`.synthora.musicbox.viewmodels.PlaybackOutput.PHONE &&
                        ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        val prefs = getSharedPreferences("audio_visualizer_permission", MODE_PRIVATE)
                        if (!prefs.getBoolean("requested", false)) {
                            prefs.edit().putBoolean("requested", true).apply()
                            visualizerPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                }
                val activeDownloads by musicViewModel.downloads.status.collectAsStateWithLifecycle()
                val currentOutput by musicViewModel.output.collectAsStateWithLifecycle()
                LaunchedEffect(activeDownloads.active > 0, currentOutput) {
                    if ((activeDownloads.active > 0 || currentOutput == `in`.synthora.musicbox.viewmodels.PlaybackOutput.ALEXA) && Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        val permissionPrefs = getSharedPreferences("notification_permission", MODE_PRIVATE)
                        if (!permissionPrefs.getBoolean("download_requested", false)) {
                            permissionPrefs.edit().putBoolean("download_requested", true).apply()
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }

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
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
                    }
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
                LaunchedEffect(tabletProfilePopup, currentRoute) {
                    if (tabletProfilePopup && currentRoute == "settings") {
                        navController.popBackStack()
                        showProfile = true
                    }
                }

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

                val isExpanded = LocalConfiguration.current.smallestScreenWidthDp >= 600 &&
                    LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                // Artist/album/playlist are child screens: they can be opened on top of any tab
                // (and on top of each other), so a tab tap must first dispose of all of them.
                fun isDetailRoute(route: String?) =
                    route != null && (route.startsWith("artist/") ||
                        route.startsWith("album/") || route.startsWith("playlist/") ||
                        route.startsWith("artist-releases/") || route.startsWith("artist-songs/") || route.startsWith("downloads/"))

                val onNavigate: (Screen) -> Unit = { screen ->
                    val wasDetail = isDetailRoute(currentRoute)
                    if (wasDetail) {
                        if (screen == Screen.Search) { searchResetTrigger++; searchFocusTrigger++ }
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
                        if (!wasDetail && screen == Screen.Search) { searchResetTrigger++; searchFocusTrigger++ }
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

                val online by `in`.synthora.musicbox.network.NetworkFeedback.online.collectAsStateWithLifecycle()
                val mediaMenu = remember { `in`.synthora.musicbox.ui.components.MediaMenuController() }
                CompositionLocalProvider(LocalHazeState provides hazeState, `in`.synthora.musicbox.ui.components.LocalMediaMenu provides mediaMenu) {
                `in`.synthora.musicbox.ui.components.MediaActionMenuHost(mediaMenu, musicViewModel, libraryViewModel, onOpen = { item ->
                    showPlayerModal = false
                    if (item.raw["offline"]?.toString() == "true") navController.navigate("downloads/${android.net.Uri.encode("${item.kind}:${item.id}")}")
                    else when (item.kind) {
                        "artist" -> { searchViewModel.loadArtistDetails(item); navController.navigate("artist/${item.id}") }
                        "album" -> { activityViewModelProvider[AlbumDetailViewModel::class.java].loadAlbumDetails(item); navController.navigate("album/${item.id}") }
                        "playlist" -> { activityViewModelProvider[PlaylistDetailViewModel::class.java].loadPlaylistDetails(item); navController.navigate("playlist/${item.id}") }
                    }
                })
                Scaffold(
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    modifier = Modifier.fillMaxSize().then(if (showProfile) Modifier.blur(6.dp) else Modifier),
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                    bottomBar = {
                        if (currentRoute != "settings") {
                            Column(
                                modifier = Modifier.fillMaxWidth().background(`in`.synthora.musicbox.ui.theme.dockColor()),
                                verticalArrangement = Arrangement.spacedBy(0.dp)
                            ) {
                                MiniPlayer(musicViewModel = musicViewModel, onExpand = { showPlayerModal = true })
                                GlassNavBar(items = navItems, modifier = if (isExpanded) Modifier.padding(vertical = 4.dp) else Modifier)
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
                    // Surfaces are opaque; do not capture a full-screen blur source on each frame.
                    Box(modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().background(GlassBackdrop.color(isGlassDark())))
                    Row(modifier = Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).padding(contentPadding), contentAlignment = Alignment.TopCenter) {
                        NavHost(
                            navController = navController,
                            startDestination = Screen.Home.route,
                            modifier = Modifier.then(if (isExpanded) Modifier else Modifier.widthIn(max = 1200.dp)).fillMaxSize()
                        ) {
                            composable(Screen.Home.route) {
                                HomeScreen(
                                    jam = jamViewModel,
                                    musicViewModel = musicViewModel,
                                    homeViewModel = homeViewModel,
                                    onSettingsClick = openProfile,
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
                                    homeViewModel = homeViewModel,
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
                                    onOpenDownloadedCollection = { collection -> navController.navigate("downloads/${android.net.Uri.encode(collection.key)}") },
                                    downloadsOpenTrigger = downloadsOpenTrigger,
                                    onOpenSettings = openProfile,
                                    onOpenHistory = { navController.navigate("history") },
                                    onOpenArtist = { artist ->
                                        searchViewModel.loadArtistDetails(artist)
                                        navController.navigate("artist/${artist.id}")
                                    },
                                    bottomPadding = bottomPadding
                                )
                            }
                            composable("downloads/{collectionKey}") { entry ->
                                `in`.synthora.musicbox.ui.screens.DownloadedCollectionScreen(entry.arguments?.getString("collectionKey").orEmpty(),
                                    musicViewModel, { navController.popBackStack() }, bottomPadding)
                            }
                            composable("history") {
                                `in`.synthora.musicbox.ui.screens.HistoryScreen(musicViewModel, { navController.popBackStack() }, bottomPadding)
                            }
                            composable("settings") {
                                SettingsScreen(
                                    jam = jamViewModel,
                                    account = account,
                                    music = musicViewModel,
                                    onNavigateBack = { navController.popBackStack() },
                                    onNavigateToPowerTools = { navController.navigate("settings/power") },
                                    bottomPadding = bottomPadding
                                )
                            }
                            composable("settings/power") {
                                `in`.synthora.musicbox.ui.screens.PowerToolsScreen(
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
                                    onShowAllReleases = { kind -> navController.navigate("artist-releases/$artistId/$kind") },
                                    onNavigateToAlbum = { album ->
                                        activityViewModelProvider[AlbumDetailViewModel::class.java]
                                            .loadAlbumDetails(album)
                                        navController.navigate("album/${album.id}")
                                    },
                                    bottomPadding = bottomPadding
                                )
                            }
                            composable("artist-releases/{artistId}/{kind}") { entry ->
                                `in`.synthora.musicbox.ui.screens.ArtistReleasesScreen(
                                    artistId = entry.arguments?.getString("artistId").orEmpty(),
                                    kind = entry.arguments?.getString("kind").orEmpty(), music = musicViewModel,
                                    onBack = { navController.popBackStack() }, bottomPadding = bottomPadding,
                                    onOpen = { item ->
                                        if (item.kind == "playlist") {
                                            activityViewModelProvider[PlaylistDetailViewModel::class.java].loadPlaylistDetails(item)
                                            navController.navigate("playlist/${item.id}")
                                        } else {
                                            activityViewModelProvider[AlbumDetailViewModel::class.java].loadAlbumDetails(item)
                                            navController.navigate("album/${item.id}")
                                        }
                                    })
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
                            composable("album/{albumId}") { entry ->
                                val albumId = entry.arguments?.getString("albumId").orEmpty()
                                LaunchedEffect(albumId) {
                                    val vm = activityViewModelProvider[AlbumDetailViewModel::class.java]
                                    if (vm.uiState.value.album?.id != albumId) vm.loadAlbumDetailsById(albumId)
                                }
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
                        if (!online && !`in`.synthora.musicbox.utils.allowsOfflineBrowsing(currentRoute)) {
                            `in`.synthora.musicbox.ui.components.ConnectionErrorState("", {
                                `in`.synthora.musicbox.network.NetworkFeedback.refresh(context)
                            }, Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
                                .padding(bottom = bottomPadding), offline = true, onOpenDownloads = {
                                    downloadsOpenTrigger++
                                    navController.navigate(Screen.Library.route) { launchSingleTop = true }
                                })
                            if (currentRoute !in listOf(Screen.Home.route, Screen.Library.route)) {
                                `in`.synthora.musicbox.ui.components.DetailBackButton({
                                    this@MainActivity.onBackPressedDispatcher.onBackPressed()
                                }, Modifier.align(Alignment.TopStart))
                            }
                        }
                        }
                    }
                    }
                    }
                }
                }

                if (showProfile) {
                    androidx.compose.ui.window.Dialog(onDismissRequest = { showProfile = false },
                        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
                        androidx.compose.material3.Surface(
                            modifier = Modifier.padding(16.dp).widthIn(max = 680.dp)
                                .fillMaxWidth().height(LocalConfiguration.current.screenHeightDp.dp * 0.88f),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                            color = `in`.synthora.musicbox.ui.theme.glassSheetColor()) {
                            SettingsScreen(account = account, music = musicViewModel, jam = jamViewModel,
                                onNavigateBack = { showProfile = false },
                                onNavigateToPowerTools = { showProfile = false; navController.navigate("settings/power") })
                        }
                    }
                }

                if (!showPlayerModal) `in`.synthora.musicbox.ui.components.RemoteVolumeFeedback()

                // Player Modal
                if (showPlayerModal) {
                    CompositionLocalProvider(`in`.synthora.musicbox.ui.components.LocalMediaMenu provides mediaMenu) {
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
        if (intent?.getBooleanExtra(`in`.synthora.musicbox.services.DownloadService.OPEN_DOWNLOADS, false) == true) {
            showPlayerOnLaunch.value = false
            downloadsOpenTrigger++
            intent.removeExtra(`in`.synthora.musicbox.services.DownloadService.OPEN_DOWNLOADS)
        }
        if (intent?.getBooleanExtra("open_player", false) == true) {
            showPlayerOnLaunch.value = true
        }
    }

    override fun onDestroy() {
        if (isFinishing) `in`.synthora.musicbox.services.MobileDeviceConnection.stop()
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
