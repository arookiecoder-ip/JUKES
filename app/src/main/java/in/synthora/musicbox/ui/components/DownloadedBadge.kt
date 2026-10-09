package `in`.synthora.musicbox.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import `in`.synthora.musicbox.services.DownloadRepository

@Composable
fun DownloadedBadge(videoId: String?, modifier: Modifier = Modifier) {
    // Album/artist cards have no song id. They cannot show this badge and
    // should not attach two download-flow collectors while scrolling.
    if (videoId.isNullOrBlank()) return
    val context = LocalContext.current
    val repository = remember { DownloadRepository.get(context) }
    val downloadedFlow = remember(repository, videoId) { repository.downloadedIds.map { ids -> videoId != null && videoId in ids }.distinctUntilChanged() }
    val progressFlow = remember(repository, videoId) { repository.progress.map { progress -> videoId?.let { progress[it] } }.distinctUntilChanged() }
    val downloaded by downloadedFlow.collectAsStateWithLifecycle(initialValue = false)
    val value by progressFlow.collectAsStateWithLifecycle(initialValue = null)
    if (value != null) {
        if (value!! >= 0) CircularProgressIndicator(progress = { value!! / 100f }, modifier = modifier.size(18.dp), strokeWidth = 2.dp)
        else CircularProgressIndicator(modifier.size(18.dp), strokeWidth = 2.dp)
    } else if (downloaded)
        Icon(Icons.Default.DownloadDone, "Downloaded", modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
}
