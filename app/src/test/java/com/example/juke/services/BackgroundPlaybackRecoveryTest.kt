package com.example.juke.services

import org.junit.Assert.*
import org.junit.Test

class BackgroundPlaybackRecoveryTest {
    @Test fun aNewSongBlocksOldOwnershipReportsUntilItsClaimAndQueueAreInstalled() {
        val preparation = PhonePreparationGuard()
        preparation.begin()
        assertFalse(canApplyPhoneOwnershipPoll("old", "old", preparation.active))
        assertFalse(canApplyPhoneOwnershipPoll("old", "new", preparation.active))
        assertTrue(deferDeviceCommand(true, true, preparation.active))
        preparation.end()
        assertFalse(canApplyPhoneOwnershipPoll("old", "new", preparation.active))
        assertTrue(canApplyPhoneOwnershipPoll("new", "new", preparation.active))
        assertFalse(deferDeviceCommand(true, true, preparation.active))
    }

    @Test fun cancellingOnePreparationDoesNotUnblockItsReplacement() {
        val preparation = PhonePreparationGuard()
        preparation.begin()
        preparation.begin()
        preparation.end()
        assertTrue(preparation.active)
        preparation.end()
        assertFalse(preparation.active)
    }

    @Test fun switchingOutputsNeverAnimatesSongArtworkEvenWithDifferentSavedSongs() {
        assertFalse(animateSongChange("old-device-song", "transferred-song", true))
        assertFalse(animateSongChange("song", "song", false))
        assertTrue(animateSongChange("song", "next", false))
    }

    @Test fun expiredLeaseResumesOnlyAfterRenewalOfTheSameIntent() {
        val interruption = PlaybackInterruption()
        interruption.remember("song", "lease", true)
        assertFalse(interruption.canResume("song", "lease", false, false, false))
        assertTrue(interruption.canResume("song", "lease", true, false, false))
        assertFalse(interruption.canResume("other song", "lease", true, false, false))
        assertFalse(interruption.canResume("song", "new lease", true, false, false))
        assertFalse(interruption.canResume("song", "lease", true, true, false))
        assertFalse(interruption.canResume("song", "lease", true, false, true))
    }
    @Test fun manualPauseOrTrackChangeCancelsAutomaticRecovery() {
        val interruption = PlaybackInterruption()
        interruption.remember("song", "lease", true)
        interruption.clear()
        assertFalse(interruption.canResume("song", "lease", true, false, false))
        interruption.remember("song", "lease", false)
        assertFalse(interruption.pending)
        interruption.remember("song", "", true)
        assertFalse(interruption.pending)
    }
    @Test fun aLongServerOutageDoesNotExhaustRecoveryPermanently() {
        val retry = BackgroundRetry()
        var now = 0L
        repeat(100) {
            assertTrue(retry.ready(now))
            retry.failed(now)
            assertFalse(retry.ready(now + 1))
            now += 60_000
            assertTrue(retry.ready(now))
        }
    }
    @Test fun preloadingBacksOffAndCanRestartAfterNetworkReturns() {
        val retry = BackgroundRetry()
        retry.failed(1_000)
        assertFalse(retry.ready(5_999))
        assertTrue(retry.ready(6_000))
        retry.failed(6_000)
        assertFalse(retry.ready(15_999))
        assertTrue(retry.ready(16_000))
        retry.reset()
        assertTrue(retry.ready(6_001))
    }
    @Test fun serverRateLimitOverridesShortRecoveryIntervals() {
        val retry = BackgroundRetry()
        retry.failed(1_000, 60_000)
        assertFalse(retry.ready(60_999))
        assertTrue(retry.ready(61_000))
    }
    @Test fun recoveryRetainsOnlyAnExistingLocalForegroundSession() {
        assertTrue(retainRecoveryNotification(true, true, false, true, false, false, false))
        assertTrue(retainRecoveryNotification(true, true, false, false, true, false, false))
        assertTrue(retainRecoveryNotification(true, true, false, false, false, true, false))
        assertFalse(retainRecoveryNotification(true, true, false, false, false, false, false))
        assertFalse(retainRecoveryNotification(true, false, false, true, true, true, false))
        assertFalse(retainRecoveryNotification(false, true, false, true, true, true, false))
        assertFalse(retainRecoveryNotification(true, true, false, true, true, true, true))
        assertFalse(retainRecoveryNotification(true, true, true, true, true, true, false))
    }

