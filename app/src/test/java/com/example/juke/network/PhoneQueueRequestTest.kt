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
