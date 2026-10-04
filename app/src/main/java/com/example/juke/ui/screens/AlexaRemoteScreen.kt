package com.example.juke.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.juke.network.*
import com.example.juke.ui.theme.GlassCard
import com.example.juke.viewmodels.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

@Composable
fun AlexaRemoteScreen(server: String, onSettings: () -> Unit, onPhoneMusic: () -> Unit,
    remote: AlexaRemoteViewModel = viewModel()) {
    val state by remote.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var tab by rememberSaveable { mutableStateOf("Home") }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("All") }
    var create by remember { mutableStateOf(false) }
    var menuItem by remember { mutableStateOf<RemoteItem?>(null) }
    var playlistTrack by remember { mutableStateOf<RemoteItem?>(null) }
    var playlists by remember { mutableStateOf<List<RemoteItem>?>(null) }
    var playlistError by remember { mutableStateOf<String?>(null) }
    var rename by remember { mutableStateOf<RemoteItem?>(null) }
    var delete by remember { mutableStateOf<RemoteItem?>(null) }
    val scope = rememberCoroutineScope()
    var accountBrowser by remember { mutableStateOf<String?>(null) }
    var browserCookies by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(server) { remote.configure(server) }
    DisposableEffect(lifecycle, remote) {
        val observer = LifecycleEventObserver { _, _ -> remote.active(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
        lifecycle.addObserver(observer)
        remote.active(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose { lifecycle.removeObserver(observer); remote.active(false) }
    }
    BackHandler(state.canGoBack) { remote.back() }
    LaunchedEffect(state.browserUrl) {
        state.browserUrl?.takeIf { it.isNotBlank() }?.let { value ->
            val url = if(value.startsWith("/")) server + value else value
            if (AlexaRemotePolicy.trusted(server, url)) {
                browserCookies = remote.browserCookies(url)
                accountBrowser = url
            }
            remote.browserConsumed()
        }
    }
    accountBrowser?.let { url -> AlexaAccountBrowser(server, url, browserCookies) { cookies ->
        accountBrowser = null; remote.browserFinished(url, cookies)
    } }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Echo & YouTube Music", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onSettings) { Text("Settings") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (if(state.guest) listOf("Home", "Search", "Explore", "Player", "Queue", "Jam") else listOf("Home", "Search", "Library", "Explore", "Player", "Queue", "Accounts", "Jam")).forEach { name ->
                FilterChip(selected = tab == name, onClick = {
                    tab = name; filter = "All"
                    when(name) { "Home", "Library", "Explore" -> remote.tab(name); "Jam" -> if(!state.guest) remote.jam("status"); "Accounts" -> remote.refreshProfile() }
                }, label = { Text(name) })
            }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { message ->
            GlassCard(Modifier.fillMaxWidth()) {
                Text(message, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
                Row { TextButton(onClick = { remote.initialize() }) { Text("Retry connection") }; TextButton(onClick = remote::dismiss) { Text("Dismiss") } }
            }
        }
        state.notice?.takeIf(String::isNotBlank)?.let { Text(it, Modifier.clickable { remote.dismiss() }.padding(8.dp)) }
        Box(Modifier.weight(1f)) {
            when(tab) {
                "Player" -> RemotePlayer(state, remote, onPhoneMusic)
                "Queue" -> RemoteQueue(state, remote) { menuItem = it }
                "Accounts" -> RemoteAccounts(state, remote, server)
                "Jam" -> RemoteJam(state, remote, server)
                else -> Column {
                    if (tab == "Search") {
                        OutlinedTextField(query, onValueChange = { query = it; remote.suggestions(it) },
                            label = { Text("Songs, artists, albums, playlists or YouTube URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Row {
                            Button(onClick = { filter = "All"; remote.search(query) }, enabled = query.isNotBlank()) { Text("Search") }
                            TextButton(onClick = { remote.playLink(query) }, enabled = query.isNotBlank() && state.serial.isNotBlank()) { Text("Play URL / query") }
                        }
                        state.suggestions.take(5).forEach { suggestion -> TextButton(onClick = { query = suggestion; remote.search(suggestion) }) { Text(suggestion) } }
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            listOf("All", "Tracks", "Artists", "Albums", "Playlists").forEach { value -> FilterChip(filter == value, onClick = { filter = value }, label = { Text(value) }) }
                        }
                    }
                    if (tab == "Library") Row(Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton(onClick = { remote.tab("Library") }) { Text("Playlists") }
                        TextButton(onClick = { remote.tab("Liked music") }) { Text("Liked music") }
                        TextButton(onClick = { remote.tab("Artists") }) { Text("Artists") }
                        TextButton(onClick = { remote.tab("History") }) { Text("History") }
                        TextButton(onClick = { create = true }) { Text("Create playlist") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.canGoBack) TextButton(onClick = remote::back) { Text("Back") }
                        Text(state.page?.title ?: state.destination.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { remote.loadPage() }) { Text("Refresh") }
                    }
                    if (tab == "Home" && state.destination.path == "/api/home/") Row(Modifier.horizontalScroll(rememberScrollState())) {
                        state.page?.raw?.array("filters")?.forEach { value ->
                            val obj = value.objectOrEmpty(); val id = obj.text("id")
                            FilterChip(state.destination.query["filter"].orEmpty().ifBlank { "all" } == id,
                                onClick = { remote.navigate(RemoteDestination("Home", "/api/home/", mapOf("filter" to id)), true) }, label = { Text(obj.text("label").ifBlank { id }) })
                        }
                    }
                    state.destination.entity?.let { entity ->
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            TextButton(onClick = {
                                if(entity.playlistId.isNotBlank()) remote.play(entity)
                                else state.page?.shelves?.flatMap { it.items }?.firstOrNull { it.videoId.isNotBlank() }?.let { remote.play(it) }
                            }, enabled = state.serial.isNotBlank()) { Text("Play") }
                            TextButton(onClick = { remote.queue(entity) }, enabled = state.serial.isNotBlank()) { Text("Add collection to queue") }
                            if(entity.kind == "artist") {
                                TextButton(onClick = { remote.subscribe(entity, true) }) { Text("Subscribe") }
                                TextButton(onClick = { remote.subscribe(entity, false) }) { Text("Unsubscribe") }
                                TextButton(onClick = remote::artistSongs) { Text("All songs") }
                            }
                            if(entity.editable) { TextButton(onClick = { rename = entity }) { Text("Rename") }; TextButton(onClick = { delete = entity }) { Text("Delete") } }
                        }
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                        val page = state.page
                        if(page != null && page.shelves.isEmpty()) item { Text("No items available.", Modifier.padding(16.dp)) }
                        page?.shelves?.forEach { shelf ->
                            val entries = shelf.items.filter { item -> filter == "All" || item.kind == when(filter) { "Tracks" -> "track"; "Artists" -> "artist"; "Albums" -> "album"; else -> "playlist" } }
                            if(entries.isNotEmpty()) {
                                item { Text(shelf.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp)) }
                                itemsIndexed(entries) { _, item -> RemoteRow(item, state.liked.contains(item.videoId), onClick = { remote.open(item) }, onMenu = { menuItem = item }) }
                            }
                        }
                        if(page?.hasMore == true) item { Button(onClick = { remote.loadPage(true) }, enabled = !state.busy) { Text("Load more songs") } }
                        item { Spacer(Modifier.height(20.dp)) }
                    }
                }
            }
        }
        if(tab != "Player" && state.nowPlaying.text("title").isNotBlank()) {
            GlassCard(Modifier.fillMaxWidth(), onClick = { tab = "Player" }) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(state.nowPlaying.text("thumbnail"), contentDescription = null, modifier = Modifier.size(42.dp))
                    Text(state.nowPlaying.text("title"), Modifier.weight(1f).padding(8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { remote.command(if(state.nowPlaying.flag("playing")) "pause" else "play") }) { Text(if(state.nowPlaying.flag("playing")) "Pause" else "Play") }
                }
            }
        }
    }
    menuItem?.let { item ->
        AlertDialog(onDismissRequest = { menuItem = null }, title = { Text(item.title) }, text = {
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = { remote.play(item); menuItem = null }) { Text("Play on Echo") }
                if(item.videoId.isNotBlank() && !state.guest) {
                    TextButton(onClick = { remote.play(item, radio = true); menuItem = null }) { Text("Start radio") }
                    TextButton(onClick = { remote.like(item, !state.liked.contains(item.videoId)); menuItem = null }) { Text(if(state.liked.contains(item.videoId)) "Unlike" else "Like") }
                    TextButton(onClick = { remote.artist(item); menuItem = null }) { Text("Go to artist") }
                    TextButton(onClick = { remote.album(item); menuItem = null }) { Text("Go to album") }
                    TextButton(onClick = {
                        playlistTrack = item; playlists = null; playlistError = null; menuItem = null
                        scope.launch { runCatching { remote.playlists() }.onSuccess { playlists = it }.onFailure { playlistError = it.message } }
                    }) { Text("Add to playlist") }
                }
                if(!state.guest) TextButton(onClick = { remote.queue(item, true); menuItem = null }) { Text("Play next") }
                if(!state.guest) TextButton(onClick = { remote.queue(item); menuItem = null }) { Text("Add to queue") }
                if(item.editable) {
                    TextButton(onClick = { rename = item; menuItem = null }) { Text("Rename playlist") }
                    TextButton(onClick = { delete = item; menuItem = null }) { Text("Delete playlist") }
                }
                TextButton(onClick = {
                    val url = if(item.videoId.isNotBlank()) "https://music.youtube.com/watch?v=${item.videoId}" else "https://music.youtube.com/playlist?list=${item.playlistId}"
                    shareRemote(context, url); menuItem = null
                }) { Text("Share") }
            }
        }, confirmButton = { TextButton(onClick = { menuItem = null }) { Text("Close") } })
    }
    if(create) RemoteNameDialog("Create YouTube playlist", "", onDismiss = { create = false }) { name -> remote.createPlaylist(name); create = false }
    rename?.let { item -> RemoteNameDialog("Rename playlist", item.title, onDismiss = { rename = null }) { name -> remote.renamePlaylist(item, name); rename = null } }
    delete?.let { item -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("Delete ${item.title}?") }, text = { Text("This deletes the playlist from YouTube Music.") },
        confirmButton = { TextButton(onClick = { remote.deletePlaylist(item); delete = null }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { delete = null }) { Text("Cancel") } }) }
    playlistTrack?.let { track -> AlertDialog(onDismissRequest = { playlistTrack = null }, title = { Text("Add to playlist") }, text = {
        LazyColumn {
            if(playlists == null && playlistError == null) item { CircularProgressIndicator() }
            playlistError?.let { item { Text(it) } }
            if(playlists?.isEmpty() == true) item { Text("No editable YouTube playlists. Create one in Library.") }
            itemsIndexed(playlists.orEmpty()) { _, playlist -> TextButton(onClick = { remote.addToPlaylist(track, playlist); playlistTrack = null }) { Text(playlist.title) } }
        }
    }, confirmButton = { TextButton(onClick = { playlistTrack = null }) { Text("Cancel") } }) }
}

