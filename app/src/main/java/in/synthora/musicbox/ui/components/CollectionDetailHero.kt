package `in`.synthora.musicbox.ui.components

import `in`.synthora.musicbox.ui.components.stableStatusBarsPadding

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
import `in`.synthora.musicbox.network.BrowseItem

/** One hero and action layout for account collections and their downloaded copies. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CollectionDetailHero(item: BrowseItem, title: String, image: String, credit: String,
    metadata: String, description: String = "", onCreditClick: (() -> Unit)? = null,
    onPlay: () -> Unit, onShuffle: () -> Unit, onQueue: () -> Unit, options: List<ExtraSongOption> = emptyList()) {
    DetailHero(image) {
        val paneHeight = LocalDetailPaneHeight.current
        val topInset = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding()
        val contentHeight = paneHeight?.let { (it - topInset).coerceAtLeast(0.dp) } ?: 0.dp
        BoxWithConstraints(Modifier.fillMaxWidth().stableStatusBarsPadding()) {
            val wide = paneHeight == null && maxWidth >= 840.dp
            @Composable fun labels() {
                Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                if (credit.isNotBlank()) Text(credit, style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable(enabled = onCreditClick != null) { onCreditClick?.invoke() })
                Text(metadata, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                if (description.isNotBlank()) Text(description, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
            }
            if (wide) Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 56.dp, bottom = 16.dp),
                verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                AsyncImage(image, title, Modifier.size(220.dp).clip(RoundedCornerShape(8.dp)).testTag("Collection artwork"), contentScale = ContentScale.Crop)
                Column(Modifier.weight(1f).testTag("Collection hero")) {
                    labels()
                    CollectionActions(item, onPlay, onShuffle, onQueue, options)
                }
            } else Column(Modifier.fillMaxWidth().heightIn(min = contentHeight)
                .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = if (paneHeight != null) 16.dp else 0.dp)
                .testTag("Collection hero"), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = if (paneHeight != null) Arrangement.Center else Arrangement.Top) {
                AsyncImage(image, title, Modifier.size(200.dp).clip(RoundedCornerShape(8.dp)).testTag("Collection artwork"), contentScale = ContentScale.Crop)
                Spacer(Modifier.height(12.dp))
                labels()
                CollectionActions(item, onPlay, onShuffle, onQueue, options)
            }
        }
    }
}
