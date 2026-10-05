package com.example.juke.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage

/** Flat, dense track row shared by the album / artist / playlist detail screens (matches Library). */
@Composable
fun FlatTrackRow(
    imageUrl: String?,
    title: String,
    subtitle: String,
    duration: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    track: com.example.juke.models.Track? = null,
    collection: com.example.juke.network.BrowseItem? = null,
    sharpArtwork: Boolean = false,
    showMore: Boolean = false,
    number: Int? = null,
    onOptions: (() -> Unit)? = null
) {
    val menu = LocalMediaMenu.current
    val context = LocalContext.current
    val downloads = remember { com.example.juke.services.DownloadRepository.get(context) }
    val downloaded by downloads.tracks.collectAsStateWithLifecycle()
    val options: () -> Unit = onOptions ?: { track?.let { menu?.show(it) } ?: collection?.let { menu?.show(it) }; Unit }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .combinedClickable(onClick = onClick, onLongClick = options, onLongClickLabel = "Song options")
                .heightIn(min = 48.dp)
                .padding(horizontal = 4.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (number != null) Text(number.toString(), Modifier.width(32.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else {
            AsyncImage(
                model = imageUrl ?: "",
                contentDescription = null,
                modifier = Modifier
                    .size(44.dp)
                    .clip(if (sharpArtwork) androidx.compose.ui.graphics.RectangleShape else RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                contentScale = ContentScale.Crop
            )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            DownloadedBadge(track?.ytVideoId)
            Spacer(Modifier.width(8.dp))
            Text(
                duration,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.width(48.dp).padding(end = 8.dp)
            )
            if (showMore) androidx.compose.material3.IconButton(onClick = options) {
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.MoreVert, "More options")
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = if (number != null) 48.dp else 60.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        )
    }
}
