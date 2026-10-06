package com.example.juke.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.RectangleShape
import coil.compose.AsyncImage
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.juke.models.Track
import com.example.juke.network.*
import com.example.juke.services.AccountRepository
import com.example.juke.viewmodels.LibraryViewModel
import com.example.juke.viewmodels.MusicViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext

data class QueueSongActions(val play: () -> Unit, val moveUp: (() -> Unit)? = null,
    val moveDown: (() -> Unit)? = null, val remove: (() -> Unit)? = null, val select: (() -> Unit)? = null)

data class ExtraSongOption(val icon: androidx.compose.ui.graphics.vector.ImageVector, val label: String, val action: () -> Unit)

class MediaMenuController {
    var playerOnly by mutableStateOf(false)
    var track by mutableStateOf<Track?>(null)
    var item by mutableStateOf<BrowseItem?>(null)
    var extraOptions by mutableStateOf<List<ExtraSongOption>>(emptyList())
    var queueActions by mutableStateOf<QueueSongActions?>(null)
    fun show(track: Track, queueActions: QueueSongActions? = null, extras: List<ExtraSongOption> = emptyList(), playerOnly: Boolean = false) { this.playerOnly = playerOnly; item = null; this.track = track; this.queueActions = queueActions; extraOptions = extras }
    fun show(item: BrowseItem, extras: List<ExtraSongOption> = emptyList()) { playerOnly = false; extraOptions = extras; track = null; queueActions = null; this.item = item }
    fun dismiss() { extraOptions = emptyList(); track = null; item = null; queueActions = null }
}
val LocalMediaMenu = staticCompositionLocalOf<MediaMenuController?> { null }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaActionMenuHost(menu: MediaMenuController, music: MusicViewModel, library: LibraryViewModel, onOpen: (BrowseItem) -> Unit) {
    val downloadedCollections by music.downloadedCollections.collectAsState()
    val downloaded by music.downloadedTracks.collectAsState()
    val downloadProgress by music.downloadProgress.collectAsState()
    val track = menu.track
    val item = menu.item
    val extraOptions = menu.extraOptions
    val queueActions = menu.queueActions
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val share = rememberShareAction()
    var saveTrack by remember { mutableStateOf<Track?>(null) }
    var playlists by remember { mutableStateOf<List<BrowseItem>?>(null) }
    var playlistError by remember { mutableStateOf<String?>(null) }
    var create by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var deletingCollection by remember { mutableStateOf<com.example.juke.services.DownloadedCollection?>(null) }
    fun resolve(block: suspend () -> Unit) {
        scope.launch {
            try { block() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Toast.makeText(context, e.message ?: "Action failed", Toast.LENGTH_SHORT).show() }
        }
    }
    if (track != null || item != null) {
        ModalBottomSheet(sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), onDismissRequest = menu::dismiss, shape = RectangleShape, containerColor = MaterialTheme.colorScheme.surface, dragHandle = { Box(Modifier.fillMaxWidth().height(20.dp), contentAlignment = Alignment.Center) { Box(Modifier.size(32.dp, 3.dp).background(MaterialTheme.colorScheme.onSurfaceVariant, androidx.compose.foundation.shape.RoundedCornerShape(2.dp))) } }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
                MusicMenuHeader(track?.title ?: item!!.title, track?.artist ?: item?.subtitle.orEmpty(), track?.thumbnailUri ?: item?.image)
                fun quick(run: () -> Unit) { menu.dismiss(); run() }
                if (track != null && !menu.playerOnly) {
                    val liked = track.ytVideoId in AccountRepository.liked.value
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                        MusicQuickAction(Icons.Filled.ThumbUp, if (liked) "Unlike" else "Like", Modifier.weight(1f), selected = liked) { quick { music.toggleFavorite(track) } }
                        MusicQuickAction(Icons.Filled.SkipNext, "Next", Modifier.weight(1f)) { quick { music.addNext(track) } }
                        MusicQuickAction(Icons.Filled.Add, "Queue", Modifier.weight(1f)) { quick { music.addToQueue(listOf(track)) } }
                        MusicQuickAction(Icons.Filled.Radio, "Radio", Modifier.weight(1f)) { quick { music.startRadio(track) } }
                    }
                    HorizontalDivider()
                } else if (item != null && item.kind in listOf("album", "playlist")) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                        MusicQuickAction(Icons.Filled.PlayArrow, "Play", Modifier.weight(1f)) { quick { music.playCollection(item) } }
                        MusicQuickAction(Icons.Filled.Shuffle, "Shuffle", Modifier.weight(1f)) { quick { music.playCollection(item, shuffle = true) } }
                        MusicQuickAction(Icons.Filled.SkipNext, "Next", Modifier.weight(1f)) { quick { music.queueCollection(item, next = true) } }
                        MusicQuickAction(Icons.Filled.Add, "Queue", Modifier.weight(1f)) { quick { music.queueCollection(item, next = false) } }
                    }
                    HorizontalDivider()
                }
                @Composable fun action(label: String, run: () -> Unit) {
                    val icon = when (label) {
                        "Play", "Play now" -> Icons.Filled.PlayArrow
                        "Like", "Unlike" -> Icons.Filled.ThumbUp
                        "Play next" -> Icons.Filled.SkipNext
                        "Add to queue" -> Icons.Filled.QueueMusic
                        "Play Radio" -> Icons.Filled.Radio
                        "Go to artist" -> Icons.Filled.Person
                        "Go to album" -> Icons.Filled.Album
                        "Share" -> Icons.Filled.Share
                        "Download", "Downloading…" -> Icons.Filled.Download
                        "Remove download", "Delete album download", "Delete playlist download" -> Icons.Filled.Delete
                        "Save to Playlist" -> Icons.Filled.PlaylistAdd
                        "Remove from queue" -> Icons.Filled.Delete
                        "Select" -> Icons.Filled.CheckBoxOutlineBlank
                        "Shuffle play" -> Icons.Filled.Shuffle
                        else -> Icons.Filled.OpenInNew
                    }
                    MusicMenuOption(icon, label, enabled = label != "Downloading…", showProgress = label == "Downloading…") { menu.dismiss(); run() }
                }
                if (track != null) {
                    queueActions?.let { actions ->
                        action("Play now", actions.play)
                        actions.select?.let { action("Select", it) }
                    }
                    action("Go to artist") {
                        resolve {
                            val id = track.artistId ?: Backend.get("/api/artist/resolve/", mapOf("name" to track.artist)).objectOrEmpty().text("channel_id", "artist_id", "id")
                            check(id.isNotBlank()) { "Artist unavailable for this song" }
                            onOpen(BrowseParser.item(JsonObject(mapOf("channel_id" to kotlinx.serialization.json.JsonPrimitive(id), "name" to kotlinx.serialization.json.JsonPrimitive(track.artist))), "artists"))
                        }
                    }
                    action("Go to album") {
                        resolve {
                            val id = track.albumId ?: Backend.get("/api/album/resolve/${track.ytVideoId}").objectOrEmpty().text("album_id")
                            check(id.isNotBlank()) { "Album unavailable for this song" }
                            onOpen(BrowseParser.item(JsonObject(mapOf("browseId" to kotlinx.serialization.json.JsonPrimitive(id))), "albums"))
                        }
                    }
                    val saved = downloaded.any { it.ytVideoId == track.ytVideoId }
                    val downloading = track.ytVideoId in downloadProgress
                    action(if (saved) "Remove download" else if (downloading) "Downloading…" else "Download") {
                        if (saved) music.removeDownload(track) else if (!downloading) music.download(track)
                    }
                    action("Share") {
                        track.ytVideoId?.takeIf { it.isNotBlank() }?.let { share("Share ${track.title}", "https://music.youtube.com/watch?v=$it") }
                    }
                    if (!menu.playerOnly) action("Save to Playlist") {
                        saveTrack = track; playlists = null; playlistError = null
                        resolve {
                            try { playlists = library.editablePlaylists() }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { playlistError = networkErrorMessage(e) ?: e.message ?: "Couldn't load playlists"; throw e }
                        }
                    }
                    if (queueActions != null) {
                        queueActions.remove?.takeIf { music.uiState.value.currentTrack?.uuid != track.uuid }?.let { action("Remove from queue", it) }
                    } else if (music.uiState.value.currentTrack?.uuid != track.uuid && music.uiState.value.queue.any { it.uuid == track.uuid }) {
                        action("Remove from queue") { music.removeFromQueue(track.uuid) }
                    }
                    if (extraOptions.isNotEmpty()) {
                        HorizontalDivider()
                        extraOptions.forEach { extra -> MusicMenuOption(extra.icon, extra.label) { menu.dismiss(); extra.action() } }
                    }
                } else if (item != null) {
                    if (item.kind in listOf("album", "playlist")) {
                        val saved = downloadedCollections.firstOrNull { it.id == item.id && it.kind == item.kind }
                        action(if (saved == null) "Download" else if (item.raw.flag("offline")) "Delete ${item.kind} download" else "Remove download") {
                            if (saved == null) music.downloadCollection(item)
                            else if (item.raw.flag("offline")) deletingCollection = saved
                            else music.downloads.removeCollection(saved)
                        }
                        if (saved != null && saved.available(downloaded).size < saved.tracks.size) {
                            action("Retry download") { music.downloads.retryCollection(saved) }
                        }
                    }
                    action("Open ${item.kind}") { onOpen(item) }
                    action("Share") {
                        val route = if (item.kind == "artist") "channel/${item.id}" else if (item.kind == "album") "browse/${item.id}" else "playlist?list=${item.playlistId.ifBlank { item.id }.removePrefix("VL")}"
                        share("Share ${item.title}", "https://music.youtube.com/$route")
                    }
                    extraOptions.forEach { extra -> MusicMenuOption(extra.icon, extra.label) { menu.dismiss(); extra.action() } }
                }
            }
        }
    }
    deletingCollection?.let { collection ->
        GlassAlertDialog(onDismissRequest = { deletingCollection = null }, title = { Text("Delete ${collection.kind} download?") },
            text = { Text("Remove ${collection.title} and its downloaded songs from this device? Songs in other downloaded collections are kept.") },
            confirmButton = { TextButton(onClick = { music.downloads.removeCollection(collection); deletingCollection = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deletingCollection = null }) { Text("Cancel") } })
    }
    saveTrack?.let { selected ->
        if (create) {
            AlertDialog(shape = RectangleShape, onDismissRequest = { create = false; saveTrack = null }, title = { Text("New playlist") },
                text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Playlist name") }) },
                confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { library.createPlaylist(name.trim(), listOf(selected)); create = false; saveTrack = null; name = "" }) { Text("Create") } },
                dismissButton = { TextButton(onClick = { create = false }) { Text("Back") } })
        } else {
            AddToPlaylistDialog(playlists, listOf(selected), onDismiss = { saveTrack = null },
                onAddToPlaylist = { library.addTracksToPlaylist(it, listOf(selected)); saveTrack = null }, onCreatePlaylist = { create = true }, error = playlistError,
                onRetry = {
                    playlistError = null
                    resolve {
                        try { playlists = library.editablePlaylists() }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { playlistError = networkErrorMessage(e) ?: e.message ?: "Couldn't load playlists"; throw e }
                    }
                })
        }
    }
}
