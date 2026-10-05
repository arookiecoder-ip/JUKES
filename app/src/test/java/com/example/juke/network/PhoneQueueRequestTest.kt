package com.example.juke.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class PhoneQueueRequestTest {
    @Test fun fullPhoneQueueInstallationAndReadsUseTheSameKeyedServer() = runBlocking {
        val tracks = List(5_000) { AlexaBackendApi.BackendTrack(videoId = if (it % 2 == 0) "abcdefghijk" else "lmnopqrstuv") }
        var installed = false
        var requests = 0
        val engine = MockEngine { request ->
            requests++
            assertEquals("audio.example", request.url.host)
            assertEquals("audio-key", request.headers["X-Api-Key"])
            if (request.method.value == "POST") {
                assertEquals("/staging/api/app/queue/", request.url.encodedPath)
                val body = request.body as OutgoingContent.ByteArrayContent
                val payload = Json.parseToJsonElement(body.bytes().toString(Charsets.UTF_8)).jsonObject
                assertEquals("start", payload["action"]!!.jsonPrimitive.content)
                assertEquals(5_000, (payload["tracks"] as kotlinx.serialization.json.JsonArray).size)
                assertEquals("3457", payload["queue_index"]!!.jsonPrimitive.content)
                installed = true
                respond("{}", HttpStatusCode.OK)
            } else {
                assertTrue(installed)
                assertEquals("/staging/alexa/now_playing/", request.url.encodedPath)
                assertEquals("phone", request.url.parameters["serial"])
                respond("""{"video_id":"lmnopqrstuv","queue_index":3457,"queue":[]}""", HttpStatusCode.OK)
            }
        }
        HttpClient(engine).use { client ->
            publishPhoneQueue(client, "https://audio.example/staging/", "audio-key",
                AlexaBackendApi.QueueUpdate("start", "lmnopqrstuv", tracks, true, 9000, 3457))
            val snapshot = readPhoneQueue(client, "https://audio.example/staging/", "audio-key")
            assertEquals("3457", snapshot["queue_index"]!!.jsonPrimitive.content)
        }
        assertEquals(2, requests)
    }
    @Test fun phoneQueueUsesAudioServerPrefixAndApiKeyWithJsonBody() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals("https://audio.example/staging/api/app/queue/", request.url.toString())
            assertEquals("POST", request.method.value)
            assertEquals("audio-key", request.headers["X-Api-Key"])
            val body = request.body as OutgoingContent.ByteArrayContent
            val payload = Json.parseToJsonElement(body.bytes().toString(Charsets.UTF_8)).jsonObject
            assertEquals("current", payload["action"]!!.jsonPrimitive.content)
            assertEquals("abcdefghijk", payload["after"]!!.jsonPrimitive.content)
            respond("{}", HttpStatusCode.OK)
        }
        HttpClient(engine).use { client ->
            publishPhoneQueue(client, "https://audio.example/staging/", "audio-key",
                AlexaBackendApi.QueueUpdate("current", "abcdefghijk", emptyList()))
        }
    }

    @Test fun queueAuthFailureDoesNotBecomeAccountSessionFailure() = runBlocking {
        HttpClient(MockEngine { respond("{}", HttpStatusCode.Unauthorized) }).use { client ->
            try {
                publishPhoneQueue(client, "https://audio.example/staging", "wrong-key",
                    AlexaBackendApi.QueueUpdate("current", "abcdefghijk", emptyList()))
                fail("Authentication failure was swallowed")
            } catch (e: BackendHttpException) { assertEquals(401, e.statusCode) }
        }
    }
}
