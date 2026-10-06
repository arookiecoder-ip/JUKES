package com.example.juke.ui.screens

import com.example.juke.ui.components.stableStatusBarsPadding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.juke.models.Track
import com.example.juke.network.*
import com.example.juke.ui.components.*
import com.example.juke.viewmodels.MusicViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

@Composable
fun HistoryScreen(music: MusicViewModel, onBack: () -> Unit, bottomPadding: Dp) {
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(retry) {
        loading = true; error = null
        try {
            tracks = (Backend.get("/history/") as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.let(BrowseParser::item) }
                .filter { it.videoId.isNotBlank() }.map { it.toTrack() }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = networkErrorMessage(e) ?: e.message ?: "Couldn't load history" }
        finally { loading = false }
    }
    Box(Modifier.fillMaxSize()) {
        if (error != null) ConnectionErrorState(error.orEmpty(), { retry++ },
            Modifier.fillMaxSize().padding(bottom = bottomPadding))
        else LazyColumn(Modifier.fillMaxSize().stableStatusBarsPadding(), contentPadding = PaddingValues(top = 60.dp, bottom = bottomPadding + 16.dp)) {
            item { Text("History", Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.headlineMedium) }
            if (loading && tracks.isEmpty()) item { Box(Modifier.padding(horizontal = 20.dp)) { DiscoverySkeleton(moods = false) } }
            itemsIndexed(tracks, key = { index, track -> "$index:${track.ytVideoId}" }) { index, track ->
                SwipeToAddNextContainer(onAddNext = { music.addNext(track) }, onAddToQueue = { music.addToQueue(listOf(track)) }) {
                    FlatTrackRow(track.thumbnailUri, track.title, track.artist,
                        if (track.durationSec > 0) "%d:%02d".format(track.durationSec / 60, track.durationSec % 60) else "",
                        onClick = { music.setQueue(tracks, index) }, modifier = Modifier.padding(horizontal = 20.dp), track = track)
                }
            }
            if (!loading && error == null && tracks.isEmpty()) item { Text("Nothing played yet", Modifier.padding(20.dp)) }
        }
        DetailBackButton(onBack, Modifier.align(Alignment.TopStart))
    }
}
