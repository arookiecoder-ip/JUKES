package com.example.juke.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.juke.models.Track
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Keep blank until the shared resolver verifies HD; retry on resume and reconnect. */
@Composable
fun TrackArtwork(track: Track, modifier: Modifier = Modifier, large: Boolean = false) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val online by com.example.juke.network.NetworkFeedback.online.collectAsState()
    val bitmap by produceState<android.graphics.Bitmap?>(null, track.ytVideoId, track.thumbnailUri, online) {
        value = null
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive && value == null) {
                value = com.example.juke.network.ArtworkRepository.load(context, track.thumbnailUri.orEmpty(), track.ytVideoId)
                if (value == null) delay(if (online) 3_000 else 30_000)
            }
        }
    }
    val image = bitmap
    if (image != null) Image(image.asImageBitmap(), track.title, modifier, contentScale = ContentScale.Crop)
    else Box(modifier) { Box(Modifier.fillMaxSize()) }
}
