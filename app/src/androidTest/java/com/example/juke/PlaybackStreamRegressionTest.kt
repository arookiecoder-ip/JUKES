package com.example.juke

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.example.juke.network.Backend
import com.example.juke.network.deviceAudioUrl
import com.example.juke.services.playbackDataSources
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.math.sin

/** Actual HTTP fetching, authentication, cache reads and decoding, with no Library downloads. */
@UnstableApi
class PlaybackStreamRegressionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun uncachedHttpAudioPlaysThroughProductionPipeline() {
        AudioServer().use { server ->
            playAndAdvance(server.base, "fixture-key", deviceAudioUrl(server.base, "abcdefghijk"), 20_000)
            assertTrue("Player never requested HTTP audio", server.requests.isNotEmpty())
            assertTrue("Missing audio authentication", server.requests.all { it["x-api-key"] == "fixture-key" })
        }
    }

    @Test fun configuredAudioPathPrefixStillAuthenticatesAndPlays() {
        AudioServer().use { server ->
            val base = server.base + "/music"
            playAndAdvance(base, "fixture-key", deviceAudioUrl(base, "abcdefghijk"), 20_000)
            assertTrue(server.requests.all { it["x-api-key"] == "fixture-key" })
        }
    }

    @Test fun configuredServerPlaysOnlineWithEmptyDeviceCache() {
        assertTrue("Audio API key is missing from this build", Backend.apiKey.isNotBlank())
        // Same public sample used by the server's cookie test. This player does not report
        // listening history or issue Echo/queue commands, and cannot use Library downloads.
        playAndAdvance(Backend.audioBaseUrl, Backend.apiKey,
            deviceAudioUrl(Backend.audioBaseUrl, "Yq4tcnH4bxg"), 210_000)
    }

    private fun playAndAdvance(base: String, key: String, uri: String, timeoutMs: Long) {
        val directory = File(context.cacheDir, "online-player-test-${UUID.randomUUID()}")
        val cache = SimpleCache(directory, LeastRecentlyUsedCacheEvictor(4L * 1024 * 1024), StandaloneDatabaseProvider(context))
        var player: ExoPlayer? = null
        try {
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(
                    playbackDataSources(context, cache, base, key))).build().apply {
                    setMediaItem(MediaItem.fromUri(uri)); prepare(); play()
                }
            }
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            var state = Player.STATE_IDLE
            var requested = false
            var position = 0L
            while (SystemClock.elapsedRealtime() < deadline) {
                var error: PlaybackException? = null
                var playing = false
                instrumentation.runOnMainSync {
                    error = player!!.playerError; playing = player!!.isPlaying
                    position = player!!.currentPosition; state = player!!.playbackState
                    requested = player!!.playWhenReady
                }
                error?.let { failure ->
                    val cause = generateSequence<Throwable>(failure) { it.cause }
                        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
                    fail("Online player error ${failure.errorCodeName}; HTTP status=${cause?.responseCode ?: "none"}")
                }
                if (playing && position >= 300) return
                SystemClock.sleep(50)
            }
            fail("Online player stalled: state=$state playWhenReady=$requested position=$position cacheBytes=${cache.cacheSpace}")
        } finally {
            instrumentation.runOnMainSync { player?.release() }
            cache.release(); directory.deleteRecursively()
        }
    }

    private class AudioServer : AutoCloseable {
        private val socket = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        val base = "http://127.0.0.1:${socket.localPort}"
        val requests = CopyOnWriteArrayList<Map<String, String>>()
        private val data = audioSample()
        private val worker = thread(isDaemon = true) {
            while (!socket.isClosed) {
                val connection = try { socket.accept() } catch (_: java.io.IOException) { break }
                connection.use {
                    it.soTimeout = 5_000
                    val input = it.getInputStream().bufferedReader()
                    val request = input.readLine().orEmpty()
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isBlank()) break
                        headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                    }
                    requests += headers
                    val output = it.getOutputStream()
                    if (headers["x-api-key"] != "fixture-key" || !request.contains("wait=1")) {
                        output.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    } else {
                        val range = headers["range"]?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull()
                        val start = (range ?: 0).coerceIn(0, data.lastIndex)
                        val status = if (range == null) "200 OK" else "206 Partial Content"
                        val rangeHeader = if (range == null) "" else "Content-Range: bytes $start-${data.lastIndex}/${data.size}\r\n"
                        output.write(("HTTP/1.1 $status\r\nContent-Type: audio/wav\r\nContent-Length: ${data.size - start}\r\n" +
                            "Accept-Ranges: bytes\r\n${rangeHeader}Connection: close\r\n\r\n").toByteArray())
                        output.write(data, start, data.size - start)
                    }
                    output.flush()
                }
            }
        }
        override fun close() { socket.close(); worker.join(1_000) }
        companion object {
            private fun audioSample(): ByteArray {
                val rate = 16_000
                val bytes = rate * 4 * 2
                return ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
                    put("RIFF".toByteArray()); putInt(bytes + 36); put("WAVEfmt ".toByteArray())
                    putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2)
                    putShort(2); putShort(16); put("data".toByteArray()); putInt(bytes)
                    repeat(bytes / 2) { putShort((sin(it * 2 * Math.PI * 440 / rate) * 8_000).toInt().toShort()) }
                }.array()
            }
        }
    }
}
