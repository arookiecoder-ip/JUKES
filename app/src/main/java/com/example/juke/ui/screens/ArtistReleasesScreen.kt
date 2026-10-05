package com.example.juke.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.juke.network.*
import com.example.juke.ui.components.*
import com.example.juke.viewmodels.ArtistReleasesViewModel
import com.example.juke.viewmodels.MusicViewModel
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun ArtistReleasesScreen(artistId: String, kind: String, music: MusicViewModel,
    onBack: () -> Unit, onOpen: (BrowseItem) -> Unit, bottomPadding: Dp,
    releases: ArtistReleasesViewModel = viewModel()) {
    val state by releases.state.collectAsStateWithLifecycle()
    val grid = rememberLazyGridState()
    LaunchedEffect(artistId, kind) { releases.open(artistId, kind) }
    LaunchedEffect(grid, state.items.size, state.loading, state.error) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged().collect { last ->
                if (state.items.isNotEmpty() && last >= state.items.size - 4 && !state.loading && state.error == null)
                    releases.loadMore()
            }
    }
    Box(Modifier.fillMaxSize()) {
        if (state.error != null && state.items.isEmpty()) ConnectionErrorState(state.error.orEmpty(), releases::loadMore,
            Modifier.fillMaxSize().padding(bottom = bottomPadding))
        else LazyVerticalGrid(GridCells.Adaptive(148.dp), Modifier.fillMaxSize().statusBarsPadding(), state = grid,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 60.dp, bottom = bottomPadding + 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) { Text(kind.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.headlineMedium) }
            items(state.items, key = { it.id }) { item ->
                if (item.kind == "playlist") PlaylistCard(item, { onOpen(item) }, onPlay = { music.playCollection(item) }, fillCell = true)
                else AlbumCard(item, { onOpen(item) }, onPlay = { music.playCollection(item) }, fillCell = true)
            }
            if (state.loading && state.items.isEmpty()) items(8) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).shimmerEffect())
                    Box(Modifier.fillMaxWidth(0.8f).height(16.dp).shimmerEffect())
                }
            } else if (state.loading) item(span = { GridItemSpan(maxLineSpan) }) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let { message -> item(span = { GridItemSpan(maxLineSpan) }) {
                ConnectionErrorState(message, releases::loadMore, Modifier.fillMaxWidth())
            } }
            if (!state.loading && state.error == null && state.items.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text("No releases available yet") }
        }
        DetailBackButton(onBack, Modifier.align(Alignment.TopStart))
    }
}
