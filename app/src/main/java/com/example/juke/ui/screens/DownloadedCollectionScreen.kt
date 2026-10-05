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
    val selection = remember(collectionKey) { DownloadSelection() }
    Box(Modifier.fillMaxSize()) {
        if (collection == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("This download was removed") }
        else {
            val available = remember(collection, files) { collection.available(files) }
            val local = available.associateBy { it.ytVideoId }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding + 24.dp)) {
                item {
                    DetailHero(collection.image) {
                        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 52.dp, start = 20.dp, end = 20.dp, bottom = 16.dp)) {
                            coil.compose.AsyncImage(collection.image, collection.title, Modifier.size(180.dp).align(Alignment.CenterHorizontally), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                            Spacer(Modifier.height(16.dp))
                            Text(collection.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text("Downloaded ${collection.kind} · ${available.size}/${collection.tracks.size} songs", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                FilledIconButton(enabled = available.isNotEmpty(), onClick = { music.playDownloaded(available, 0) }) { Icon(Icons.Default.PlayArrow, "Play downloaded collection") }
                                FilledTonalIconButton(enabled = available.isNotEmpty(), onClick = { music.playDownloaded(available.shuffled(), 0) }) { Icon(Icons.Default.Shuffle, "Shuffle downloaded collection") }
                                IconButton(onClick = { music.downloads.retryCollection(collection) }) { Icon(Icons.Default.Download, "Download missing songs") }
                                IconButton(onClick = { selection.active = true }) { Icon(Icons.Default.CheckBoxOutlineBlank, "Select collection songs") }
                            }
                        }
                    }
                }
                item { DownloadSelectionBar(selection, available, music.downloads::removeAll) }
                itemsIndexed(collection.tracks, key = { index, track -> "$index:${track.uuid}" }) { index, original ->
                    val song = local[original.ytVideoId]?.copy(uuid = original.uuid) ?: original
                    if (original.ytVideoId in local) DownloadTrackRow(song, selection,
                        onPlay = { music.playDownloaded(available, available.indexOfFirst { it.uuid == original.uuid }.coerceAtLeast(0)) },
                        modifier = Modifier.padding(horizontal = 16.dp), number = if (collection.kind == "album") index + 1 else null)
                    else Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(original.title, Modifier.weight(1f), maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        DownloadedBadge(original.ytVideoId)
                        IconButton(onClick = { music.download(original) }) { Icon(Icons.Default.Download, "Download ${original.title}") }
                    }
                }
            }
        }
        DetailBackButton(onBack, Modifier.align(Alignment.TopStart))
    }
}
