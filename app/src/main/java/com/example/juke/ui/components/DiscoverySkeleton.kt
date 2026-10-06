package com.example.juke.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun DiscoverySkeleton(moods: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(if (moods) 6 else 8) {
            if (moods) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(2) { Box(Modifier.weight(1f).height(52.dp).shimmerEffect()) }
            } else Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(44.dp).shimmerEffect())
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.fillMaxWidth(0.7f).height(16.dp).shimmerEffect())
                    Box(Modifier.fillMaxWidth(0.45f).height(12.dp).shimmerEffect())
                }
                Box(Modifier.size(20.dp).shimmerEffect())
            }
        }
    }
}
