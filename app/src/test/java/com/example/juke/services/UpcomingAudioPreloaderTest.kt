package com.example.juke.services

import android.app.Application
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.*
import androidx.media3.datasource.cache.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Exercise the real cache writer after a server outage; no UI or emulator. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class UpcomingAudioPreloaderTest {
    @Test fun failedBackgroundWarmRetriesAndRetainsCachedAudio() = runBlocking {
        val directory = java.io.File(RuntimeEnvironment.getApplication().cacheDir, "preload-${System.nanoTime()}")
        val cache = SimpleCache(directory, NoOpCacheEvictor())
        val opened = AtomicInteger()
        val closed = AtomicInteger()
        val upstream = DataSource.Factory {
            object : DataSource {
                private val bytes = ByteArrayDataSource(ByteArray(1_024) { 3 })
                override fun addTransferListener(listener: TransferListener) = bytes.addTransferListener(listener)
                override fun open(spec: DataSpec): Long {
                    if (opened.incrementAndGet() == 1) throw IOException("Server unavailable")
                    return bytes.open(spec)
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int) = bytes.read(buffer, offset, length)
                override fun getUri(): Uri? = bytes.uri
                override fun close() { closed.incrementAndGet(); bytes.close() }
            }
        }
        var now = 0L
        val childScope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val preloader = UpcomingAudioPreloader(CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream),
            childScope, policyOverride = { PreloadPolicy(1, 1_024) }, nowMs = { now })
        val url = "https://audio.example/song"
        try {
            preloader.update(listOf(url))
            withTimeout(5_000) { while (closed.get() == 0) delay(10) }
            delay(30) // Return the IO completion to the preloader's owning coroutine.
            now = 4_000
            preloader.update(listOf(url))
            delay(30)
            assertEquals(1, opened.get())
            now = 5_000
            preloader.update(listOf(url))
            withTimeout(5_000) { while (!cache.isCached(url, 0, 1_024)) delay(10) }
            delay(30)
            assertEquals(2, opened.get())
            now = 120_000
            preloader.update(listOf(url))
            delay(30)
            assertEquals(2, opened.get())
            assertTrue(cache.isCached(url, 0, 1_024))
        } finally {
            preloader.clear()
            childScope.coroutineContext[Job]?.cancelAndJoin()
            cache.release()
            directory.deleteRecursively()
        }
    }
}
