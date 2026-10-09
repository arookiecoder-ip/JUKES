package `in`.synthora.musicbox.ui.components.player

import `in`.synthora.musicbox.ui.components.stableStatusBarsPadding

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Keep artwork beside controls on wide screens, and above them in portrait. */
@Composable
fun ResponsivePlayerLayout(artwork: @Composable () -> Unit, controls: @Composable () -> Unit, modifier: Modifier = Modifier, queue: (@Composable () -> Unit)? = null) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val paneWidth = maxWidth
        val paneHeight = maxHeight
        val wide = androidx.compose.ui.platform.LocalConfiguration.current.smallestScreenWidthDp >= 600 && paneWidth > paneHeight
        if (wide && queue != null) Row(Modifier.fillMaxSize().testTag("Landscape player")) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.weight(1f).fillMaxWidth()) { artwork() }
                Box(Modifier.heightIn(max = paneHeight * 0.52f).fillMaxWidth()) { controls() }
            }
            Box(Modifier.weight(1f).fillMaxHeight().stableStatusBarsPadding()) { queue() }
        } else Column(Modifier.fillMaxSize().testTag("Portrait player"), horizontalAlignment = Alignment.CenterHorizontally) {
            val fraction = if (paneWidth > paneHeight) 0.5f else 0.6f
            Box(Modifier.fillMaxWidth().height(paneHeight * fraction).testTag("Player artwork pane")) { artwork() }
            Box(Modifier.weight(1f).fillMaxWidth().testTag("Player controls pane"), contentAlignment = if (paneWidth > paneHeight) Alignment.BottomCenter else Alignment.TopCenter) {
                Box(Modifier.widthIn(max = 680.dp).fillMaxWidth()) { controls() }
            }
        }
    }
}
