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
}
