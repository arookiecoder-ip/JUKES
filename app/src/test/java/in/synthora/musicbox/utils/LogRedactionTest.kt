package `in`.synthora.musicbox.utils

import org.junit.Assert.*
import org.junit.Test

class LogRedactionTest {
    @Test fun headersAndUrlCredentialsAreHiddenWithoutLosingDiagnostics() {
        val text = "GET https://host/audio/?video_id=abc&key=secret&wait=1\nX-Api-Key: secret\nAuthorization: Bearer other\nCookie: session=private\nCaused by: timeout"
        val safe = redactSecrets(text)
        for (secret in listOf("secret", "other", "private")) assertFalse(safe.contains(secret))
        assertTrue(safe.contains("video_id=abc")); assertTrue(safe.contains("wait=1")); assertTrue(safe.contains("timeout"))
    }
    @Test fun knownKeyIsHiddenInNestedErrorsAndEncodedValues() {
        val key = "abc+/XYZ"
        val safe = redactSecrets("Cause: $key encoded=${java.net.URLEncoder.encode(key, "UTF-8")}", key)
        assertFalse(safe.contains(key)); assertFalse(safe.contains("abc%2B%2FXYZ"))
    }
    @Test fun emptyKeyDoesNotModifyOrdinaryMessages() {
        assertEquals("Player ready", redactSecrets("Player ready"))
    }
}
