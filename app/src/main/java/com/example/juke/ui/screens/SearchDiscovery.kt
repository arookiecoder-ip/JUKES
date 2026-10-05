package com.example.juke.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.juke.models.Track
import com.example.juke.network.*
import com.example.juke.ui.components.*
import com.example.juke.viewmodels.MusicViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

@Composable
fun SearchLandingContent(history: List<String>, recommendations: List<Track>, loading: Boolean,
    onSearch: (String) -> Unit, onRemove: (String) -> Unit, onDiscover: (String) -> Unit,
    onRetry: () -> Unit, music: MusicViewModel, bottomPadding: Dp) {
    LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp).let {
        PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = bottomPadding + 24.dp)
    }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (history.isNotEmpty()) item { Text("Recent searches", style = MaterialTheme.typography.titleMedium) }
        fun recent(queries: List<String>) {
            items(queries, key = { "history:$it" }) { query ->
                Row(Modifier.fillMaxWidth().clickable { onSearch(query) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.History, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(query, Modifier.weight(1f).padding(horizontal = 16.dp), maxLines = 1)
                    IconButton(onClick = { onRemove(query) }) { Icon(Icons.Default.Close, "Remove $query") }
                }
            }
        }
        recent(history.take(5))
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf("Moods & genres" to "moods", "New releases" to "new_releases").forEach { (title, mode) ->
                    OutlinedCard(onClick = { onDiscover(mode) }, shape = RectangleShape, modifier = Modifier.weight(1f)) {
                        Column(Modifier.padding(16.dp).heightIn(min = 72.dp), verticalArrangement = Arrangement.SpaceBetween) {
                            Icon(if (mode == "moods") Icons.Default.Explore else Icons.Default.NewReleases, null)
                            Spacer(Modifier.height(12.dp))
                            Text(title, style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
            }
        }
        recent(history.drop(5))
        if (history.isEmpty()) {
            item { Text("Recommended for you", style = MaterialTheme.typography.titleLarge) }
            if (loading && recommendations.isEmpty()) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            items(recommendations, key = { "recommendation:${it.ytVideoId}" }) { track ->
                SwipeToAddNextContainer(onAddNext = { music.addNext(track) }, onAddToQueue = { music.addToQueue(listOf(track)) }) {
                    FlatTrackRow(track.thumbnailUri, track.title, track.artist,
                        if (track.durationSec > 0) "%d:%02d".format(track.durationSec / 60, track.durationSec % 60) else "",
                        onClick = { music.playTrack(track) }, track = track)
                }
            }
            if (!loading && recommendations.isEmpty()) item { TextButton(onClick = onRetry) { Text("Retry recommendations") } }
        }
    }
}

@Composable
fun SearchDiscoveryScreen(mode: String, onBack: () -> Unit, music: MusicViewModel,
    onOpenAlbum: (BrowseItem) -> Unit, onOpenPlaylist: (BrowseItem) -> Unit, bottomPadding: Dp) {
    var selectedMood by remember(mode) { mutableStateOf<BrowseItem?>(null) }
    var page by remember(mode) { mutableStateOf<BrowsePage?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val title = selectedMood?.title ?: if (mode == "moods") "Moods & genres" else "New releases"
    fun back() { if (selectedMood != null) selectedMood = null else onBack() }
    BackHandler { back() }
    LaunchedEffect(mode, selectedMood, retry) {
        loading = true; error = null; page = null
        try {
            val mood = selectedMood
            val data = if (mood == null) Backend.get("/api/explore/").objectOrEmpty()
                else Backend.get("/api/explore/moods/", mapOf("params" to mood.raw.text("params"), "title" to mood.title)).objectOrEmpty()
            val filtered = if (mood != null) JsonObject(data.filterKeys { it != "playlists" }) else
                JsonObject(data.filterKeys { it == if (mode == "moods") "moods_and_genres" else "new_releases" })
            page = BrowseParser.page(filtered, title)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = networkErrorMessage(e) ?: e.message ?: "Couldn't load $title" }
        finally { loading = false }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 60.dp, bottom = bottomPadding + 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(title, style = MaterialTheme.typography.headlineMedium) }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error); TextButton(onClick = { retry++ }) { Text("Retry") } } }
            page?.shelves?.forEach { shelf ->
                if (selectedMood != null) item { Text(shelf.title, style = MaterialTheme.typography.titleMedium) }
                items(shelf.items.chunked(2)) { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        pair.forEach { item ->
                            when (item.kind) {
                                "mood" -> OutlinedCard(onClick = { selectedMood = item }, modifier = Modifier.weight(1f), shape = RectangleShape) { Text(item.title, Modifier.padding(20.dp)) }
                                "album" -> AlbumCard(item, { onOpenAlbum(item) }, onPlay = { music.playCollection(item) })
                                "track" -> Column(Modifier.weight(1f)) { FlatTrackRow(item.image, item.title, item.subtitle, "", { music.playTrack(item.toTrack()) }, track = item.toTrack()) }
                                else -> PlaylistCard(item, { onOpenPlaylist(item) }, onPlay = { music.playCollection(item) })
                            }
                        }
                    }
                }
            }
            if (!loading && error == null && page?.shelves.isNullOrEmpty()) item { Text("No items available yet.") }
        }
        DetailBackButton(::back, Modifier.align(Alignment.TopStart))
    }
}
