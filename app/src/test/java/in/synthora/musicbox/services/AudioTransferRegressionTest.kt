package `in`.synthora.musicbox.services

import android.app.Application
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.*
import androidx.media3.datasource.cache.*
import androidx.media3.extractor.*
import androidx.media3.extractor.mp4.Mp4Extractor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.EOFException
import java.io.IOException
import java.net.ServerSocket
import kotlin.concurrent.thread

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class AudioTransferRegressionTest {
    private val url = "https://audio.example/song"
    private val audio = ByteArray(600_000) { (it % 251).toByte() }
    private val spec get() = DataSpec.Builder().setUri(url).setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION).build()
    private fun withCache(block: (SimpleCache) -> Unit) {
        val directory = java.io.File(RuntimeEnvironment.getApplication().cacheDir, "audio-transfer-${System.nanoTime()}")
        val cache = SimpleCache(directory, NoOpCacheEvictor())
        try { block(cache) } finally { cache.release(); directory.deleteRecursively() }
    }
    private inner class Response(private val body: ByteArray = audio, private val total: Int = audio.size,
        private val tag: String = "\"version-a\"", private val unknown: Boolean = false) : DataSource {
        var index = 0
        var limit = 0
        var request: DataSpec? = null
        override fun addTransferListener(listener: TransferListener) {}
        override fun open(spec: DataSpec): Long {
            request = spec; index = spec.position.toInt()
            limit = minOf(total, if (spec.length < 0) total else index + spec.length.toInt())
            return if (unknown) -1 else (limit - index).toLong()
        }
        override fun getUri(): Uri? = request?.uri
        override fun getResponseHeaders(): Map<String, List<String>> {
            val result = mutableMapOf("Content-Type" to listOf("audio/mp4"), "ETag" to listOf(tag))
            if (!unknown) {
                result["Content-Length"] = listOf((limit - requireNotNull(request).position).toString())
                if (requireNotNull(request).position > 0 || requireNotNull(request).length > 0)
                    result["Content-Range"] = listOf("bytes ${requireNotNull(request).position}-${limit - 1}/$total")
            }
            return result
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val n = minOf(length, 8_192, limit - index, body.size - index)
            if (n <= 0) return C.RESULT_END_OF_INPUT
            body.copyInto(buffer, offset, index, index + n); index += n; return n
        }
        override fun close() {}
    }
    private fun writer(cache: Cache, source: DataSource) = CacheDataSource.Factory().setCache(cache)
        .setUpstreamDataSourceFactory { source }.setCacheWriteDataSinkFactory(
            CacheDataSink.Factory().setCache(cache).setFragmentSize(262_144)).createDataSource()
    private fun fill(cache: Cache) {
        val source = writer(cache, ValidatingAudioDataSource(Response(), cache))
        try { CacheWriter(source, spec, null, null).cache() } finally { source.close() }
    }
    private fun readAll(source: DataSource): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4_096)
        while (true) { val n = source.read(buffer, 0, buffer.size); if (n < 0) break; if (n > 0) out.write(buffer, 0, n) }
        return out.toByteArray()
    }

    @Test fun earlyEofCannotShrinkCacheLengthAndRetryRetainsAlreadySavedBytes() = withCache { cache ->
        val source = writer(cache, ValidatingAudioDataSource(Response(audio.copyOf(300_000)), cache))
        try { CacheWriter(source, spec, null, null).cache(); fail("Incomplete transfer was marked complete") }
        catch (_: EOFException) {} finally { source.close() }
        assertEquals(audio.size.toLong(), ContentMetadata.getContentLength(cache.getContentMetadata(url)))
        assertTrue(cache.getCachedBytes(url, 0, audio.size.toLong()) > 0)
        assertFalse(cache.isCached(url, 0, audio.size.toLong()))
        fill(cache)
        val offline = CacheDataSource.Factory().setCache(cache).createDataSource()
        try { offline.open(spec); assertArrayEquals(audio, readAll(offline)) } finally { offline.close() }
    }

    @Test fun realHttpSocketClosingEarlyIsAnErrorRatherThanACompletedResource() {
        val server = ServerSocket(0)
        val sender = thread(isDaemon = true) {
            server.accept().use { socket ->
                socket.soTimeout = 5_000
                val reader = socket.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) {}
                socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: audio/mp4\r\nContent-Length: 600000\r\nConnection: close\r\n\r\n".toByteArray())
                socket.getOutputStream().write(audio, 0, 4_096)
                socket.getOutputStream().flush()
            }
        }
        val source = ValidatingAudioDataSource(DefaultHttpDataSource.Factory().setReadTimeoutMs(5_000).createDataSource())
        try {
            source.open(DataSpec.Builder().setUri("http://127.0.0.1:${server.localPort}/audio").build())
            try { readAll(source); fail("Early HTTP EOF was accepted") } catch (_: EOFException) {}
        } finally { source.close(); server.close(); sender.join(5_000); assertFalse(sender.isAlive) }
    }

    @Test fun boundedRangesAndUnknownLengthStreamsStillEndNormally() {
        for (unknown in listOf(false, true)) {
            val source = ValidatingAudioDataSource(Response(unknown = unknown))
            val request = if (unknown) spec else spec.buildUpon().setPosition(51).setLength(23).build()
            try { source.open(request); assertArrayEquals(if (unknown) audio else audio.copyOfRange(51, 74), readAll(source)) }
            finally { source.close() }
        }
    }

    @Test fun aValidRangeClampedAtFileEndIsNotMistakenForATruncatedTransfer() {
        val response = Response()
        val source = ValidatingAudioDataSource(object : DataSource by response {
            override fun open(spec: DataSpec): Long { response.open(spec); return spec.length }
            override fun getResponseHeaders() = response.responseHeaders
        })
        val request = spec.buildUpon().setPosition(audio.size - 5L).setLength(20).build()
        try { assertEquals(5L, source.open(request)); assertArrayEquals(audio.takeLast(5).toByteArray(), readAll(source)) }
        finally { source.close() }
    }

    @Test fun aMisalignedContentRangeCannotSupplyBytesAtTheWrongOffset() = withCache { cache ->
        val response = Response()
        val source = ValidatingAudioDataSource(object : DataSource by response {
            override fun getResponseHeaders() = response.responseHeaders +
                mapOf("Content-Range" to listOf("bytes 50-99/${audio.size}"))
        }, cache)
        try { source.open(spec.buildUpon().setPosition(51).setLength(49).build()); fail("Misaligned range accepted") }
        catch (_: AudioRepresentationChangedException) {} finally { source.close() }
    }

    @Test fun changedServerFileCannotBeSplicedIntoAnExistingPartialCache() = withCache { cache ->
        val first = writer(cache, ValidatingAudioDataSource(Response(), cache))
        try { CacheWriter(first, spec.buildUpon().setLength(100_000).build(), null, null).cache() } finally { first.close() }
        val before = cache.getCachedBytes(url, 0, audio.size.toLong())
        val response = Response(tag = "\"version-b\"")
        val second = writer(cache, ValidatingAudioDataSource(response, cache))
        try { CacheWriter(second, spec, null, null).cache(); fail("Mixed representations were accepted") }
        catch (_: AudioRepresentationChangedException) {} finally { second.close() }
        assertEquals(before, cache.getCachedBytes(url, 0, audio.size.toLong()))
        assertEquals("\"version-a\"", requireNotNull(response.request).httpRequestHeaders["If-Range"])
        cache.removeResource(url); clearAudioRepresentation(cache, url)
        val fresh = writer(cache, ValidatingAudioDataSource(Response(tag = "\"version-b\""), cache))
        try { CacheWriter(fresh, spec, null, null).cache() } finally { fresh.close() }
        assertTrue(cache.isCached(url, 0, audio.size.toLong()))
    }

    @Test fun completedCacheReplacesFailedSocketWithoutLosingOrRepeatingAnyByte() = withCache { cache ->
        var index = 0
        var warmed = false
        val network = object : DataSource {
            override fun addTransferListener(listener: TransferListener) {}
            override fun open(spec: DataSpec): Long { index = spec.position.toInt(); return (audio.size - index).toLong() }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (index >= 81_920) {
                    fill(cache); warmed = true
                    throw IOException("Network disconnected after disk writer completed")
                }
                val n = minOf(length, 8_192)
                audio.copyInto(buffer, offset, index, index + n); index += n; return n
            }
            override fun getUri() = Uri.parse(url)
            override fun close() {}
        }
        val source = CachedAudioFallbackDataSource(network, cache)
        try { source.open(spec); assertArrayEquals(audio, readAll(source)); assertTrue(warmed) } finally { source.close() }
    }

    @Test fun completedCacheStopsSocketReadsBeforeNextNetworkFailure() = withCache { cache ->
        val network = Response()
        val source = CachedAudioFallbackDataSource(network, cache)
        try {
            source.open(spec)
            val prefix = ByteArray(77); assertEquals(77, source.read(prefix, 0, prefix.size))
            fill(cache)
            assertArrayEquals(audio, prefix + readAll(source))
            assertEquals(77, network.index)
        } finally { source.close() }
    }

    @Test fun partialCacheCannotConcealTransferFailureAndSourceCanBeReopened() = withCache { cache ->
        val source = CachedAudioFallbackDataSource(ValidatingAudioDataSource(Response(audio.copyOf(1_000)), cache), cache)
        repeat(2) {
            try { source.open(spec); readAll(source); fail("Partial data concealed EOF") }
            catch (_: EOFException) {} finally { source.close() }
        }
    }

    @Test fun aChangedRepresentationIsNeverConcealedByFallbackToOldCache() = withCache { cache ->
        fill(cache)
        val source = CachedAudioFallbackDataSource(ValidatingAudioDataSource(Response(tag = "\"version-b\""), cache), cache)
        try { source.open(spec); fail("New audio was joined with old cache") }
        catch (_: AudioRepresentationChangedException) {} finally { source.close() }
    }

    @Test fun realMp4RemainsParseableToItsLastSampleAfterInterruptedDownloadAndOfflineRetry() = withCache { cache ->
        val mp4 = requireNotNull(javaClass.getResourceAsStream("/audio/cache-recovery.m4a")).use { it.readBytes() }
        fun save(bytes: ByteArray) {
            val source = writer(cache, ValidatingAudioDataSource(Response(bytes, mp4.size), cache))
            try { CacheWriter(source, spec, null, null).cache() } finally { source.close() }
        }
        try { save(mp4.copyOf(mp4.size / 2)); fail("Half an MP4 was accepted") } catch (_: EOFException) {}
        save(mp4)
        checkCompletedAudioCache(cache, url)
        var samples = 0
        var lastTime = -1L
        var mime: String? = null
        val extractor = Mp4Extractor()
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = object : ForwardingTrackOutput(DiscardingTrackOutput()) {
                override fun format(format: androidx.media3.common.Format) { mime = format.sampleMimeType }
                override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, crypto: TrackOutput.CryptoData?) {
                    samples++; lastTime = timeUs
                }
            }
            override fun endTracks() {}
            override fun seekMap(map: SeekMap) {}
        })
        val offline = CacheDataSource.Factory().setCache(cache).createDataSource()
        try {
            offline.open(spec)
            var input = DefaultExtractorInput(offline, 0, mp4.size.toLong())
            val seek = PositionHolder()
            var iterations = 0
            while (++iterations < 10_000) {
                when (extractor.read(input, seek)) {
                    Extractor.RESULT_END_OF_INPUT -> break
                    Extractor.RESULT_SEEK -> {
                        offline.close(); offline.open(spec.buildUpon().setPosition(seek.position).build())
                        input = DefaultExtractorInput(offline, seek.position, mp4.size.toLong())
                    }
                }
            }
            assertTrue("Extractor did not finish", iterations < 10_000)
            assertEquals("audio/mp4a-latm", mime)
            assertTrue("Missing AAC samples", samples > 150)
            assertTrue("Audio ended before its last sample", lastTime >= 3_800_000)
        } finally { extractor.release(); offline.close() }
    }

    @Test fun truncatedMp4SavedByOlderVersionsIsRejectedBeforeItCanPlay() = withCache { cache ->
        val mp4 = requireNotNull(javaClass.getResourceAsStream("/audio/cache-recovery.m4a")).use { it.readBytes() }
        // Reproduce the older pipeline's early-EOF behavior: its saved length
        // becomes the shortened response length, so byte coverage appears complete.
        val source = writer(cache, Response(mp4.copyOf(mp4.size / 2), mp4.size))
        try { CacheWriter(source, spec, null, null).cache() } finally { source.close() }
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(url))
        assertTrue(length > 0 && length < mp4.size)
        assertTrue(cache.isCached(url, 0, length))
        val reader = playbackDataSources(RuntimeEnvironment.getApplication(), cache, readOnlyCache = true).createDataSource()
        try { reader.open(spec); fail("Old incomplete MP4 was accepted") }
        catch (_: InvalidCachedAudioException) {} finally { reader.close() }
        assertTrue(hasInvalidCachedAudio(IOException(InvalidCachedAudioException())))
    }

    @Test fun legalExtendedAndOpenEndedMp4BoxesAreAcceptedButOversizedBoxesAreNot() = withCache { cache ->
        fun box(type: String, bytes: ByteArray = byteArrayOf()): ByteArray =
            java.nio.ByteBuffer.allocate(8 + bytes.size).putInt(8 + bytes.size).put(type.toByteArray()).put(bytes).array()
        val ftyp = box("ftyp", "M4A ".toByteArray())
        val moov = box("moov")
        val extended = java.nio.ByteBuffer.allocate(17).putInt(1).put("mdat".toByteArray()).putLong(17).put(7).array()
        val openEnded = java.nio.ByteBuffer.allocate(9).putInt(0).put("mdat".toByteArray()).put(7).array()
        for (tail in listOf(extended, openEnded, extended.copyOf(15))) {
            cache.removeResource(url); clearAudioRepresentation(cache, url)
            val bytes = ftyp + moov + tail
            val source = writer(cache, ByteArrayDataSource(bytes))
            try { CacheWriter(source, spec, null, null).cache() } finally { source.close() }
            try { checkCompletedAudioCache(cache, url); assertTrue(tail.size >= 17 || tail === openEnded) }
            catch (_: InvalidCachedAudioException) { assertEquals(15, tail.size) }
        }
    }

    @Test fun longFragmentedMp4HasNoArtificialBoxCountLimit() = withCache { cache ->
        val bytes = java.io.ByteArrayOutputStream()
        fun box(type: String, payload: Int = 0) {
            bytes.write(java.nio.ByteBuffer.allocate(8 + payload).putInt(8 + payload).put(type.toByteArray())
                .put(ByteArray(payload)).array())
        }
        box("ftyp", 8); box("moov")
        repeat(5_100) { box("moof"); box("mdat", 1) }
        val source = writer(cache, ByteArrayDataSource(bytes.toByteArray()))
        try { CacheWriter(source, spec, null, null).cache() } finally { source.close() }
        checkCompletedAudioCache(cache, url)
        assertEquals(bytes.size().toLong(), cache.getContentMetadata(url).get(AUDIO_INTEGRITY_LENGTH, -1L))
    }
}
