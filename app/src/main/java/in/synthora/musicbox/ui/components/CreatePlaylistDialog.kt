package `in`.synthora.musicbox.ui.components

import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import `in`.synthora.musicbox.utils.rememberJukeHaptics

@Composable
fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    val haptic = rememberJukeHaptics()

    GlassAlertDialog(
        shape = androidx.compose.ui.graphics.RectangleShape,
        onDismissRequest = onDismiss,
        title = { Text("New Playlist") },
        text = {
            OutlinedTextField(
                shape = androidx.compose.ui.graphics.RectangleShape,
                value = name,
                onValueChange = { name = it },
                label = { Text("Playlist Name") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    haptic.heavyClick()
                    onCreate(name)
                },
                enabled = name.isNotBlank()
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = {
                haptic.click()
                onDismiss()
            }) {
                Text("Cancel")
            }
        }
    )
}
