package com.example.juke.services

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class DeviceQueueStartupTest {
    @Test fun playbackStartsWhileServerSynchronizationIsStillBlocked() = runBlocking {
        val server = CompletableDeferred<Unit>()
        var playing = false
        val job = DeviceQueueStartup.start(this, { "stream" }, { playing = true }, { server.await() }, { fail("Unexpected error") })
        yield()
        assertTrue(playing)
        assertFalse(job.isCompleted)
        server.complete(Unit)
        job.join()
    }
    @Test fun synchronizationFailureDoesNotUndoPlayback() = runBlocking {
        var playing = false
        var reported = false
        val job = DeviceQueueStartup.start(this, { "stream" }, { playing = true }, { throw IOException("Offline") }, { reported = true })
        job.join()
        assertTrue(playing)
        assertTrue(reported)
    }
    @Test fun unavailableStreamDoesNotStartPlaybackOrSynchronization() = runBlocking {
        var played = false
        var synchronized = false
        try {
            DeviceQueueStartup.start(this, { throw IOException("No stream") }, { played = true }, { synchronized = true }, {})
            fail("Stream failure must be returned")
        } catch (_: IOException) {}
        assertFalse(played)
        assertFalse(synchronized)
    }
}
