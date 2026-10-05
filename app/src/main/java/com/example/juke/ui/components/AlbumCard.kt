package com.example.juke.ui.components

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import com.example.juke.ui.theme.GlassCard
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.juke.network.BrowseItem
import com.example.juke.utils.rememberJukeHaptics

@Composable
fun AlbumCard(
    album: BrowseItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onPlay: (() -> Unit)? = null,
    artworkSize: androidx.compose.ui.unit.Dp = 148.dp,
    fillCell: Boolean = false
) {
    val haptic = rememberJukeHaptics()
    val menu = LocalMediaMenu.current
    Column(
        modifier = modifier
            .then(if (fillCell) Modifier.fillMaxWidth() else Modifier.width(artworkSize))
            .clip(androidx.compose.ui.graphics.RectangleShape)
            .combinedClickable(onClick = { haptic.click(); onClick() }, onLongClick = { haptic.heavyClick(); menu?.show(album) }, onLongClickLabel = "Collection options")
            .padding(bottom = 4.dp)
    ) {
        androidx.compose.foundation.layout.Box(if (fillCell) Modifier.fillMaxWidth().aspectRatio(1f) else Modifier.size(artworkSize)) {
        AsyncImage(
            model = album.image,
            contentDescription = album.title,
            modifier = Modifier
                .matchParentSize()
                .clip(androidx.compose.ui.graphics.RectangleShape),
            contentScale = ContentScale.Crop
        )
            onPlay?.let { play -> CollectionPlayButton("Play ${album.title}", play,
                Modifier.align(Alignment.BottomEnd).padding(8.dp)) }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = album.title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = album.subtitle.ifBlank { "Album" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
