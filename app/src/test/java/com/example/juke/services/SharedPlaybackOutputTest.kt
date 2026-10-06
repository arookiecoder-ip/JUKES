package com.example.juke.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class SharedPlaybackOutputTest {
    @Test fun reopeningWithAnOldPhoneTokenCannotClaimALaterAlexaSession() {
        val latest = sharedPlaybackOutput(Json.parseToJsonElement("""{
            "playback_output":"alexa","output_owner":"","output_token":"new-echo-intent",
            "output_serial":"echo-one","phone_lease_ms":0
        }""").jsonObject)
        assertFalse(latest.belongsToPhone("my-phone", "cached-phone-token"))
        assertEquals("echo-one", latest.serial)
    }
    @Test fun phoneOwnershipRequiresMatchingClientAndExactEpoch() {
        val current = SharedPlaybackOutput("phone", "my-phone", "current", 12000)
        assertTrue(current.belongsToPhone("my-phone", "current"))
        assertFalse(current.belongsToPhone("my-phone", "old"))
        assertFalse(current.belongsToPhone("another-phone", "current"))
        assertFalse(SharedPlaybackOutput("phone", "my-phone", "").belongsToPhone("my-phone", ""))
    }
    @Test fun confirmedPhoneMetadataDoesNotBecomeUnconfirmedEchoPlayback() {
        val snapshot = parseEchoSnapshot(Json.parseToJsonElement("""{
            "video_id":"abcdefghijk","title":"Phone song","queue":[],"queue_index":-1,
            "playback_output":"phone","output_owner":"my-phone","output_token":"current",
            "playing":true,"playback_confirmed":true,"playback_processing":false
        }""").jsonObject, now = 100, previousVolume = null, preserveVolume = false)
        assertEquals("phone", snapshot.sharedOutput.mode)
        assertTrue(snapshot.confirmed)
        assertFalse(snapshot.processing)
        assertFalse(snapshot.confirmedOnAlexa("abcdefghijk"))
        assertTrue(snapshot.copy(sharedOutput = SharedPlaybackOutput("alexa")).confirmedOnAlexa("abcdefghijk"))
    }
    @Test fun actualAlexaBufferingRemainsUnconfirmedAndProcessing() {
        val snapshot = parseEchoSnapshot(Json.parseToJsonElement("""{
            "video_id":"abcdefghijk","title":"Echo song","queue":[],"queue_index":-1,
            "playback_output":"alexa","playing":true,
            "playback_confirmed":false,"playback_processing":true
        }""").jsonObject, now = 100, previousVolume = null, preserveVolume = false)
        assertFalse(snapshot.confirmed)
        assertTrue(snapshot.processing)
        assertTrue(snapshot.playing)
        assertFalse(snapshot.confirmedOnAlexa("abcdefghijk"))
    }
    @Test fun preparedAudioCannotRestartPhoneAfterPauseAcknowledgement() {
        assertFalse(canStartPhonePlayback("old", "", true, 0, 100))
        assertFalse(canStartPhonePlayback("old", "new", false, 1000, 100))
        assertFalse(canStartPhonePlayback("old", "old", false, 100, 100))
        assertTrue(canStartPhonePlayback("new", "new", false, 1000, 100))
        assertTrue(canStartPhonePlayback("", "", false, 0, 100)) // Explicit offline playback.
        assertFalse(canStartPhonePlayback("", "new", false, 1000, 100)) // Older offline preparation.
    }
    @org.junit.Test fun offlineDownloadsKeepPlayingButNetworkStreamsStillRequireALease() {
        org.junit.Assert.assertTrue(canContinueDownloadedOffline(false, "file"))
        org.junit.Assert.assertTrue(canContinueDownloadedOffline(false, "content"))
        org.junit.Assert.assertFalse(canContinueDownloadedOffline(false, "https"))
        org.junit.Assert.assertFalse(canContinueDownloadedOffline(true, "file"))
        org.junit.Assert.assertFalse(canContinueDownloadedOffline(false, null))
    }
}
