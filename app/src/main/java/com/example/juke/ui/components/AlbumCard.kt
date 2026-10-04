package com.example.juke.ui.components

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
    modifier: Modifier = Modifier
) {
    val haptic = rememberJukeHaptics()
    val menu = LocalMediaMenu.current
    Column(
        modifier = modifier
            .width(148.dp)
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = { haptic.click(); onClick() }, onLongClick = { haptic.heavyClick(); menu?.show(album) }, onLongClickLabel = "Collection options")
            .padding(bottom = 4.dp)
    ) {
        AsyncImage(
            model = album.image,
            contentDescription = album.title,
            modifier = Modifier
                .size(148.dp)
                .clip(RoundedCornerShape(14.dp)),
            contentScale = ContentScale.Crop
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = album.title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            minLines = 2,
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
