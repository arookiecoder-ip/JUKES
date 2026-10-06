package com.example.juke.network

import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class DeviceAudioTest {
    @Test fun ordinaryPlaybackRequestsSeekableAudioWithoutPuttingCredentialsInUrl() {
        val uri = URI(deviceAudioUrl("https://audio.example.com/", "abcdefghijk"))
        assertEquals("/audio/", uri.path)
        assertEquals("video_id=abcdefghijk&wait=1", uri.query)
        assertNull(uri.userInfo)
        assertFalse(uri.query.contains("key="))
    }
    @Test fun queueIdsPreserveUnderscoresAndHyphens() {
        val uri = URI(deviceAudioUrl("https://audio.example.com", "a_-defghijk"))
        assertTrue(uri.query.contains("video_id=a_-defghijk"))
    }
    @Test fun malformedIdsCannotChangeAudioRequestOptions() {
        try {
            deviceAudioUrl("https://audio.example.com", "abc&wait=0")
            fail("Invalid video id should be rejected")
        } catch (_: IllegalArgumentException) { }
    }
    @Test fun authenticationFollowsConfiguredOriginAndPathPrefix() {
        val base = "https://audio.example.com/music/"
        assertTrue(isBackendAudioRequest(base, deviceAudioUrl(base, "abcdefghijk")))
        assertTrue(isBackendAudioRequest(base, "https://AUDIO.example.com:443/music/audio/?video_id=abcdefghijk&wait=1"))
        assertFalse(isBackendAudioRequest(base, "https://other.example.com/music/audio/?video_id=abcdefghijk"))
        assertFalse(isBackendAudioRequest(base, "http://audio.example.com/music/audio/"))
        assertFalse(isBackendAudioRequest(base, "https://audio.example.com:444/music/audio/"))
        assertFalse(isBackendAudioRequest(base, "https://audio.example.com/audio/"))
        assertFalse(isBackendAudioRequest(base, "https://audio.example.com/music/audio/unrelated"))
    }
}
