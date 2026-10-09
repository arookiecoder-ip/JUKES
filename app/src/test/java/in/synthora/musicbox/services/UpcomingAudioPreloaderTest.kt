package `in`.synthora.musicbox.services

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
import java.util.concurrent.atomic.AtomicBoolean

/** Exercise the real cache writer after a server outage; no UI or emulator. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class UpcomingAudioPreloaderTest {
    @Test fun activeLazyDownloadIsPromotedToDemandWhenItsSongStarts() = runBlocking {
        val directory = java.io.File(RuntimeEnvironment.getApplication().cacheDir, "promote-${System.nanoTime()}")
        val cache = SimpleCache(directory, NoOpCacheEvictor())
        val entered = java.util.concurrent.CountDownLatch(1)
        val stopped = java.util.concurrent.CountDownLatch(1)
        val demandOpens = AtomicInteger()
        val acquired = AtomicInteger()
        val released = AtomicInteger()
        val url = "https://audio.example/next"
        val lazyFactory = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory {
            object : DataSource {
                override fun addTransferListener(listener: TransferListener) {}
                override fun open(spec: DataSpec): Long = 8_192
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    entered.countDown(); check(stopped.await(5, java.util.concurrent.TimeUnit.SECONDS)); throw IOException("Lazy download cancelled")
                }
                override fun getUri(): Uri = Uri.parse(url)
                override fun close() { stopped.countDown() }
            }
        }
        val demandFactory = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory {
            object : DataSource {
                val delegate = ByteArrayDataSource(ByteArray(8_192))
                override fun addTransferListener(listener: TransferListener) = delegate.addTransferListener(listener)
                override fun open(spec: DataSpec): Long { demandOpens.incrementAndGet(); return delegate.open(spec) }
                override fun read(buffer: ByteArray, offset: Int, length: Int) = delegate.read(buffer, offset, length)
                override fun getUri() = delegate.uri
                override fun close() = delegate.close()
            }
        }
        val childScope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val preloader = UpcomingAudioPreloader(lazyFactory, childScope, policyOverride = { PreloadPolicy(1, -1) },
            demandFactory = demandFactory, guardFactory = {
                acquired.incrementAndGet()
                object : AudioDownloadGuard {
                    override fun progress() {}
                    override fun close() { released.incrementAndGet() }
                }
            })
        try {
            preloader.update(listOf(url))
            withContext(Dispatchers.IO) { assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
            preloader.update(emptyList(), currentUrl = url)
            withTimeout(5_000) { childScope.coroutineContext[Job]!!.children.toList().forEach { it.join() } }
            assertTrue(cache.isCached(url, 0, 8_192))
            assertEquals(1, demandOpens.get()); assertEquals(1, acquired.get()); assertEquals(1, released.get())
        } finally {
            stopped.countDown(); preloader.clear(); childScope.coroutineContext[Job]?.cancelAndJoin()
            cache.release(); directory.deleteRecursively()
        }
    }

    @Test fun failedCurrentDownloadReleasesGuardAndDoesNotBusyRetry() = runBlocking {
        val directory = java.io.File(RuntimeEnvironment.getApplication().cacheDir, "failed-guard-${System.nanoTime()}")
        val cache = SimpleCache(directory, NoOpCacheEvictor())
        val acquired = AtomicInteger()
        val released = AtomicInteger()
        val childScope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val factory = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory {
            object : DataSource {
                override fun addTransferListener(listener: TransferListener) {}
                override fun open(spec: DataSpec): Long = throw IOException("Offline")
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
                override fun getUri(): Uri? = null
                override fun close() {}
            }
        }
        val preloader = UpcomingAudioPreloader(factory, childScope, policyOverride = { PreloadPolicy(0, 0) }, nowMs = { 0 },
            guardFactory = {
                acquired.incrementAndGet()
                object : AudioDownloadGuard {
                    override fun progress() {}
                    override fun close() { released.incrementAndGet() }
                }
            })
        val url = "https://audio.example/current"
        suspend fun finish() { withTimeout(5_000) { childScope.coroutineContext[Job]!!.children.toList().forEach { it.join() } } }
        try {
            preloader.update(emptyList(), currentUrl = url); finish()
            assertEquals(1, acquired.get()); assertEquals(1, released.get())
            preloader.update(emptyList(), currentUrl = url); finish()
            assertEquals(1, acquired.get()); assertEquals(1, released.get())
        } finally {
            preloader.clear(); childScope.coroutineContext[Job]?.cancelAndJoin()
            cache.release(); directory.deleteRecursively()
        }
    }

    @Test fun currentDownloadHoldsGuardOnlyUntilCompletionAndUpcomingSongsDoNotAcquireIt() = runBlocking {
        val directory = java.io.File(RuntimeEnvironment.getApplication().cacheDir, "guard-${System.nanoTime()}")
        val cache = SimpleCache(directory, NoOpCacheEvictor())
        val acquired = AtomicInteger()
        val released = AtomicInteger()
        val progressed = AtomicInteger()
        val childScope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val preloader = UpcomingAudioPreloader(CacheDataSource.Factory().setCache(cache)
            .setUpstreamDataSourceFactory { ByteArrayDataSource(ByteArray(8_192)) }, childScope,
            policyOverride = { PreloadPolicy(5, -1) }, guardFactory = {
                acquired.incrementAndGet()
                object : AudioDownloadGuard {
                    override fun progress() { progressed.incrementAndGet() }
                    override fun close() { released.incrementAndGet() }
                }
            })
        suspend fun finish() { withTimeout(5_000) { childScope.coroutineContext[Job]!!.children.toList().forEach { it.join() } } }
        val current = "https://audio.example/current"
        val next = listOf("https://audio.example/next")
        try {
            preloader.update(next, currentUrl = current); finish()
            assertEquals(1, acquired.get()); assertEquals(1, released.get()); assertTrue(progressed.get() > 0)
            preloader.update(next, currentUrl = current); finish()
            assertTrue(cache.isCached(next.single(), 0, 8_192))
            assertEquals(1, acquired.get()); assertEquals(1, released.get())
        } finally {
            preloader.clear(); childScope.coroutineContext[Job]?.cancelAndJoin()
            cache.release(); directory.deleteRecursively()
        }
    }

    @Test fun partialAudioBecomesReadableBeforeWholeBackgroundDownloadCompletes() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val directory = java.io.File(app.cacheDir, "fragments-${System.nanoTime()}")
        val cache = SimpleCache(directory, NoOpCacheEvictor())
        val paused = java.util.concurrent.CountDownLatch(1)
        val resume = java.util.concurrent.CountDownLatch(1)
        val childScope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val url = "https://audio.example/current"
        val total = 1_048_576
        val factory = (playbackDataSources(app, cache, cacheWarm = true) as CacheDataSource.Factory)
            .setUpstreamDataSourceFactory {
                object : DataSource {
                    val delegate = ByteArrayDataSource(ByteArray(total) { 9 })
                    var read = 0
                    override fun addTransferListener(listener: TransferListener) = delegate.addTransferListener(listener)
                    override fun open(spec: DataSpec): Long = delegate.open(spec)
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (read >= 524_288 && resume.count > 0) { paused.countDown(); check(resume.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
                        val count = delegate.read(buffer, offset, length)
                        if (count > 0) read += count
                        return count
                    }
                    override fun getUri() = delegate.uri
                    override fun close() { resume.countDown(); delegate.close() }
                }
            }
        val preloader = UpcomingAudioPreloader(factory, childScope, policyOverride = { PreloadPolicy(0, 0) })
        try {
            preloader.update(emptyList(), currentUrl = url)
            withContext(Dispatchers.IO) { assertTrue(paused.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
            assertFalse(cache.isCached(url, 0, total.toLong()))
            assertTrue(cache.isCached(url, 0, 262_144))
            resume.countDown()
            withTimeout(5_000) { childScope.coroutineContext[Job]!!.children.toList().forEach { it.join() } }
            assertTrue(cache.isCached(url, 0, total.toLong()))
        } finally {
            resume.countDown(); preloader.clear(); childScope.coroutineContext[Job]?.cancelAndJoin()
            cache.release(); directory.deleteRecursively()
        }
    }

    @Test fun openPlaybackReaderDoesNotBlockWholeSongCachingAndOfflineReplay() = runBlocking {
        val directory = java.io.File(RuntimeEnvironment.getApplication().cacheDir, "reader-${System.nanoTime()}")
        val cache = SimpleCache(directory, NoOpCacheEvictor())
        val url = "https://audio.example/current"
        val bytes = ByteArray(8_192) { 7 }
        val reader = playbackDataSources(RuntimeEnvironment.getApplication(), cache,
            readOnlyCache = true).let { it as CacheDataSource.Factory }
            .setUpstreamDataSourceFactory { ByteArrayDataSource(bytes) }.createDataSource()
        val writer = CacheDataSource.Factory().setCache(cache)
            .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE)
            .setUpstreamDataSourceFactory { ByteArrayDataSource(bytes) }.createDataSource()
        try {
            reader.open(DataSpec.Builder().setUri(url).build())
            reader.read(ByteArray(256), 0, 256)
            withTimeout(5_000) { withContext(Dispatchers.IO) {
                CacheWriter(writer, DataSpec.Builder().setUri(url).build(), null, null).cache()
            } }
            assertTrue(cache.isCached(url, 0, bytes.size.toLong()))
            reader.close()
            val offline = playbackDataSources(RuntimeEnvironment.getApplication(), cache,
                readOnlyCache = true).let { it as CacheDataSource.Factory }
                .setUpstreamDataSourceFactory { object : DataSource {
                    override fun addTransferListener(listener: TransferListener) {}
                    override fun open(spec: DataSpec): Long = throw IOException("Offline")
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw IOException("Offline")
                    override fun getUri(): Uri? = null
                    override fun close() {}
                } }.createDataSource()
            try {
                offline.open(DataSpec.Builder().setUri(url).build())
                val result = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(512)
                while (true) {
                    val count = offline.read(buffer, 0, buffer.size)
                    if (count == -1) break
                    result.write(buffer, 0, count)
                }
                assertArrayEquals(bytes, result.toByteArray())
            } finally { offline.close() }
        } finally { reader.close(); writer.close(); cache.release(); directory.deleteRecursively() }
    }

    @Test fun wholeCurrentAndFiveUpcomingSongsAreCachedAndEvictionIsDetected() = runBlocking {
        val directory = java.io.File(RuntimeEnvironment.getApplication().cacheDir, "priority-${System.nanoTime()}")
        val cache = SimpleCache(directory, NoOpCacheEvictor())
        val opens = java.util.Collections.synchronizedList(mutableListOf<String>())
        val factory = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory {
            object : DataSource {
                val delegate = ByteArrayDataSource(ByteArray(8_192))
                override fun addTransferListener(listener: TransferListener) = delegate.addTransferListener(listener)
                override fun open(spec: DataSpec): Long { opens.add(spec.uri.toString()); return delegate.open(spec) }
                override fun read(buffer: ByteArray, offset: Int, length: Int) = delegate.read(buffer, offset, length)
                override fun getUri() = delegate.uri
                override fun close() = delegate.close()
            }
        }
        val childScope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val preloader = UpcomingAudioPreloader(factory, childScope, policyOverride = { PreloadPolicy(5, -1) })
        val current = "https://audio.example/current"
        val next = (1..5).map { "https://audio.example/next$it" }
        suspend fun finish() { withTimeout(5_000) { childScope.coroutineContext[Job]!!.children.toList().forEach { it.join() } } }
        try {
            preloader.update(next, buffering = true, currentUrl = current); finish()
            assertEquals(listOf(current), opens.toList())
            assertTrue(cache.isCached(current, 0, 8_192))
            preloader.update(next, currentUrl = current); finish()
            next.forEach { assertTrue(cache.isCached(it, 0, 8_192)) }
            cache.removeResource(current)
            opens.clear()
            preloader.update(next, currentUrl = current); finish()
            assertEquals(listOf(current), opens.toList())
            assertTrue(cache.isCached(current, 0, 8_192))
        } finally {
            preloader.clear(); childScope.coroutineContext[Job]?.cancelAndJoin()
            cache.release(); directory.deleteRecursively()
        }
    }

    @Test fun cachePressureRetainsPlayingAudioAndReleasesItAfterSongChanges() {
        val directory = java.io.File(RuntimeEnvironment.getApplication().cacheDir, "pin-${System.nanoTime()}")
        val evictor = PlayingAudioCacheEvictor(8_192)
        val cache = SimpleCache(directory, evictor)
        fun cacheSong(url: String) {
            val source = CacheDataSource.Factory().setCache(cache)
                .setUpstreamDataSourceFactory { ByteArrayDataSource(ByteArray(4_096)) }.createDataSource()
            try { CacheWriter(source, DataSpec.Builder().setUri(url).build(), null, null).cache() }
            finally { source.close() }
        }
        try {
            evictor.playingKey = "https://audio.example/current"
            cacheSong(evictor.playingKey!!)
            repeat(5) { cacheSong("https://audio.example/next$it") }
            assertTrue(cache.isCached(evictor.playingKey!!, 0, 4_096))
            assertTrue(cache.cacheSpace <= 8_192)
            val previous = evictor.playingKey!!
            evictor.playingKey = "https://audio.example/new"
            cacheSong(evictor.playingKey!!)
            cacheSong("https://audio.example/later")
            assertFalse(cache.isCached(previous, 0, 4_096))
        } finally { cache.release(); directory.deleteRecursively() }
    }

    @Test fun partialWarmPromotesToWholeCurrentSong() = runBlocking {
        val directory = java.io.File(RuntimeEnvironment.getApplication().cacheDir, "full-${System.nanoTime()}")
        val cache = SimpleCache(directory, NoOpCacheEvictor())
        val childScope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val preloader = UpcomingAudioPreloader(CacheDataSource.Factory().setCache(cache)
            .setUpstreamDataSourceFactory { ByteArrayDataSource(ByteArray(8_192)) },
            childScope, policyOverride = { PreloadPolicy(1, 512) })
        val url = "https://audio.example/current"
        suspend fun finish() { withTimeout(5_000) {
            childScope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
        } }
        try {
            preloader.update(listOf(url)); finish()
            assertEquals(512L, cache.getCachedBytes(url, 0, 8_192))
            preloader.update(emptyList(), currentUrl = url); finish()
            assertEquals(8_192L, cache.getCachedBytes(url, 0, 8_192))
        } finally {
            preloader.clear()
            childScope.coroutineContext[Job]?.cancelAndJoin()
            cache.release(); directory.deleteRecursively()
        }
    }
    @Test fun failedBackgroundWarmRetriesAndRetainsCachedAudio() = runBlocking {
        val directory = java.io.File(RuntimeEnvironment.getApplication().cacheDir, "preload-${System.nanoTime()}")
        val cache = SimpleCache(directory, NoOpCacheEvictor())
        val opened = AtomicInteger()
        val closed = AtomicInteger()
        val serverAvailable = AtomicBoolean(false)
        val upstream = DataSource.Factory {
            object : DataSource {
                private val bytes = ByteArrayDataSource(ByteArray(1_024) { 3 })
                override fun addTransferListener(listener: TransferListener) = bytes.addTransferListener(listener)
                override fun open(spec: DataSpec): Long {
                    opened.incrementAndGet()
                    if (!serverAvailable.get()) throw IOException("Server unavailable")
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
            withTimeout(5_000) { childScope.coroutineContext[Job]!!.children.toList().forEach { it.join() } }
            val failedRequests = opened.get()
            assertTrue(failedRequests > 0)
            now = 4_000
            preloader.update(listOf(url))
            withTimeout(5_000) { childScope.coroutineContext[Job]!!.children.toList().forEach { it.join() } }
            assertEquals(failedRequests, opened.get())
            serverAvailable.set(true)
            now = 5_000
            preloader.update(listOf(url))
            withTimeout(5_000) { childScope.coroutineContext[Job]!!.children.toList().forEach { it.join() } }
            assertTrue(cache.isCached(url, 0, 1_024))
            assertTrue(opened.get() > failedRequests)
            val recoveredRequests = opened.get()
            now = 120_000
            preloader.update(listOf(url))
            withTimeout(5_000) { childScope.coroutineContext[Job]!!.children.toList().forEach { it.join() } }
            assertEquals(recoveredRequests, opened.get())
            assertTrue(cache.isCached(url, 0, 1_024))
        } finally {
            preloader.clear()
            childScope.coroutineContext[Job]?.cancelAndJoin()
            cache.release()
            directory.deleteRecursively()
        }
    }
}
