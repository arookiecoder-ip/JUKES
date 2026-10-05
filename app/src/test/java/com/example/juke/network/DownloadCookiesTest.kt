package com.example.juke.network

import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class DownloadCookiesTest {
    @Test fun manualCheckAlwaysForcesAudioProbe() = runBlocking {
        val api = DownloadCookies { method, body, force ->
            assertEquals(HttpMethod.Get, method); assertNull(body); assertTrue(force)
            Json.parseToJsonElement("""{"valid":false,"message":"Audio download failed"}""")
        }
        val result = api.check()
        assertFalse(result.valid); assertEquals("Audio download failed", result.message)
    }

    @Test fun successfulSaveRequiresBothDownloadAndPromotion() = runBlocking {
        val export = "# Netscape HTTP Cookie File\nexample"
        val api = DownloadCookies { method, body, force ->
            assertEquals(HttpMethod.Post, method); assertFalse(force)
            assertEquals(export, body!!["cookies"]!!.jsonPrimitive.content)
            Json.parseToJsonElement("""{"success":true,"valid":true,"message":"Audio passed"}""")
        }
        assertTrue(api.replace(export).valid)
    }

    @Test fun failedProbeCannotBeReportedAsSaved() = runBlocking {
        for (reply in listOf("""{"success":true,"valid":false}""", """{"valid":true}""")) {
            val api = DownloadCookies { _, _, _ -> Json.parseToJsonElement(reply) }
            try { api.replace("export"); fail("Failed probe was accepted") }
            catch (e: IllegalStateException) { assertTrue(e.message!!.contains("Existing cookies were kept")) }
        }
    }

    @Test fun blankAndOversizedUtf8ExportsAreRejectedWithoutRequest() = runBlocking {
        val api = DownloadCookies { _, _, _ -> error("Should not send") }
        for (export in listOf("  ", "é".repeat(65537))) {
            try { api.replace(export); fail("Invalid export sent") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun serverRejectionReachesCallerForRetry() = runBlocking {
        val api = DownloadCookies { _, _, _ -> throw BackendHttpException(422, "Audio download failed") }
        try { api.replace("export"); fail("Rejection was swallowed") }
        catch (e: BackendHttpException) { assertEquals(422, e.statusCode) }
    }
}