@Composable private fun RemoteRow(item: RemoteItem, liked: Boolean, onClick: () -> Unit, onMenu: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(item.image, contentDescription = null, modifier = Modifier.size(56.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(item.subtitle.ifBlank { item.kind.replaceFirstChar { it.uppercase() } }, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if(liked) Text("Liked", style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = onMenu) { Text("More") }
        }
    }
}

@Composable private fun RemoteNameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember(initial) { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable private fun RemotePlayer(state: AlexaRemoteState, remote: AlexaRemoteViewModel, onPhone: () -> Unit) {
    val np = state.nowPlaying
    val duration = np.number("duration_ms").coerceAtLeast(1)
    var position by remember(np.number("position_ms")) { mutableStateOf(np.number("position_ms").toFloat()) }
    var volume by remember(np.number("volume")) { mutableStateOf(np.number("volume").toFloat()) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        item { Text("Echo device", style = MaterialTheme.typography.titleMedium) }
        itemsIndexed(state.devices) { _, device -> FilterChip(state.serial == device.text("serial"), onClick = { remote.selectDevice(device.text("serial")) }, label = { Text(device.text("name", "accountName").ifBlank { device.text("serial") }) }) }
        item { TextButton(onClick = remote::refreshDevices) { Text("Refresh devices") } }
        if(state.devices.isEmpty()) item { Text("Connect Amazon in Accounts to discover Echo devices.") }
        item { AsyncImage(np.text("thumbnail"), contentDescription = null, modifier = Modifier.fillMaxWidth().height(240.dp)) }
        item { Text(np.text("title").ifBlank { "Nothing playing" }, style = MaterialTheme.typography.headlineSmall); Text(np.text("artist")) }
        np["playback_error"].objectOrEmpty().text("message").takeIf(String::isNotBlank)?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        item {
            Slider(position.coerceIn(0f, duration.toFloat()), onValueChange = { position = it }, valueRange = 0f..duration.toFloat(), onValueChangeFinished = { remote.seek(position.toLong()) }, enabled = state.serial.isNotBlank() && duration > 1)
            Text("${remoteTime(position.toLong())} / ${remoteTime(duration)}")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onClick = { remote.command("previous") }, enabled = state.serial.isNotBlank()) { Text("Previous") }
                Button(onClick = { remote.command(if(np.flag("playing")) "pause" else "play") }, enabled = state.serial.isNotBlank()) { Text(if(np.flag("playing")) "Pause" else "Play") }
                TextButton(onClick = { remote.command("next") }, enabled = state.serial.isNotBlank()) { Text("Next") }
            }
        }
        item { Text("Volume ${volume.toInt()}%"); Slider(volume.coerceIn(0f,100f), { volume = it }, valueRange = 0f..100f, onValueChangeFinished = { remote.command("volume", volume.toLong()) }, enabled = state.serial.isNotBlank()) }
        item { TextButton(onClick = onPhone) { Text("Switch to phone playback") } }
    }
}
private fun remoteTime(ms: Long): String = "${ms / 60000}:${((ms / 1000) % 60).toString().padStart(2, '0')}"

