package com.example.juke.network

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.utils.io.readAvailable
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.URI

/** Opt-in live network probe; never run on ordinary builds or play downloaded audio. */
class SpotsaverLiveTest {
    @Test fun coldSessionsResolveAndReturnMp3Bytes() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Enable with -e spotsaverLive true", args.getString("spotsaverLive") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val reports = mutableListOf<JsonObject>()
        try {
            repeat(2) { run ->
                // Fresh client, no cookie storage, credentials, JS or browser session.
                val client = HttpClient(OkHttp) {
                    install(HttpTimeout) {
                        requestTimeoutMillis = 45_000
                        connectTimeoutMillis = 15_000
                        socketTimeoutMillis = 20_000
                    }
                }
                val report = mutableMapOf<String, JsonElement>("run" to JsonPrimitive(run + 1))
                try {
                    suspend fun api(path: String, payload: JsonObject): JsonObject {
                        val start = SystemClock.elapsedRealtime()
                        val response = client.post("https://spotsaver.net/api/$path/") {
                            contentType(ContentType.Application.Json)
                            header("Accept", "application/json")
                            setBody(payload.toString())
                        }
                        val body = response.bodyAsText()
                        report["${path}Status"] = JsonPrimitive(response.status.value)
                        report["${path}Ms"] = JsonPrimitive(SystemClock.elapsedRealtime() - start)
                        val challenge = body.contains("cf-chl", true) || body.contains("Just a moment", true) || body.contains("challenge-platform", true)
                        report["${path}Challenge"] = JsonPrimitive(challenge)
                        assertTrue("$path blocked/challenged: HTTP ${response.status.value}", response.status.value in 200..299 && !challenge)
                        return try { Json.parseToJsonElement(body).jsonObject } catch (e: Exception) {
                            throw AssertionError("$path returned non-JSON (${response.headers["Content-Type"]})", e)
                        }
                    }
                    val id = api("get-id", buildJsonObject {
                        put("title", "Jasmine")
                        put("artist", "Talha Anjum, Umair")
                    })["videoId"]?.jsonPrimitive?.contentOrNull
                    assertTrue("Missing videoId", !id.isNullOrBlank())
                    val response = api("download", buildJsonObject {
                        put("videoId", id!!)
                        put("candidateIds", buildJsonArray {})
                        put("format", "mp3")
                        put("title", "Jasmine - Talha Anjum, Umair")
                        put("licenseKey", JsonNull)
                    })
                    val url = response["downloadUrl"]?.jsonPrimitive?.contentOrNull
                    assertTrue("Missing downloadUrl (status=${response["status"]})", !url.isNullOrBlank())
                    val uri = URI(url!!)
                    assertTrue("Expected HTTPS download URL", uri.scheme == "https" && uri.host != null)
                    report["mediaHost"] = JsonPrimitive(uri.host)
                    val start = SystemClock.elapsedRealtime()
                    client.prepareGet(url).execute { media ->
                        report["mediaStatus"] = JsonPrimitive(media.status.value)
                        report["mediaContentType"] = JsonPrimitive(media.headers["Content-Type"] ?: "")
                        report["mediaHeadersMs"] = JsonPrimitive(SystemClock.elapsedRealtime() - start)
                        assertTrue("Media HTTP ${media.status.value}", media.status.value in 200..299)
                        val bytes = ByteArray(4096)
                        var count = 0
                        val channel = media.bodyAsChannel()
                        while (count < bytes.size) {
                            val read = channel.readAvailable(bytes, count, bytes.size - count)
                            if (read < 0) break
                            count += read
                        }
                        report["firstBytesMs"] = JsonPrimitive(SystemClock.elapsedRealtime() - start)
                        report["sampleBytes"] = JsonPrimitive(count)
                        val id3 = count >= 3 && bytes[0] == 73.toByte() && bytes[1] == 68.toByte() && bytes[2] == 51.toByte()
                        val frame = (0 until (count - 1).coerceAtLeast(0)).any {
                            (bytes[it].toInt() and 255) == 255 && (bytes[it + 1].toInt() and 224) == 224
                        }
                        report["mp3Signature"] = JsonPrimitive(id3 || frame)
                        assertTrue("Returned bytes are not MP3 (could be HTML/challenge)", id3 || frame)
                    }
                    report["passed"] = JsonPrimitive(true)
                } catch (e: Throwable) {
                    report["passed"] = JsonPrimitive(false)
                    report["failureType"] = JsonPrimitive(e.javaClass.simpleName)
                    throw e
                } finally {
                    reports += JsonObject(report)
                    client.close()
                }
            }
        } finally {
            // Signed URLs and cookies are intentionally excluded from the report.
            context.openFileOutput("spotsaver-live-results.json", 0).use {
                it.write(JsonArray(reports).toString().toByteArray())
            }
        }
    }
}
