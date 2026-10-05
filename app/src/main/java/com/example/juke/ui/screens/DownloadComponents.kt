package com.example.juke.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.juke.models.Track
import com.example.juke.ui.components.FlatTrackRow
import com.example.juke.ui.components.GlassAlertDialog
import com.example.juke.ui.components.LocalMediaMenu
import com.example.juke.ui.components.QueueSongActions

@Stable
class DownloadSelection {
    var active by mutableStateOf(false)
    var selected by mutableStateOf<Set<String>>(emptySet())
    var confirming by mutableStateOf(false)
    fun select(track: Track) { active = true; selected = selected + requireNotNull(track.ytVideoId) }
    fun toggle(track: Track) { track.ytVideoId?.let { selected = if (it in selected) selected - it else selected + it } }
    fun clear() { active = false; selected = emptySet(); confirming = false }
}

@Composable
fun DownloadSelectionBar(selection: DownloadSelection, tracks: List<Track>, onRemove: (List<Track>) -> Unit) {
    if (selection.active) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = selection::clear) { Icon(Icons.Default.Close, "Cancel selection") }
            Text("${selection.selected.size} selected", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { selection.selected = tracks.mapNotNull { it.ytVideoId }.toSet() }) { Icon(Icons.Default.SelectAll, "Select all downloads") }
            IconButton(enabled = selection.selected.isNotEmpty(), onClick = { selection.confirming = true }) { Icon(Icons.Default.Delete, "Delete selected downloads") }
        }
    }
    if (selection.confirming) GlassAlertDialog(onDismissRequest = { selection.confirming = false },
        title = { Text("Delete downloads?") }, text = { Text("Remove ${selection.selected.size} downloaded songs from this device?") },
        confirmButton = { TextButton(onClick = { onRemove(tracks.filter { it.ytVideoId in selection.selected }); selection.clear() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { selection.confirming = false }) { Text("Cancel") } })
}

@Composable
fun DownloadTrackRow(track: Track, selection: DownloadSelection, onPlay: () -> Unit,
    modifier: Modifier = Modifier, number: Int? = null) {
    val menu = LocalMediaMenu.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (selection.active) Checkbox(checked = track.ytVideoId in selection.selected, onCheckedChange = { selection.toggle(track) })
        FlatTrackRow(track.thumbnailUri, track.title, track.artist,
            if (track.durationSec > 0) "%d:%02d".format(track.durationSec / 60, track.durationSec % 60) else "",
            onClick = { if (selection.active) selection.toggle(track) else onPlay() }, modifier = Modifier.weight(1f),
            track = track, sharpArtwork = true, showMore = !selection.active, number = number,
            onOptions = { menu?.show(track, QueueSongActions(play = onPlay, select = { selection.select(track) })) })
    }
}
