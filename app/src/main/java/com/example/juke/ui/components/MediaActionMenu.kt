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

class MediaMenuController {
    var track by mutableStateOf<Track?>(null)
    var item by mutableStateOf<BrowseItem?>(null)
    fun show(track: Track) { item = null; this.track = track }
    fun show(item: BrowseItem) { track = null; this.item = item }
    fun dismiss() { track = null; item = null }
}
val LocalMediaMenu = staticCompositionLocalOf<MediaMenuController?> { null }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaActionMenuHost(menu: MediaMenuController, music: MusicViewModel, library: LibraryViewModel, onOpen: (BrowseItem) -> Unit) {
    val track = menu.track
    val item = menu.item
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var saveTrack by remember { mutableStateOf<Track?>(null) }
    var playlists by remember { mutableStateOf<List<BrowseItem>?>(null) }
    var create by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    fun resolve(block: suspend () -> Unit) {
        scope.launch {
            try { block() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Toast.makeText(context, e.message ?: "Action failed", Toast.LENGTH_SHORT).show() }
        }
    }
    if (track != null || item != null) {
        ModalBottomSheet(sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), onDismissRequest = menu::dismiss, shape = RectangleShape, containerColor = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
                MusicMenuHeader(track?.title ?: item!!.title, track?.artist ?: item?.subtitle.orEmpty(), track?.thumbnailUri ?: item?.image)
                fun quick(run: () -> Unit) { menu.dismiss(); run() }
                if (track != null) {
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
                        "Play" -> Icons.Filled.PlayArrow
                        "Like", "Unlike" -> Icons.Filled.ThumbUp
                        "Play next" -> Icons.Filled.SkipNext
                        "Add to queue" -> Icons.Filled.QueueMusic
                        "Play Radio" -> Icons.Filled.Radio
                        "Go to artist" -> Icons.Filled.Person
                        "Go to album" -> Icons.Filled.Album
                        "Save to Playlist" -> Icons.Filled.PlaylistAdd
                        "Remove from queue" -> Icons.Filled.Delete
                        "Shuffle play" -> Icons.Filled.Shuffle
                        else -> Icons.Filled.OpenInNew
                    }
                    MusicMenuOption(icon, label) { menu.dismiss(); run() }
                }
                if (track != null) {
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
                    action("Save to Playlist") {
                        saveTrack = track; playlists = null
                        resolve {
                            try { playlists = library.editablePlaylists() }
                            catch (e: Exception) { saveTrack = null; throw e }
                        }
                    }
                    if (music.uiState.value.queue.any { it.uuid == track.uuid }) {
                        action("Remove from queue") { music.removeFromQueue(track.uuid) }
                    }
                } else if (item != null) {
                    action("Open ${item.kind}") { onOpen(item) }
                }
            }
        }
    }
    saveTrack?.let { selected ->
        if (create) {
            AlertDialog(onDismissRequest = { create = false; saveTrack = null }, title = { Text("New playlist") },
                text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Playlist name") }) },
                confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { library.createPlaylist(name.trim(), listOf(selected)); create = false; saveTrack = null; name = "" }) { Text("Create") } },
                dismissButton = { TextButton(onClick = { create = false }) { Text("Back") } })
        } else {
            AddToPlaylistDialog(playlists, listOf(selected), onDismiss = { saveTrack = null },
                onAddToPlaylist = { library.addTracksToPlaylist(it, listOf(selected)); saveTrack = null }, onCreatePlaylist = { create = true })
        }
    }
}
