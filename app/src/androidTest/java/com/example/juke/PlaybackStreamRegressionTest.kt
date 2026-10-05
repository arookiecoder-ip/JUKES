package com.example.juke

import android.os.SystemClock
import android.content.ComponentName
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import com.example.juke.models.Track
import com.example.juke.services.PlaybackManager
import com.example.juke.services.PlaybackService
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.TimeUnit
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
        val base = android.net.Uri.parse(Backend.audioBaseUrl)
        val uri = android.net.Uri.parse(deviceAudioUrl(Backend.audioBaseUrl, "Yq4tcnH4bxg"))
        val legacyMatch = uri.host == base.host && uri.scheme == base.scheme && uri.port == base.port &&
            uri.path?.startsWith("/audio/") == true
        println("Configured audio authentication: legacyMatch=$legacyMatch currentMatch=${com.example.juke.network.isBackendAudioRequest(Backend.audioBaseUrl, uri.toString())}")
        // Same public sample used by the server's cookie test. This player does not report
        // listening history or issue Echo/queue commands, and cannot use Library downloads.
        playAndAdvance(Backend.audioBaseUrl, Backend.apiKey,
            deviceAudioUrl(Backend.audioBaseUrl, "Yq4tcnH4bxg"), 210_000)
    }

    @Test fun configuredServerPlaysThroughAppServiceAndController() {
        val prefs = context.getSharedPreferences("music_settings_prefs", android.content.Context.MODE_PRIVATE)
        val originalOutput = prefs.getString("playback_output", null)
        prefs.edit().putString("playback_output", "PHONE").commit()
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                lateinit var connection: ListenableFuture<MediaController>
                instrumentation.runOnMainSync {
                    connection = MediaController.Builder(context,
                        SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
                }
                val controller = connection.get(20, TimeUnit.SECONDS)
                val manager = PlaybackManager.getInstance(context)
                try {
                    instrumentation.runOnMainSync {
                        manager.setQueue(listOf(Track(uuid = "online-regression-${UUID.randomUUID()}",
                            title = "Online playback regression", artist = "", durationSec = 240,
                            localUri = deviceAudioUrl(Backend.audioBaseUrl, "Yq4tcnH4bxg"), isStream = true)))
                    }
                    assertAdvances(controller, 210_000) { PlaybackService.StreamCacheManager.getCache(context).cacheSpace }
                } finally {
                    instrumentation.runOnMainSync { controller.stop(); controller.clearMediaItems(); controller.release(); manager.release() }
                }
            }
        } finally {
            prefs.edit().apply {
                if (originalOutput == null) remove("playback_output") else putString("playback_output", originalOutput)
            }.commit()
        }
    }

    @Test fun largeQueueShuffleAndReorderPreserveEverySongAndCurrentItem() {
        val audio = File(context.cacheDir, "large-queue-fixture.wav").apply { writeBytes(AudioServer.audioSample()) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                lateinit var connection: ListenableFuture<MediaController>
                instrumentation.runOnMainSync {
                    connection = MediaController.Builder(context,
                        SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
                }
                val controller = connection.get(20, TimeUnit.SECONDS)
                val manager = PlaybackManager.getInstance(context)
                val tracks = (0 until 2_000).map { Track("large-$it", "Song $it", "Artist", durationSec = 4,
                    localUri = audio.toURI().toString(), isStream = false) }
                try {
                    instrumentation.runOnMainSync { manager.setQueue(tracks, 100, playWhenReady = false) }
                    awaitQueue(controller, 2_000, tracks[100].uuid)
                    instrumentation.runOnMainSync { manager.toggleShuffle() }
                    awaitQueue(controller, 2_000, tracks[100].uuid) { player ->
                        (101 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId } != tracks.drop(101).map { it.uuid }
                    }
                    instrumentation.runOnMainSync {
                        val ids = (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId }
                        assertEquals(tracks.map { it.uuid }.toSet(), ids.toSet())
                        assertEquals(tracks.take(101).map { it.uuid }, ids.take(101))
                        assertNotEquals(tracks.drop(101).map { it.uuid }, ids.drop(101))
                        manager.replaceUpcoming(tracks.take(101) + tracks.drop(101).reversed(), 100)
                    }
                    val deadline = SystemClock.elapsedRealtime() + 10_000
                    var reversed = false
                    while (!reversed && SystemClock.elapsedRealtime() < deadline) {
                        instrumentation.runOnMainSync { reversed = controller.getMediaItemAt(101).mediaId == tracks.last().uuid }
                        if (!reversed) SystemClock.sleep(50)
                    }
                    assertTrue("Large queue reorder never reached the player", reversed)
                    awaitQueue(controller, 2_000, tracks[100].uuid)
                } finally {
                    instrumentation.runOnMainSync { controller.stop(); controller.clearMediaItems(); controller.release(); manager.release() }
                }
            }
        } finally { audio.delete() }
    }
    private fun awaitQueue(controller: MediaController, count: Int, currentId: String, matches: (MediaController) -> Boolean = { true }) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = controller.mediaItemCount == count && controller.currentMediaItem?.mediaId == currentId && matches(controller) }
            if (ready) return
            SystemClock.sleep(50)
        }
        fail("Large queue did not preserve all $count songs and the current item")
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
            try {
                assertAdvances(player!!, timeoutMs) { cache.cacheSpace }
            } catch (failure: AssertionError) {
                val backend = android.net.Uri.parse(base)
                val request = android.net.Uri.parse(uri)
                val legacyMatch = request.host == backend.host && request.scheme == backend.scheme &&
                    request.port == backend.port && request.path?.startsWith("/audio/") == true
                throw AssertionError("${failure.message}; legacyAuthMatch=$legacyMatch authMatch=${com.example.juke.network.isBackendAudioRequest(base, uri)}", failure)
            }
        } finally {
            instrumentation.runOnMainSync { player?.release() }
            cache.release(); directory.deleteRecursively()
        }
    }

    private fun assertAdvances(player: Player, timeoutMs: Long, cacheBytes: () -> Long) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var state = Player.STATE_IDLE
        var requested = false
        var position = 0L
        while (SystemClock.elapsedRealtime() < deadline) {
            var error: PlaybackException? = null
            var playing = false
            instrumentation.runOnMainSync {
                error = player.playerError; playing = player.isPlaying
                position = player.currentPosition; state = player.playbackState
                requested = player.playWhenReady
            }
            error?.let { failure ->
                val cause = generateSequence<Throwable>(failure) { it.cause }
                    .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
                fail("Online player error ${failure.errorCodeName}; HTTP status=${cause?.responseCode ?: "none"}")
            }
            if (playing && position >= 300) return
            SystemClock.sleep(50)
        }
        fail("Online player stalled: state=$state playWhenReady=$requested position=$position cacheBytes=${cacheBytes()}")
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
            fun audioSample(): ByteArray {
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
