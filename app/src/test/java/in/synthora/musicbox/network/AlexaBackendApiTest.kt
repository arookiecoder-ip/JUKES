package `in`.synthora.musicbox.network

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AlexaBackendApiTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun radioContractHandlesBothThumbnailShapesAndNulls() {
        val payload = json.decodeFromString<AlexaBackendApi.GetRadioResponse>(
            """{"playlist":[{"title":"Seed","video_id":"AAAAAAAAAAA",
            "thumbnail":{"url":"https://example.com/seed.jpg"},"duration_ms":123456},
            {"video_id":"BBBBBBBBBBB","thumbnail":"https://example.com/b.jpg"},
            {"video_id":"CCCCCCCCCCC","thumbnail":null}]}"""
        )
        val track = payload.playlist[0].toAppTrack("https://example.com/staging/proxy/?video_id=AAAAAAAAAAA&key=test")
        assertEquals("AAAAAAAAAAA", track.ytVideoId)
        assertEquals(123, track.durationSec)
        assertEquals("https://example.com/seed.jpg", track.thumbnailUri)
        assertTrue(track.isStream)
        assertTrue(track.localUri!!.contains("/staging/proxy/"))
        assertEquals("https://example.com/b.jpg", AlexaBackendApi.thumbnailUrl(payload.playlist[1].thumbnail))
        assertNull(AlexaBackendApi.thumbnailUrl(payload.playlist[2].thumbnail))
    }

    @Test fun queueItemsCarryTheMetadataEveryQueueEndpointAccepts() {
        val item = BrowseParser.item(Json.parseToJsonElement(
            """{"video_id":"AAAAAAAAAAA","title":"Song","artist":"Singer","thumbnail":"art","duration_ms":200000,"artist_id":"UC1"}"""
        ) as kotlinx.serialization.json.JsonObject)
        val metadata = item.toTrack(liked = setOf("AAAAAAAAAAA")).metadata()
        assertEquals("AAAAAAAAAAA", metadata.text("video_id"))
        assertEquals("Singer", metadata.text("artist"))
        assertEquals(200000L, metadata.number("duration_ms"))
        assertTrue(item.toTrack(liked = setOf("AAAAAAAAAAA")).isFavourite)
    }
}
