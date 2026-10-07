package com.example.juke.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun AdaptiveDetailLayout(bottomPadding: Dp, spacing: Dp = 0.dp,
    hero: @Composable () -> Unit, content: LazyListScope.() -> Unit) {
    val config = LocalConfiguration.current
    val landscape = config.smallestScreenWidthDp >= 600 && config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    if (landscape) Row(Modifier.fillMaxSize().padding(bottom = bottomPadding)) {
        BoxWithConstraints(Modifier.weight(0.85f).fillMaxHeight().clipToBounds()) {
            val paneHeight = maxHeight
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                CompositionLocalProvider(LocalDetailPaneHeight provides paneHeight) { hero() }
            }
        }
        LazyColumn(Modifier.weight(1.15f).fillMaxHeight().stableStatusBarsPadding(),
            contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(spacing), content = content)
    } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding + 16.dp),
        verticalArrangement = Arrangement.spacedBy(spacing)) {
        item(key = "hero") { hero() }
        content()
    }
}
