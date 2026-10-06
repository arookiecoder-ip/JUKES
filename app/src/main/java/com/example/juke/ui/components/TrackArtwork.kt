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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
    var exhausted by remember(source, track.ytVideoId) { mutableStateOf(false) }
    var retry by remember(source, track.ytVideoId) { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, source, track.ytVideoId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && exhausted) {
                candidate = 0; exhausted = false; retry++
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val online by com.example.juke.network.NetworkFeedback.online.collectAsState()
    LaunchedEffect(online) { if (online && exhausted) { candidate = 0; exhausted = false; retry++ } }
    LaunchedEffect(exhausted, online, retry) {
        if (exhausted && online && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            kotlinx.coroutines.delay(if (retry < 2) 3_000 else 30_000)
            candidate = 0; exhausted = false; retry++
        }
    }
    val url = candidates[candidate]
    key(track.ytVideoId ?: track.uuid, url, retry) {
        SubcomposeAsyncImage(ImageRequest.Builder(context).data(url)
            // Original decode dimensions let us reject YouTube's HTTP-200 placeholder images.
            .memoryCacheKey("hd:$url").diskCacheKey(url)
            .memoryCachePolicy(if (retry > 0) coil.request.CachePolicy.WRITE_ONLY else coil.request.CachePolicy.ENABLED)
            .diskCachePolicy(if (retry > 0) coil.request.CachePolicy.WRITE_ONLY else coil.request.CachePolicy.ENABLED)
            .size(Size.ORIGINAL).crossfade(false).build(),
            track.title, modifier, contentScale = ContentScale.Crop,
            loading = { Box(Modifier.fillMaxSize()) },
            error = {
                LaunchedEffect(url) { if (candidate < candidates.lastIndex) candidate++ else exhausted = true }
                Box(Modifier.fillMaxSize())
            },
            success = { success ->
                val image = success.result.drawable
                if (isHdArtwork(image.intrinsicWidth, image.intrinsicHeight)) SubcomposeAsyncImageContent()
                else {
                    LaunchedEffect(url) { if (candidate < candidates.lastIndex) candidate++ else exhausted = true }
                    Box(Modifier.fillMaxSize())
                }
            })
    }
}
