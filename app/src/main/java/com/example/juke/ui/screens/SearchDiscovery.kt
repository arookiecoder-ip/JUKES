package com.example.juke.ui.screens

import com.example.juke.ui.components.stableStatusBarsPadding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import kotlinx.serialization.json.Json
import com.example.juke.services.DiscoveryCache
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
    }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (history.isNotEmpty()) item { Text("Recent searches", style = MaterialTheme.typography.titleMedium) }
        fun recent(queries: List<String>) {
            items(queries, key = { "history:$it" }) { query ->
                Row(Modifier.fillMaxWidth().clickable { onSearch(query) }.padding(vertical = 0.dp), verticalAlignment = Alignment.CenterVertically) {
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
        if (history.size <= 5) {
            item { Text("Recommended for you", style = MaterialTheme.typography.titleLarge) }
            if (loading && recommendations.isEmpty()) item { TrackRowsSkeleton() }
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
    onOpenAlbum: (BrowseItem) -> Unit, onOpenPlaylist: (BrowseItem) -> Unit, bottomPadding: Dp,
    session: com.example.juke.viewmodels.DiscoverySessionViewModel) {
    val context = LocalContext.current
    val cache = remember { DiscoveryCache(context) }
    var selectedMoodJson by rememberSaveable(mode) { mutableStateOf<String?>(null) }
    val selectedMood = remember(selectedMoodJson) {
        selectedMoodJson?.let { runCatching { BrowseParser.item(Json.parseToJsonElement(it) as JsonObject) }.getOrNull() }
    }
    var page by remember(mode) { mutableStateOf(session.get("$mode:${selectedMood?.raw?.text("params").orEmpty()}")
        ?.let { BrowseParser.page(it, selectedMood?.title ?: if (mode == "moods") "Moods & genres" else "New releases") }) }
    var loading by remember { mutableStateOf(page == null) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val title = selectedMood?.title ?: if (mode == "moods") "Moods & genres" else "New releases"
    fun back() { if (selectedMood != null) selectedMoodJson = null else onBack() }
    BackHandler { back() }
    LaunchedEffect(mode, selectedMood, retry) {
        val key = "$mode:${selectedMood?.raw?.text("params").orEmpty()}"
        val retained = session.get(key)
        val cached = retained ?: if (mode == "moods" && selectedMood == null) cache.moods() else null
        page = cached?.let { BrowseParser.page(it, title) }
        loading = page == null; error = null
        if (cached != null && retry == 0 && (retained != null || cache.fresh())) return@LaunchedEffect
        try {
            val mood = selectedMood
            val data = if (mood == null) Backend.get("/api/explore/").objectOrEmpty()
                else Backend.get("/api/explore/moods/", mapOf("params" to mood.raw.text("params"), "title" to mood.title)).objectOrEmpty()
            if (mood == null) cache.save(data)
            val filtered = if (mood != null) JsonObject(data.filterKeys { it != "playlists" || data.array("featured_playlists").isEmpty() }) else
                JsonObject(data.filterKeys { it == if (mode == "moods") "moods_and_genres" else "new_releases" })
            session.put(key, filtered)
            page = BrowseParser.page(filtered, title)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = networkErrorMessage(e) ?: e.message ?: "Couldn't load $title" }
        finally { loading = false }
    }
    Box(Modifier.fillMaxSize()) {
        if (error != null) ConnectionErrorState(error.orEmpty(), { retry++ },
            Modifier.fillMaxSize().padding(bottom = bottomPadding))
        else LazyColumn(Modifier.fillMaxSize().stableStatusBarsPadding(), contentPadding = PaddingValues(top = 60.dp, bottom = bottomPadding + 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(title, Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.headlineMedium) }
            if (loading && page == null) item { Box(Modifier.padding(horizontal = 20.dp)) { DiscoverySkeleton(mode == "moods" && selectedMood == null) } }
            page?.shelves?.forEach { shelf ->
                if (selectedMood != null) item { Text(shelf.title, Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleMedium) }
                if (mode == "new_releases" || shelf.items.all { it.kind == "track" }) {
                    items(shelf.items, key = { it.id }) { item ->
                        val track = item.takeIf { it.kind == "track" }?.toTrack()
                        FlatTrackRow(item.image, item.title, item.subtitle,
                            if (item.durationMs > 0) "%d:%02d".format(item.durationMs / 60000, item.durationMs / 1000 % 60) else "",
                            onClick = { when (item.kind) {
                                "track" -> music.playTrack(requireNotNull(track))
                                "album" -> onOpenAlbum(item)
                                else -> onOpenPlaylist(item)
                            } }, track = track, collection = item.takeIf { it.kind != "track" }, modifier = Modifier.padding(horizontal = 20.dp), sharpArtwork = true, showMore = mode == "new_releases")
                    }
                } else if (shelf.items.all { it.kind == "mood" }) {
                    items(shelf.items.chunked(2)) { pair ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            pair.forEach { item ->
                                val accents = listOf(0xFFFF8C3A, 0xFFE80000, 0xFF8A3FFC, 0xFFFFE264, 0xFF00A928, 0xFF00A9D7)
                                val accent = Color(accents[shelf.items.indexOf(item) % accents.size])
                                Row(Modifier.weight(1f).heightIn(min = 52.dp).background(Color(0xFF2B2B2B))
                                    .clickable { selectedMoodJson = item.raw.toString() }, verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.width(6.dp).height(52.dp).background(accent))
                                    Text(item.title, Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                } else {
                    item {
                        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(shelf.items, key = { it.id }) { item ->
                                if (item.kind == "album") AlbumCard(item, { onOpenAlbum(item) }, onPlay = { music.playCollection(item) }, artworkSize = 148.dp)
                                else PlaylistCard(item, { onOpenPlaylist(item) }, onPlay = { music.playCollection(item) }, artworkSize = 148.dp)
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
