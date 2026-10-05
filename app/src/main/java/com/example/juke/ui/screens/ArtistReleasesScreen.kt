package com.example.juke.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.juke.network.*
import com.example.juke.ui.components.*
import com.example.juke.viewmodels.MusicViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

@Composable
fun ArtistReleasesScreen(artistId: String, kind: String, music: MusicViewModel,
    onBack: () -> Unit, onOpen: (BrowseItem) -> Unit, bottomPadding: Dp) {
    var releases by remember(artistId, kind) { mutableStateOf<List<BrowseItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var hasMore by remember { mutableStateOf(true) }
    var offset by remember { mutableLongStateOf(0) }
    var request by remember { mutableIntStateOf(0) }
    LaunchedEffect(artistId, kind, request) {
        loading = true; error = null
        try {
            val data = Backend.get("/api/artist/$artistId/releases", mapOf("kind" to kind, "offset" to offset.toString(), "limit" to "30")).objectOrEmpty()
            val page = data.array("items").mapNotNull { (it as? JsonObject)?.let { row -> BrowseParser.item(row, if (kind == "playlists") "" else "albums") } }.filter { it.id.isNotBlank() }
            releases = (releases + page).distinctBy { it.id }
            offset = data.number("next_offset")
            hasMore = data.flag("has_more") && page.isNotEmpty()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = networkErrorMessage(e) ?: e.message ?: "Couldn't load releases" }
        finally { loading = false }
    }
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 60.dp, bottom = bottomPadding + 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item(span = { GridItemSpan(2) }) { Text(kind.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.headlineMedium) }
            itemsIndexed(releases, key = { _, item -> item.id }) { index, item ->
                if (index >= releases.size - 4 && hasMore && !loading && error == null) LaunchedEffect(releases.size) { request++ }
                if (item.kind == "playlist") PlaylistCard(item, { onOpen(item) }, onPlay = { music.playCollection(item) })
                else AlbumCard(item, { onOpen(item) }, onPlay = { music.playCollection(item) })
            }
            if (loading) item(span = { GridItemSpan(2) }) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { message -> item(span = { GridItemSpan(2) }) { Column { Text(message, color = MaterialTheme.colorScheme.error); TextButton(onClick = { request++ }) { Text("Retry") } } } }
        }
        DetailBackButton(onBack, Modifier.align(Alignment.TopStart))
    }
}
