package com.example.juke.ui.components

import com.example.juke.ui.components.stableStatusBarsPadding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.juke.network.BrowseItem

/** One hero and action layout for account collections and their downloaded copies. */
@Composable
fun CollectionDetailHero(item: BrowseItem, title: String, image: String, credit: String,
    metadata: String, description: String = "", onCreditClick: (() -> Unit)? = null,
    onPlay: () -> Unit, onShuffle: () -> Unit, onQueue: () -> Unit, options: List<ExtraSongOption> = emptyList()) {
    DetailHero(image) {
        Column(Modifier.fillMaxWidth().stableStatusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 16.dp)
            .testTag("Collection hero"), horizontalAlignment = Alignment.CenterHorizontally) {
            AsyncImage(image, title, Modifier.size(200.dp).clip(RoundedCornerShape(8.dp)).testTag("Collection artwork"), contentScale = ContentScale.Crop)
            Spacer(Modifier.height(12.dp))
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            if (credit.isNotBlank()) Text(credit, style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(enabled = onCreditClick != null) { onCreditClick?.invoke() })
            Text(metadata, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            if (description.isNotBlank()) Text(description, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
        }
        CollectionActions(item, onPlay, onShuffle, onQueue, options)
    }
}
