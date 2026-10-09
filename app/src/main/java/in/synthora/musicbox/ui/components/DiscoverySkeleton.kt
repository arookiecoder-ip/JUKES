package `in`.synthora.musicbox.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun DiscoverySkeleton(moods: Boolean, collections: Boolean = false) {
    if (collections) {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            repeat(2) {
                Box(Modifier.fillMaxWidth(0.4f).height(20.dp).shimmerEffect())
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(5) { Column(Modifier.width(148.dp)) { MediaCardSkeleton() } }
                }
            }
            TrackRowsSkeleton(3)
        }
        return
    }
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
