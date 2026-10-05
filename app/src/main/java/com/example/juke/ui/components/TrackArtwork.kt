package com.example.juke.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import com.example.juke.models.Track
import com.example.juke.network.artworkCandidates

/** A song change creates a new painter; large art never reuses a tiny decoded thumbnail. */
@Composable
fun TrackArtwork(track: Track, modifier: Modifier = Modifier, large: Boolean = false) {
    val context = LocalContext.current
    val source = track.thumbnailUri.orEmpty()
    val candidates = remember(source, track.ytVideoId, large) { artworkCandidates(source, track.ytVideoId, large) }
    var candidate by remember(source, track.ytVideoId, large) { mutableIntStateOf(0) }
    val url = candidates[candidate]
    key(track.ytVideoId ?: track.uuid, url, large) {
        AsyncImage(ImageRequest.Builder(context).data(url)
            .memoryCacheKey("${if (large) "player" else "mini"}:$url")
            .diskCacheKey(url).size(if (large) 1200 else 160).precision(Precision.EXACT)
            .crossfade(false).build(), track.title, modifier, contentScale = ContentScale.Crop,
            onError = { if (candidate < candidates.lastIndex) candidate++ },
            onSuccess = { result ->
                // YouTube may return HTTP 200 with a tiny placeholder for missing max-res art.
                if (large && result.result.drawable.intrinsicWidth in 1..299 && candidate < candidates.lastIndex) candidate++
            })
    }
}
