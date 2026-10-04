package com.example.juke.services

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class PlaybackHandoffTest {
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
}
