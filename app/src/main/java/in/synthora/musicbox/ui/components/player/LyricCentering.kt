package `in`.synthora.musicbox.ui.components.player

/** Center the whole active line, including wrapped lines and viewport insets. */
internal fun lyricCenterDelta(offset: Int, size: Int, viewportStart: Int, viewportEnd: Int): Float =
    offset + size / 2f - (viewportStart + viewportEnd) / 2f
