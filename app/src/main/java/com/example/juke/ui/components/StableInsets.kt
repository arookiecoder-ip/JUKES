package com.example.juke.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed

fun Modifier.stableStatusBarsPadding(): Modifier = composed {
    windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
}

fun Modifier.stableNavigationBarsPadding(): Modifier = composed {
    windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
}
