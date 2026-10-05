package com.example.juke.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import coil.size.Size
import com.example.juke.models.Track
import com.example.juke.network.artworkCandidates
import com.example.juke.network.isHdArtwork

/** A new song stays blank until genuine HD artwork is decoded; thumbnails are never stretched. */
@Composable
fun TrackArtwork(track: Track, modifier: Modifier = Modifier, large: Boolean = false) {
    val context = LocalContext.current
    val source = track.thumbnailUri.orEmpty()
    val candidates = remember(source, track.ytVideoId) { artworkCandidates(source, track.ytVideoId, true) }
    var candidate by remember(source, track.ytVideoId) { mutableIntStateOf(0) }
    val url = candidates[candidate]
    key(track.ytVideoId ?: track.uuid, url) {
        SubcomposeAsyncImage(ImageRequest.Builder(context).data(url)
            // Original decode dimensions let us reject YouTube's HTTP-200 placeholder images.
            .memoryCacheKey("hd:$url").diskCacheKey(url).size(Size.ORIGINAL).crossfade(false).build(),
            track.title, modifier, contentScale = ContentScale.Crop,
            loading = { Box(Modifier.fillMaxSize()) },
            error = {
                LaunchedEffect(url) { if (candidate < candidates.lastIndex) candidate++ }
                Box(Modifier.fillMaxSize())
            },
            success = { success ->
                val image = success.result.drawable
                if (isHdArtwork(image.intrinsicWidth, image.intrinsicHeight)) SubcomposeAsyncImageContent()
                else {
                    LaunchedEffect(url) { if (candidate < candidates.lastIndex) candidate++ }
                    Box(Modifier.fillMaxSize())
                }
            })
    }
}
