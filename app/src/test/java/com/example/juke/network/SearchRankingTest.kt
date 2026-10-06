package com.example.juke.network

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class SearchRankingTest {
    private fun item(id: String, title: String, type: String, videoType: String = "") = BrowseParser.item(buildJsonObject {
        put("videoId", id); put("title", title); put("artist", "Singer"); put("resultType", type); put("videoType", videoType)
    })
    @Test fun audioCounterpartReplacesVideoTopResult() {
        val video = item("video", "My Song (Official Video)", "video")
        val song = item("audio", "My Song", "song")
        assertEquals(song, audioSearchHero(listOf(video, song), listOf(song)))
    }
    @Test fun officialVideoIsNotAudioEvenWithoutResultType() {
        assertFalse(isAudioSearchItem(item("video", "Song", "track", "MUSIC_VIDEO_TYPE_OMV")))
        assertTrue(isAudioSearchItem(item("audio", "Song", "video", "MUSIC_VIDEO_TYPE_ATV")))
    }
    @Test fun unicodeTitlesMatchWithoutCollapsingDifferentSongs() {
        val video = item("video", "আমার গান (Official)", "video")
        val other = item("other", "অন্য গান", "song")
        val song = item("audio", "আমার গান", "song")
        assertEquals(song, audioSearchHero(listOf(video), listOf(other, song)))
    }
    @Test fun missingSongArrayStillShowsMixedTracksWithoutDroppingVideos() {
        val video = item("video", "Song", "video")
        val audio = item("audio", "Song", "song")
        assertEquals(listOf(audio, video), searchSongItems(listOf(video, audio), emptyList()))
        assertEquals(listOf(video), searchSongItems(listOf(video), emptyList()))
        assertEquals(listOf(audio, video), searchSongItems(listOf(video, audio), listOf(audio)))
    }
}
