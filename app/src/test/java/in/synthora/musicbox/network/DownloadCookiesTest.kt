package `in`.synthora.musicbox.network

import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class DownloadCookiesTest {
    private val row = ".youtube.com\tTRUE\t/\tTRUE\t2000000000\tTEST\tvalue"
    private val export = "# Netscape HTTP Cookie File\n$row"
    @Test fun manualCheckAlwaysForcesAudioProbe() = runBlocking {
        val api = DownloadCookies { method, body, force ->
            assertEquals(HttpMethod.Get, method); assertNull(body); assertTrue(force)
            Json.parseToJsonElement("""{"valid":false,"message":"Audio download failed"}""")
        }
        val result = api.check()
        assertFalse(result.valid); assertEquals("Audio download failed", result.message)
    }

    @Test fun successfulSaveRequiresBothDownloadAndPromotion() = runBlocking {
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
            try { api.replace(export); fail("Failed probe was accepted") }
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
        try { api.replace(export); fail("Rejection was swallowed") }
        catch (e: BackendHttpException) { assertEquals(422, e.statusCode) }
    }
    @Test fun fileBomAndWindowsLineEndingsAreNormalized() = runBlocking {
        val api = DownloadCookies { _, body, _ ->
            assertEquals(export, body!!["cookies"]!!.jsonPrimitive.content)
            Json.parseToJsonElement("""{"success":true,"valid":true}""")
        }
        assertTrue(api.replace("\uFEFF" + export.replace("\n", "\r\n")).valid)
    }

    @Test fun tabSeparatedRowsWithoutHeaderReceiveNetscapeHeader() = runBlocking {
        val api = DownloadCookies { _, body, _ ->
            assertEquals(export, body!!["cookies"]!!.jsonPrimitive.content)
            Json.parseToJsonElement("""{"success":true,"valid":true}""")
        }
        assertTrue(api.replace(row).valid)
    }

    @Test fun jsonOrDamagedClipboardExportIsRejectedBeforeSending() = runBlocking {
        val api = DownloadCookies { _, _, _ -> error("Should not send malformed export") }
        for (damaged in listOf("[{\"name\":\"TEST\",\"value\":\"value\"}]", row.replace('\t', ' '))) {
            try { api.replace(damaged); fail("Malformed export sent") }
            catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("tab-separated")) }
        }
    }

    @Test fun busyBackgroundProbeIsRetriedButInvalidUploadsAreNot() = runBlocking {
        var attempts = 0
        val api = DownloadCookies { _, _, _ ->
            if (++attempts == 1) throw BackendHttpException(429, "Another test is running")
            Json.parseToJsonElement("""{"valid":true}""")
        }
        assertTrue(api.check().valid)
        assertEquals(2, attempts)
    }

}
