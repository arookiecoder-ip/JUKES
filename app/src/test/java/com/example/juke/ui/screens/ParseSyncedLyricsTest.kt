package com.example.juke.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class ParseSyncedLyricsTest {
    private val lrc = """
        [ar:Someone]
        [00:01.50] First line
        [00:05.5]Second
        [01:02.123]   Third  
        [00:09.00]
    """.trimIndent()

    @Test
    fun parsesTimesTextAndSkipsTagsAndBlankLines() {
        val lines = parseSyncedLyrics(lrc)
        assertEquals(listOf(1500L, 5500L, 62123L), lines.map { it.timeMs })
        assertEquals(listOf("First line", "Second", "Third"), lines.map { it.text })
    }

    @Test
    fun appliesOffsetAndNeverGoesNegative() {
        val lines = parseSyncedLyrics(lrc, offsetMs = -2000L)
        assertEquals(listOf(0L, 3500L, 60123L), lines.map { it.timeMs })
    }
}
