package com.example.juke.services

import org.junit.Assert.*
import org.junit.Test

class SharedQueueTest {
    @Test fun longShuffledQueueRetainsItsEntireTail() {
        val order = (0 until 5_000).map { "video$it" }.shuffled(kotlin.random.Random(42))
        val current = order[120]
        val index = sharedQueueIndex(order, current, current, 120, 120)
        assertEquals(4_879, order.drop(index + 1).size)
        assertEquals(order.subList(121, 5_000), order.drop(index + 1))
    }
    @Test fun duplicateSongUsesItsCurrentOccurrence() {
        val queue = listOf("a", "b", "a", "c", "a")
        assertEquals(2, sharedQueueIndex(queue, "a", "a", 2, 0))
        assertEquals(4, sharedQueueIndex(queue, "a", "b", 1, 4))
    }
    @Test fun staleSnapshotCannotClearCurrentQueue() {
        assertEquals(-1, sharedQueueIndex(listOf("old"), "new", "old", 0, 0))
    }
    @org.junit.Test fun movingRowsPreservesTheCurrentOccurrence() {
        org.junit.Assert.assertEquals(4, queueIndexAfterMove(1, 1, 4))
        org.junit.Assert.assertEquals(2, queueIndexAfterMove(3, 1, 4))
        org.junit.Assert.assertEquals(4, queueIndexAfterMove(3, 5, 1))
        org.junit.Assert.assertEquals(0, queueIndexAfterMove(0, 2, 4))
    }
}
