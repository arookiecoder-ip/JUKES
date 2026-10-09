package `in`.synthora.musicbox.ui.components.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.synthora.musicbox.ui.screens.LyricLine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
fun SyncedLyricsLines(lines: List<LyricLine>, position: Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val dragged by list.interactionSource.collectIsDraggedAsState()
    val active = remember(lines, position) { lines.indexOfLast { it.timeMs <= position }.coerceAtLeast(0) }
    val currentActive by rememberUpdatedState(active)
    suspend fun center(index: Int) {
        if (index !in lines.indices || dragged) return
        var layout = list.layoutInfo
        var item = layout.visibleItemsInfo.firstOrNull { it.index == index }
        if (item == null) {
            // Large seeks place the target near the center before measuring its real height.
            list.scrollToItem(index, -(layout.viewportEndOffset - layout.viewportStartOffset) / 2)
            withFrameNanos { }
            layout = list.layoutInfo
            item = layout.visibleItemsInfo.firstOrNull { it.index == index }
        }
        item?.let {
            list.animateScrollBy(lyricCenterDelta(it.offset, it.size,
                layout.viewportStartOffset, layout.viewportEndOffset))
        }
    }
    LaunchedEffect(lines) {
        snapshotFlow { Triple(currentActive, dragged, list.layoutInfo.viewportEndOffset - list.layoutInfo.viewportStartOffset) }.collectLatest { (index, dragging, _) ->
            if (!dragging) { delay(350); center(index) }
        }
    }
    BoxWithConstraints(modifier.testTag("Synced lyrics")) {
        LazyColumn(Modifier.fillMaxSize(), state = list,
            contentPadding = PaddingValues(vertical = maxHeight / 2), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            items(lines.size) { index ->
                Text(lines[index].text, Modifier.fillMaxWidth().testTag("Lyric line $index").clickable {
                    onSeek(lines[index].timeMs); scope.launch { center(index) }
                }, style = MaterialTheme.typography.headlineSmall.copy(fontSize = 28.sp, lineHeight = 35.sp),
                    color = if (index == active) Color.White else Color.White.copy(alpha = 0.42f), fontWeight = FontWeight.Bold)
            }
        }
    }
}
