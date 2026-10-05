package com.example.juke.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import com.example.juke.models.Track
import com.example.juke.network.largeArtworkUrl

/** A song change creates a new painter; large art never reuses a tiny decoded thumbnail. */
@Composable
fun TrackArtwork(track: Track, modifier: Modifier = Modifier, large: Boolean = false) {
    val context = LocalContext.current
    val source = track.thumbnailUri.orEmpty()
    val upgraded = largeArtworkUrl(source)
    var fallback by remember(source) { mutableStateOf(false) }
    val url = if (fallback) source else upgraded
    key(track.ytVideoId ?: track.uuid, url, large) {
        AsyncImage(ImageRequest.Builder(context).data(url)
            .memoryCacheKey("${if (large) "player" else "mini"}:$url")
            .diskCacheKey(url).size(if (large) 1200 else 160).precision(Precision.EXACT)
            .crossfade(false).build(), track.title, modifier, contentScale = ContentScale.Crop,
            onError = { if (url != source) fallback = true })
    }
}
