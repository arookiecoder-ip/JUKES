package com.example.juke.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun CollectionPlayButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledIconButton(onClick = onClick, modifier = modifier.size(40.dp),
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White.copy(alpha = 0.24f))) {
        Icon(Icons.Filled.PlayArrow, label, Modifier.size(26.dp), tint = Color.White)
    }
}