    @Test fun slowPlaybackWorkDoesNotBlockPresenceAndNewestUpdateSurvives() = kotlinx.coroutines.runBlocking {
        val consumer = kotlinx.coroutines.CoroutineScope(coroutineContext + kotlinx.coroutines.Job())
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val finished = kotlinx.coroutines.CompletableDeferred<Unit>()
        val seen = mutableListOf<Int>()
        val delivery = PresenceDelivery<Int>(consumer) { value ->
            seen += value
            if (value == 1) { started.complete(Unit); release.await() }
            if (value == 3) finished.complete(Unit)
        }
        try {
            delivery.offer(1)
            kotlinx.coroutines.withTimeout(2000) { started.await() }
            delivery.offer(2)
            delivery.offer(3) // These calls complete while playback is still waiting.
            assertEquals(listOf(1), seen)
            release.complete(Unit)
            kotlinx.coroutines.withTimeout(2000) { finished.await() }
            assertEquals(listOf(1, 3), seen)
        } finally { consumer.coroutineContext[kotlinx.coroutines.Job]!!.let { it.cancel(); it.join() } }
    }

    @Test fun failedPlaybackDeliveryDoesNotKillLaterReconciliation() = kotlinx.coroutines.runBlocking {
        val consumer = kotlinx.coroutines.CoroutineScope(coroutineContext + kotlinx.coroutines.Job())
        val failed = kotlinx.coroutines.CompletableDeferred<Unit>()
        val healed = kotlinx.coroutines.CompletableDeferred<Unit>()
        val delivery = PresenceDelivery<Int>(consumer, onFailure = { failed.complete(Unit) }) {
            if (it == 1) error("Server unavailable")
            healed.complete(Unit)
        }
        try {
            delivery.offer(1)
            kotlinx.coroutines.withTimeout(2000) { failed.await() }
            delivery.offer(2)
            kotlinx.coroutines.withTimeout(2000) { healed.await() }
        } finally { consumer.coroutineContext[kotlinx.coroutines.Job]!!.let { it.cancel(); it.join() } }
    }

    @Test fun acknowledgmentsCompletedDuringRequestAreNotLost() {
        val ledger = CommandAcknowledgments()
        ledger.completed("first")
        val submitted = ledger.snapshot()
        ledger.completed("second")
        ledger.delivered(submitted)
        assertEquals(listOf("second"), ledger.snapshot())
        repeat(40) { ledger.completed("cmd-$it") }
        assertEquals(32, ledger.snapshot().size)
        ledger.delivered(ledger.snapshot())
        assertEquals(9, ledger.snapshot().size)
    }

    @Test fun preparingDestinationDoesNotAcknowledgeUnexecutedControls() {
        assertTrue(deferDeviceCommand(true, false, false))
        assertTrue(deferDeviceCommand(true, true, true))
        assertFalse(deferDeviceCommand(true, true, false))
        assertFalse(deferDeviceCommand(false, false, true))
    }
    @Test fun bufferingWithoutProgressRecoversButAdvancingBufferDoesNot() {
        val stall = BufferingStall()
        assertFalse(stall.shouldRecover("song", true, true, true, 0, 4000, 4000))
        assertFalse(stall.shouldRecover("song", true, true, true, 29999, 4000, 4000))
        assertTrue(stall.shouldRecover("song", true, true, true, 30000, 4000, 4000))
        assertFalse(stall.shouldRecover("song", true, true, true, 31000, 4000, 4000))
        assertFalse(stall.shouldRecover("song", true, true, true, 59000, 4000, 6000))
        assertFalse(stall.shouldRecover("song", true, true, true, 60000, 4000, 6000))
    }
    @Test fun stallDetectionNeverRestartsPausedPlayingOrUnownedAudio() {
        for (flags in listOf(Triple(false, true, true), Triple(true, false, true), Triple(true, true, false))) {
            val stall = BufferingStall()
            assertFalse(stall.shouldRecover("song", flags.first, flags.second, flags.third, 0, 0, 0))
            assertFalse(stall.shouldRecover("song", flags.first, flags.second, flags.third, 60000, 0, 0))
        }
        val changed = BufferingStall()
        changed.shouldRecover("song", true, true, true, 0, 0, 0)
        assertFalse(changed.shouldRecover("new-song", true, true, true, 60000, 0, 0))
    }
    @Test fun foregroundBridgeNeverPromotesAnUnapprovedBackgroundService() {
        assertTrue(canJoinRemoteForeground(true, false, false, false))
        assertTrue(canJoinRemoteForeground(false, true, false, false))
        assertTrue(canJoinRemoteForeground(false, false, true, false))
        assertFalse(canJoinRemoteForeground(false, false, false, false))
        assertFalse(canJoinRemoteForeground(true, true, true, true))
    }
}
