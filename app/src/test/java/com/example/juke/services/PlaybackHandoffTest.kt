package com.example.juke.services

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class PlaybackHandoffTest {
    @Test fun readinessMeasurementPropagatesCancellationAndRecordsFailure() = runBlocking {
        val stage = PlaybackDiagnostics.Stage.PLAYER_READY
        val before = PlaybackDiagnostics.snapshot()[stage]?.failures ?: 0
        val failure = CancellationException("Switch superseded")
        try {
            PlaybackDiagnostics.measure(stage) { throw failure }
            fail("Cancelled readiness must not be treated as a completed switch")
        } catch (actual: CancellationException) { assertSame(failure, actual) }
        assertEquals(before + 1, PlaybackDiagnostics.snapshot().getValue(stage).failures)
    }

    @Test fun oldPhoneReportsStayBlockedAcrossAlexaPhoneAlexaRoundTrip() {
        assertEquals(false, canApplyPhoneOwnershipPoll("first-phone", "first-phone", true))
        assertEquals(false, canApplyPhoneOwnershipPoll("first-phone", "", false))
        assertEquals(false, canApplyPhoneOwnershipPoll("first-phone", "second-phone", false))
        assertEquals(true, canApplyPhoneOwnershipPoll("second-phone", "second-phone", false))
        assertEquals(false, canApplyPhoneOwnershipPoll("second-phone", "second-phone", true))
    }

    @Test fun emptyLeaseCannotPublishEvenOutsideHandoff() {
        assertEquals(false, canApplyPhoneOwnershipPoll("", "", false))
    }

    @Test fun activePhoneHandoffExplicitlyResumesWithoutFreshPlayOrSeek() = runBlocking {
        val events = mutableListOf<String>()
        startTransferredAlexaQueue(true, { events += "paused cursor" },
            { events += "web resume" }, { events += "paused seek" })
        assertEquals(listOf("paused cursor", "web resume"), events)
    }

    @Test fun pausedPhoneHandoffNeverDispatchesPlay() = runBlocking {
        val events = mutableListOf<String>()
        startTransferredAlexaQueue(false, { events += "paused cursor" },
            { events += "web resume" }, { events += "paused seek" })
        assertEquals(listOf("paused cursor", "paused seek"), events)
    }

    @Test fun rejectedQueueInstallCannotResumeTheOldServerSong() = runBlocking {
        var resumed = false
        try {
            startTransferredAlexaQueue(true, { error("ownership changed") },
                { resumed = true }, {})
            fail("Rejected shared queue must stop the handoff")
        } catch (_: IllegalStateException) { assertEquals(false, resumed) }
    }

    @Test fun failedOlderSwitchDoesNotRestoreOverANewerPhoneSong() = runBlocking {
        var currentToken = "original"
        var restored = false
        try {
            transferPlayback({}, { currentToken = "new-song"; error("superseded") },
                { if (shouldRestorePhoneSource("original", currentToken)) restored = true }, {}, {})
            fail("Switch must fail")
        } catch (_: IllegalStateException) { assertEquals(false, restored) }
        assertEquals("new-song", currentToken)
        assertEquals(true, shouldRestorePhoneSource("original", ""))
        assertEquals(true, shouldRestorePhoneSource("original", "original"))
    }

    @Test fun outputIsCommittedOnlyAfterDestinationStarts() = runBlocking {
        val events = mutableListOf<String>()
        transferPlayback(
            { events += "pause" }, { events += "target confirmed" },
            { events += "restore" }, { events += "stop target" }, { events += "commit" }
        )
        assertEquals(listOf("pause", "target confirmed", "commit"), events)
    }

    @Test fun failedDestinationResumesSourceWithoutChangingOutput() = runBlocking {
        val events = mutableListOf<String>()
        val failure = IllegalStateException("Echo never confirmed")
        try {
            transferPlayback(
                { events += "pause" }, { throw failure },
                { events += "restore" }, { events += "stop target" }, { events += "commit" }
            )
            fail("Handoff should fail")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
        assertEquals(listOf("pause", "stop target", "restore"), events)
    }

    @Test fun cancellationAlsoRestoresPlaybackAndPropagates() = runBlocking {
        val events = mutableListOf<String>()
        val failure = CancellationException("Signed out")
        try {
            transferPlayback(
                { events += "pause" }, { throw failure },
                { events += "restore" }, { events += "stop target" }, { events += "commit" }
            )
            fail("Cancellation should propagate")
        } catch (actual: CancellationException) {
            assertSame(failure, actual)
        }
        assertEquals(listOf("pause", "stop target", "restore"), events)
    }

    @Test fun failedTargetCleanupDoesNotPreventSourceRecovery() = runBlocking {
        var restored = false
        val failure = IllegalStateException("No stream")
        try {
            transferPlayback({}, { throw failure }, { restored = true },
                { error("Echo offline") }, { fail("Must not commit") })
            fail("Handoff should fail")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
            assertEquals(1, actual.suppressed.size)
        }
        assertEquals(true, restored)
    }
    @Test fun hungTargetIsCancelledAndPhoneRecoveryStillRuns() = runBlocking {
        var restored = false
        try {
            transferPlayback({}, { kotlinx.coroutines.awaitCancellation() }, { restored = true }, {}, {},
                targetTimeoutMs = 20, recoveryTimeoutMs = 20)
            fail("Hung handoff must finish")
        } catch (_: IllegalStateException) { assertEquals(true, restored) }
    }

    @Test fun hungEchoCleanupCannotBlockPhoneRecovery() = runBlocking {
        var restored = false
        try {
            transferPlayback({}, { error("Silent Echo") }, { restored = true },
                { kotlinx.coroutines.awaitCancellation() }, {}, targetTimeoutMs = 20, recoveryTimeoutMs = 20)
            fail("Failed handoff must finish")
        } catch (_: IllegalStateException) { assertEquals(true, restored) }
    }

    @Test fun hungPhoneRecoveryCannotKeepOutputSwitchLocked() = runBlocking {
        try {
            transferPlayback({}, { error("Silent Echo") }, { kotlinx.coroutines.awaitCancellation() }, {}, {},
                targetTimeoutMs = 20, recoveryTimeoutMs = 20)
            fail("Recovery must finish")
        } catch (error: IllegalStateException) { assertEquals(1, error.suppressed.size) }
    }
    @Test fun failedSourceValidationCannotPauseAnUntouchedAlexaDestination() = runBlocking {
        var targetTouched = false
        val failure = IllegalStateException("Server unavailable during lease validation")
        try {
            transferPlayback({ throw failure }, { targetTouched = true }, {},
                { targetTouched = true }, { fail("Must not commit") })
            fail("Validation failure must propagate")
        } catch (actual: IllegalStateException) { assertSame(failure, actual) }
        assertFalse(targetTouched)
    }
}
