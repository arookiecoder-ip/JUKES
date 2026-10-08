package `in`.synthora.musicbox.services

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneQueuePublicationTest {
    @Test fun sharedSongIsVisibleWhileRecommendationsAreStillLoading() = runBlocking {
        val radio = CompletableDeferred<List<String>>()
        val published = mutableListOf<List<String>>()
        val job = launch {
            publishPhoneQueueInStages(listOf("current"), { published.add(it) }, { it + radio.await() })
        }
        yield()
        assertEquals(listOf(listOf("current")), published)
        radio.complete(listOf("next"))
        job.join()
        assertEquals(listOf(listOf("current"), listOf("current", "next")), published)
    }

    @Test fun outputHandoffCancelsLateRecommendationsWithoutReplacingTheNewQueue() = runBlocking {
        val radio = CompletableDeferred<List<String>>()
        val published = mutableListOf<List<String>>()
        val job = launch {
            publishPhoneQueueInStages(listOf("phone"), { published.add(it) }, { it + radio.await() })
        }
        yield()
        job.cancelAndJoin()
        radio.complete(listOf("stale"))
        assertEquals(listOf(listOf("phone")), published)
    }

    @Test fun unavailableRecommendationsLeaveThePublishedSongIntact() = runBlocking {
        val published = mutableListOf<List<String>>()
        publishPhoneQueueInStages(listOf("current"), { published.add(it) }, { it })
        assertEquals(listOf(listOf("current")), published)
    }
}
