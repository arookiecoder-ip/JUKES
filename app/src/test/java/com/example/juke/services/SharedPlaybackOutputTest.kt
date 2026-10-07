package com.example.juke.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class SharedPlaybackOutputTest {
    @org.junit.Test fun renewedSongTokenRetriesOnlyExplicitIntentsOnTheSamePhone() {
        val old = SharedPlaybackOutput(mode = "phone", owner = "two", token = "old")
        val renewed = old.copy(token = "new")
        org.junit.Assert.assertTrue(canRetryPhoneCommand(old, renewed, "play"))
        org.junit.Assert.assertTrue(canRetryPhoneCommand(old, renewed, "song"))
        org.junit.Assert.assertTrue(canRetryPhoneCommand(old, renewed, "queue", true))
        org.junit.Assert.assertFalse(canRetryPhoneCommand(old, renewed, "queue"))
        org.junit.Assert.assertFalse(canRetryPhoneCommand(old, renewed, "next"))
        org.junit.Assert.assertFalse(canRetryPhoneCommand(old, renewed.copy(owner = "one"), "play"))
        org.junit.Assert.assertFalse(canRetryPhoneCommand(old, renewed.copy(mode = "alexa"), "play"))
        org.junit.Assert.assertFalse(canRetryPhoneCommand(old, renewed.copy(handoffPending = true), "play"))
    }

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
    @org.junit.Test fun pendingHandoffNeverGrantsTargetPlayback() {
        val output = SharedPlaybackOutput("phone", "target", "lease", 12_000, handoffPending = true)
        org.junit.Assert.assertFalse(output.belongsToPhone("target", "lease"))
    }
    @Test fun remoteControlledLeaseIdentifiesTheFallbackController() {
        val latest = sharedPlaybackOutput(Json.parseToJsonElement("""{
            "playback_output":"phone","output_owner":"phone-two","output_token":"transfer",
            "output_controller":"phone-one","phone_lease_ms":12000,"handoff_pending":false
        }""").jsonObject)
        assertEquals("phone-one", latest.controller)
        assertTrue(latest.belongsToPhone("phone-two", "transfer"))
        assertFalse(latest.belongsToPhone("phone-one", "transfer"))
    }
    @Test fun delayedOutputSnapshotsCannotUndoANewerHandoffButServerRestartCanReconcile() {
        val latest = SharedPlaybackOutput("phone", "two", "new", revision = 4, epoch = "server-one")
        assertTrue(SharedPlaybackOutput("alexa", token = "old", revision = 3, epoch = "server-one").olderThan(latest))
        assertFalse(latest.copy(revision = 4).olderThan(latest))
        assertFalse(SharedPlaybackOutput("alexa", revision = 1, epoch = "restarted-server").olderThan(latest))
    }
}