@Composable private fun RemoteQueue(state: AlexaRemoteState, remote: AlexaRemoteViewModel, onMenu: (RemoteItem) -> Unit) {
    val queue = state.nowPlaying.array("queue").map { AlexaRemoteParser.item(it.objectOrEmpty()) }
    var clear by remember { mutableStateOf(false) }
    Column {
        Row { TextButton(onClick = { remote.queueEdit("shuffle") }, enabled = queue.isNotEmpty() && !state.guest) { Text("Shuffle") }; TextButton(onClick = { clear = true }, enabled = queue.isNotEmpty() && !state.guest) { Text("Clear queue") } }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if(queue.isEmpty()) item { Text("Queue is empty. Choose music from Home, Search or Library.") }
            itemsIndexed(queue) { index, item ->
                Column {
                    if(index.toLong() == state.nowPlaying.number("queue_index")) Text("Current song", style = MaterialTheme.typography.labelMedium)
                    RemoteRow(item, state.liked.contains(item.videoId), { remote.play(item, index = index) }, { onMenu(item) })
                    Row {
                        TextButton(onClick = { remote.queueEdit("move", index, index - 1) }, enabled = index > 0 && !state.busy && !state.guest) { Text("Move up") }
                        TextButton(onClick = { remote.queueEdit("move", index, index + 1) }, enabled = index < queue.lastIndex && !state.busy && !state.guest) { Text("Move down") }
                        TextButton(onClick = { remote.queueEdit("remove", index) }, enabled = !state.busy && !state.guest) { Text("Remove") }
                    }
                }
            }
        }
    }
    if(clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("Clear shared queue?") }, text = { Text("This clears the queue used by the Echo and web remote.") },
        confirmButton = { TextButton(onClick = { remote.queueEdit("clear"); clear = false }) { Text("Clear") } }, dismissButton = { TextButton(onClick = { clear = false }) { Text("Cancel") } })
}

