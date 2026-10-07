package com.example.juke.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import com.example.juke.network.rowArtworkUrl

/** Small rows never fetch/decode full hero artwork or animate each newly visible cover. */
@Composable
fun ListArtwork(url: String?, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val pixels = with(LocalDensity.current) { size.roundToPx() }.coerceAtLeast(1)
    val request = remember(url, pixels, context) {
        ImageRequest.Builder(context).data(rowArtworkUrl(url.orEmpty(), pixels))
            .size(pixels).precision(Precision.INEXACT).crossfade(false).build()
    }
    AsyncImage(request, null, modifier, contentScale = ContentScale.Crop)
}
