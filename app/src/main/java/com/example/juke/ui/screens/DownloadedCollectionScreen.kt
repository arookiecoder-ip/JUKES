package com.example.juke.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.juke.ui.components.*
import com.example.juke.viewmodels.MusicViewModel

/** A downloaded album/playlist is a real detail page, using its original ordering and identity. */
@Composable
fun DownloadedCollectionScreen(collectionKey: String, music: MusicViewModel, onBack: () -> Unit, bottomPadding: Dp = 0.dp) {
    val collections by music.downloadedCollections.collectAsStateWithLifecycle()
    val files by music.downloadedTracks.collectAsStateWithLifecycle()
    val collection = collections.firstOrNull { it.key == collectionKey }
    LaunchedEffect(collectionKey) { music.refreshDownloadedDetails(collectionKey) }
    val selection = remember(collectionKey) { DownloadSelection() }
    Box(Modifier.fillMaxSize()) {
        if (collection == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("This download was removed") }
        else {
            val available = remember(collection, files) { collection.available(files) }
            val local = available.associateBy { it.ytVideoId }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding + 16.dp)) {
                item {
                    val item = collection.browseItem()
                    CollectionDetailHero(item, collection.title, collection.image,
                        if (collection.kind == "playlist" && collection.subtitle.isNotBlank()) "By ${collection.subtitle}" else collection.subtitle,
                        if (collection.kind == "album") listOf("Album", collection.year, "${collection.tracks.size} tracks").filter { it.isNotBlank() }.joinToString(" · ") else "${collection.tracks.size} tracks",
                        onPlay = { music.playCollection(item) }, onShuffle = { music.playCollection(item, shuffle = true) },
                        onQueue = { music.queueCollection(item, next = false) },
                        options = listOf(ExtraSongOption(Icons.Default.CheckBoxOutlineBlank, "Select songs") { selection.active = true }))
                }
                if (collection.description.isNotBlank()) item { Text(collection.description, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium) }
                item { DownloadSelectionBar(selection, available, music.downloads::removeAll) }
                item { Text("Tracks", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge) }
                itemsIndexed(collection.tracks, key = { index, track -> "$index:${track.uuid}" }) { index, original ->
                    val song = local[original.ytVideoId]?.copy(uuid = original.uuid) ?: original
                    SwipeToAddNextContainer(onAddNext = { music.addNext(song) }, onAddToQueue = { music.addToQueue(listOf(song)) }) {
                        DownloadTrackRow(song, selection, onPlay = {
                            val position = if (original.ytVideoId in local) collection.tracks.take(index).count { it.ytVideoId in local } else -1
                            if (position >= 0) music.playDownloaded(available, position)
                            else com.example.juke.network.NetworkFeedback.notify("Download this song to play offline")
                        }, modifier = Modifier.padding(horizontal = 20.dp),
                            number = if (collection.kind == "album") index + 1 else null, sharpArtwork = false)
                    }
                }
            }
        }
        DetailBackButton(onBack, Modifier.align(Alignment.TopStart))
    }
}
