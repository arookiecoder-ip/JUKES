package com.example.juke.network

import org.junit.Assert.*
import org.junit.Test

class AlexaRemotePolicyTest {
    @Test fun defaultsToTheWebServerRatherThanPhoneStaging() {
        assertEquals("https://alexa.synthora.in/remote/", AlexaRemotePolicy.startUrl(AlexaRemotePolicy.DEFAULT_SERVER))
        assertEquals("https://example.com", AlexaRemotePolicy.server(" HTTPS://EXAMPLE.COM/ "))
    }

    @Test fun onlyTrustsTheExactHttpsOrigin() {
        val root = "https://alexa.synthora.in"
        assertTrue(AlexaRemotePolicy.trusted(root, "$root/library"))
        assertTrue(AlexaRemotePolicy.trusted(root, "https://alexa.synthora.in:443/login/"))
        for (url in listOf("http://alexa.synthora.in/", "https://alexa.synthora.in.evil.com/",
            "https://evil.com/?next=https://alexa.synthora.in", "https://user@alexa.synthora.in/",
            "https://alexa.synthora.in:8443/", "javascript:alert(1)", "file:///etc/passwd")) {
            assertFalse(url, AlexaRemotePolicy.trusted(root, url))
        }
        assertFalse(AlexaRemotePolicy.trusted(root, null))
    }

    @Test fun customServerPortIsRespected() {
        val root = "https://music.example.com:8443"
        assertTrue(AlexaRemotePolicy.trusted(root, "$root/api/library/"))
        assertFalse(AlexaRemotePolicy.trusted(root, "https://music.example.com/api/library/"))
    }

    @Test fun rejectsStagingPathsAndCredentialsInsteadOfOpeningTheWrongLibrary() {
        for (value in listOf("http://alexa.synthora.in", "https://alexa.synthora.in/staging",
            "https://alexa.synthora.in?key=secret", "https://user:password@alexa.synthora.in",
            "https://alexa.synthora.in/#library", "file:///tmp/remote", "https://example.com:99999")) {
            assertTrue(value, runCatching { AlexaRemotePolicy.server(value) }.isFailure)
        }
    }
}
