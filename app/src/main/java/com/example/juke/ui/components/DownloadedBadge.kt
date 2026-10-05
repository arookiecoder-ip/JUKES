package com.example.juke.ui.components

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
import com.example.juke.services.DownloadRepository

@Composable
fun DownloadedBadge(videoId: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repository = remember { DownloadRepository.get(context) }
    val tracks by repository.tracks.collectAsStateWithLifecycle()
    val progress by repository.progress.collectAsStateWithLifecycle()
    if (videoId != null && videoId in progress) {
        val value = progress[videoId] ?: -1
        if (value >= 0) CircularProgressIndicator(progress = { value / 100f }, modifier = modifier.size(18.dp), strokeWidth = 2.dp)
        else CircularProgressIndicator(modifier.size(18.dp), strokeWidth = 2.dp)
    } else if (videoId != null && tracks.any { it.ytVideoId == videoId })
        Icon(Icons.Default.DownloadDone, "Downloaded", modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
}
