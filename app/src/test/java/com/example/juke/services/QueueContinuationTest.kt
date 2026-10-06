package com.example.juke.services
import org.junit.Assert.*
import org.junit.Test
class QueueContinuationTest {
    @Test fun radioDoesNotRepeatPlayedSongsOrDuplicateCandidates() {
        assertEquals(listOf("c", "d"), unheardQueueIds(listOf("a", "b"), listOf("a", "c", "c", "b", "d")))
    }
    @Test fun fullQueueIsNeverExtendedBeyondItsActualCapacity() {
        assertEquals(listOf("new"), unheardQueueIds(List(4999) { "$it" }, listOf("new", "another")))
        assertTrue(unheardQueueIds(List(5000) { "$it" }, listOf("new")).isEmpty())
    }
    @Test fun shuffleEditsNewPlayAndOutputSwitchInvalidatePendingContinuation() {
        assertTrue(canApplyQueueContinuation(listOf("a", "b"), listOf("a", "b"), "epoch", "epoch", true))
        assertFalse(canApplyQueueContinuation(listOf("a", "b"), listOf("b", "a"), "epoch", "epoch", true))
        assertFalse(canApplyQueueContinuation(listOf("a"), listOf("a", "new"), "epoch", "epoch", true))
        assertFalse(canApplyQueueContinuation(listOf("a"), listOf("a"), "old", "new", true))
        assertFalse(canApplyQueueContinuation(listOf("a"), listOf("a"), "epoch", "epoch", false))
    }
    @Test fun exhaustedQueueContinuesWithoutSkippingNativeAutoAdvanceOrOverridingPause() {
        assertTrue(shouldAdvanceExhaustedQueue(true, 9, 9, true, true))
        assertFalse(shouldAdvanceExhaustedQueue(true, 9, 10, true, true))
        assertFalse(shouldAdvanceExhaustedQueue(true, 9, 9, false, true))
        assertFalse(shouldAdvanceExhaustedQueue(false, 9, 9, true, true))
        assertFalse(shouldAdvanceExhaustedQueue(true, 9, 9, true, false))
    }
}
