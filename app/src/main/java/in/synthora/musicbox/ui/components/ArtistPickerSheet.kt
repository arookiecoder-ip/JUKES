package `in`.synthora.musicbox.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import `in`.synthora.musicbox.models.ArtistCredit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistPickerSheet(artists: List<ArtistCredit>, onDismiss: () -> Unit, onArtist: (ArtistCredit) -> Unit) {
    ModalBottomSheet(containerColor = `in`.synthora.musicbox.ui.theme.glassSheetColor(), onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Artists", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close artists") }
            }
            artists.forEach { artist -> MusicMenuOption(Icons.Default.Person, artist.name) { onArtist(artist) } }
        }
    }
}