@Composable private fun RemoteAccounts(state: AlexaRemoteState, remote: AlexaRemoteViewModel, server: String) {
    var username by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }; var code by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }; var email by remember { mutableStateOf("") }; var amazonPassword by remember { mutableStateOf("") }
    var signout by remember { mutableStateOf(false) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Server: $server", style = MaterialTheme.typography.bodySmall); Text("Production and staging use separate accounts and libraries. Select your server in Settings.") }
        item { Text("Owner sign in", style = MaterialTheme.typography.titleMedium); Text("Sign in with the same owner credentials as the web remote. Passwords stay in memory.") }
        item { OutlinedTextField(username, { username = it }, label = { Text("Owner username") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(password, { password = it }, label = { Text("Owner password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth()) }
        if(state.authToken.isNotBlank()) item { OutlinedTextField(code, { code = it }, label = { Text("Authentication code") }, singleLine = true) }
        item { Row { Button(onClick = { remote.login(username,password,code); password = ""; code = "" }, enabled = !state.busy) { Text("Sign in") }; TextButton(onClick = remote::logout) { Text("Sign out owner") } } }
        item { Text("Or use the API key for this server", style = MaterialTheme.typography.titleMedium); OutlinedTextField(apiKey, { apiKey = it }, label = { Text("Server API key") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()) }
        item { TextButton(onClick = { remote.saveKey(apiKey); apiKey = "" }) { Text("Save API key") } }
        item {
            Text("Amazon", style = MaterialTheme.typography.titleMedium)
            Text(if(state.profile.flag("amazon_connected")) "Connected" else "Not connected")
            OutlinedTextField(email, { email = it }, label = { Text("Amazon email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(amazonPassword, { amazonPassword = it }, label = { Text("Amazon password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            Row { TextButton(onClick = { remote.amazon(email, amazonPassword); amazonPassword = "" }, enabled = email.isNotBlank() && amazonPassword.isNotBlank() && !state.profile.flag("amazon_connected")) { Text("Connect Amazon") }; TextButton(onClick = { signout = true }, enabled = state.profile.flag("amazon_connected")) { Text("Disconnect") } }
        }
        item {
            Text("YouTube Music", style = MaterialTheme.typography.titleMedium)
            Text(if(state.profile.flag("youtube_auth_working")) "Library connected" else "Library needs sign in")
            Text(if(state.profile.flag("youtube_cookies_working")) "Playback connected" else "Playback needs browser reconnect")
            Text("Google and Amazon complete their secure sign in in a browser. Music browsing and controls stay in the app.")
            Row(Modifier.horizontalScroll(rememberScrollState())) { TextButton(onClick = { remote.youtube("start") }) { Text("Reconnect YouTube") }; TextButton(onClick = { remote.youtube("status") }) { Text("Check login") }; TextButton(onClick = { remote.youtube("capture") }) { Text("Capture sign in") } }
            Row { TextButton(onClick = { remote.youtube("retry") }) { Text("Retry") }; TextButton(onClick = { remote.youtube("open-youtube") }) { Text("Open YouTube") }; TextButton(onClick = { remote.youtube("stop") }) { Text("Stop session") } }
        }
        item { TextButton(onClick = remote::refreshProfile) { Text("Refresh account status") } }
    }
    if(signout) AlertDialog(onDismissRequest = { signout = false }, title = { Text("Disconnect Amazon?") }, text = { Text("This disconnects the account controlling all Echo devices on this server.") }, confirmButton = { TextButton(onClick = { remote.amazonSignOut(); signout = false }) { Text("Disconnect") } }, dismissButton = { TextButton(onClick = { signout = false }) { Text("Cancel") } })
}

@Composable private fun RemoteJam(state: AlexaRemoteState, remote: AlexaRemoteViewModel, server: String) {
    val context = LocalContext.current
    val url = state.jam.text("url")
    var invitation by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Jam", style = MaterialTheme.typography.headlineSmall)
        Text("Invite guests to choose music on your Echo. Starting again replaces the old invitation; stopping revokes guest access.")
        if(state.guest) { Text("Joined as a guest"); Button(onClick = remote::leaveJam) { Text("Leave Jam") }; return@Column }
        OutlinedTextField(invitation, { invitation = it }, label = { Text("Jam invitation URL") }, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { remote.joinJam(invitation) }, enabled = invitation.isNotBlank()) { Text("Join as guest") }
        Button(onClick = { remote.jam("start") }, enabled = !state.busy) { Text(if(state.jam.flag("active")) "Replace invitation" else "Start Jam") }
        if(state.jam.flag("active")) {
            Text(url)
            if(state.qr.isNotBlank()) AlexaJamQr(state.qr)
            Row { TextButton(onClick = { shareRemote(context,url) }) { Text("Share") }; TextButton(onClick = { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Jam invitation", url)) }) { Text("Copy link") }; TextButton(onClick = { remote.jam("stop") }) { Text("Stop Jam") } }
        }
        TextButton(onClick = { remote.jam("status") }) { Text("Refresh") }
    }
}
private fun shareRemote(context: Context, url: String) { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT,url) }, "Share music")) }
