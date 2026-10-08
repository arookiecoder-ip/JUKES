package com.example.juke.services

import org.junit.Assert.*
import org.junit.Test

class QueueInstallationTest {
    @Test fun selectedSongStartsPreparingBeforeTheRestOfALargeQueueIsInstalled() {
        val events = mutableListOf<String>()
        installQueueInBatches((0 until 1000).toList(), 901,
            seed = { assertEquals(901, it); events += "seed" },
            insert = { _, _ -> assertEquals("prepare", events[1]); events += "batch" },
            startSelected = { assertEquals(listOf("seed"), events); events += "prepare" })
        assertEquals("prepare", events[1])
        assertEquals(1, events.count { it == "prepare" })
    }

    @Test fun thousandSongQueueKeepsOrderAndSelectedSongWithBoundedTransactions() {
        val wanted = (0 until 1000).toList()
        for (selected in listOf(0, 1, 99, 200, 901, 999)) {
            val player = mutableListOf<Int>()
            var currentIndex = 0
            var transactions = 0
            installQueueInBatches(wanted, selected,
                seed = { player.add(it) },
                insert = { index, batch ->
                    assertTrue(batch.size <= 100)
                    if (index <= currentIndex) currentIndex += batch.size
                    player.addAll(index, batch)
                    transactions++
                })
            assertEquals(wanted, player)
            assertEquals(selected, currentIndex)
            assertEquals(selected, player[currentIndex])
            assertTrue(transactions <= 11)
        }
    }
    @Test fun duplicateSongsRemainDistinctQueueOccurrences() {
        val player = mutableListOf<String>()
        val wanted = listOf("a", "b", "a", "c")
        installQueueInBatches(wanted, 2, 1, seed = { player.add(it) }, insert = { index, batch -> player.addAll(index, batch) })
        assertEquals(wanted, player)
    }
}
