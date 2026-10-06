package com.example.juke.ui.components.player

import com.example.juke.ui.components.stableStatusBarsPadding

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Keep artwork beside controls on wide screens, and above them in portrait. */
@Composable
fun ResponsivePlayerLayout(artwork: @Composable () -> Unit, controls: @Composable () -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val paneWidth = maxWidth
        val paneHeight = maxHeight
        val wide = paneWidth >= 600.dp && paneWidth > paneHeight
        if (wide) Row(Modifier.fillMaxSize().testTag("Landscape player")) {
            Box(Modifier.weight(1f).fillMaxHeight().testTag("Player artwork pane")) { artwork() }
            Box(Modifier.weight(0.9f).fillMaxHeight().stableStatusBarsPadding().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.widthIn(max = 640.dp).fillMaxSize().testTag("Player controls pane")) { controls() }
            }
        } else Column(Modifier.fillMaxSize().testTag("Portrait player"), horizontalAlignment = Alignment.CenterHorizontally) {
            val fraction = if (paneWidth >= 600.dp) 0.55f else 0.6f
            Box(Modifier.fillMaxWidth().height(paneHeight * fraction).testTag("Player artwork pane")) { artwork() }
            Box(Modifier.weight(1f).widthIn(max = 680.dp).fillMaxWidth().testTag("Player controls pane")) { controls() }
        }
    }
}
