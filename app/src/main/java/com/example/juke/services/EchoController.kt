package com.example.juke.services

import android.content.SharedPreferences
import android.os.SystemClock
import com.example.juke.utils.SafeLog as Log
import com.example.juke.models.Track
import com.example.juke.network.Backend
import com.example.juke.network.AlexaBackendApi
import com.example.juke.network.BackendAuthException
import com.example.juke.network.BrowseParser
import com.example.juke.network.array
import com.example.juke.network.flag
import com.example.juke.network.metadata
import com.example.juke.network.number
import com.example.juke.network.objectOrEmpty
import com.example.juke.network.text
import com.example.juke.network.toTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class EchoDevice(val serial: String, val name: String, val online: Boolean)

/**
 * Echo playback through the server's web-remote endpoints: device list, now-playing polling
 * (every 3 s while the app is open, 10 s in the background, like the web remote), transport,
 * volume and the shared queue.
 */
class EchoController(
    private val scope: CoroutineScope,
    private val prefs: SharedPreferences,
    private val onError: (String) -> Unit,
    private val onSignedOut: () -> Unit
) {
    private val _devices = MutableStateFlow<List<EchoDevice>>(emptyList())
    val devices: StateFlow<List<EchoDevice>> = _devices.asStateFlow()

    private val _serial = MutableStateFlow(prefs.getString(KEY_SERIAL, "").orEmpty())
    val serial: StateFlow<String> = _serial.asStateFlow()

    /** Amazon session on the server: null until known. */
    private val _amazonConnected = MutableStateFlow<Boolean?>(null)
    val amazonConnected: StateFlow<Boolean?> = _amazonConnected.asStateFlow()

    private val _state = MutableStateFlow(EchoState())
    val state: StateFlow<EchoState> = _state.asStateFlow()

    private var pollJob: Job? = null
    private var pollingForeground = false
    private val pollLock = Mutex()
    private val transportLock = Mutex()
    private var transportIntent = 0L
    private var lastVolumeRefresh = 0L
    private var likedVersion: Long? = null
    private var mutationEpoch = 0L
    private var refreshedAt = 0L
    // Monotonic play-intent sequence: lets the server drop a superseded switch
    // that arrives out of order instead of playing stale tracks in turn.
    var playQueueSession = ""
        private set
    private var playIntentSeq = prefs.getLong("play_intent_seq", 0L)
    private val intentClient = prefs.getString("play_intent_client", null) ?: java.util.UUID.randomUUID().toString().also {
        prefs.edit().putString("play_intent_client", it).apply()
    }
    private fun nextPlayIntent(): Long {
        playIntentSeq = maxOf(System.currentTimeMillis(), playIntentSeq + 1)
        prefs.edit().putLong("play_intent_seq", playIntentSeq).apply()
        return playIntentSeq
    }

    // Volume slider: a drag is debounced, and server echoes are ignored for a grace window so
    // a stale poll can't snap the slider back (same rules as the web remote).
    private var volumeJob: Job? = null
    private var volumeGraceUntil = 0L
    private var volumeSeq = 0

    val selectedDevice: EchoDevice? get() = _devices.value.firstOrNull { it.serial == _serial.value }

    /** Load the Echo list and the current now-playing in one request. */
    suspend fun loadDevices(): Boolean {
        val saved = _serial.value
        val init = Backend.get("/alexa/init/", if (saved.isNotBlank()) mapOf("serial" to saved) else emptyMap()).objectOrEmpty()
        val loggedIn = init["status"].objectOrEmpty().flag("logged_in")
        _amazonConnected.value = loggedIn
        val list = init.array("devices").map { it.objectOrEmpty() }.map {
            EchoDevice(it.text("serial"), it.text("name").ifBlank { it.text("serial") }, it.flag("online"))
        }.filter { it.serial.isNotBlank() }
        _devices.value = list
        val chosen = list.firstOrNull { it.serial == saved }?.serial ?: init.text("serial").ifBlank { list.firstOrNull()?.serial.orEmpty() }
        if (chosen != _serial.value) setSerial(chosen)
        init["now_playing"]?.let { np -> if (np is JsonObject) apply(np) }
        return loggedIn && list.isNotEmpty()
    }

    /** Refresh presence without changing playback, artwork or the selected output. */
    suspend fun refreshDeviceStatus() {
        val response = Backend.get("/alexa/devices/").objectOrEmpty()
        _devices.value = response.array("devices").map { it.objectOrEmpty() }.map {
            EchoDevice(it.text("serial"), it.text("name").ifBlank { it.text("serial") }, it.flag("online"))
        }.filter { it.serial.isNotBlank() }
    }

    fun select(serial: String, refreshAfter: Boolean = true) {
        if (serial == _serial.value) return
        setSerial(serial)
        _state.value = EchoState()
        if (refreshAfter) scope.launch { safely { refresh(force = true) } }
    }

    private fun setSerial(serial: String) {
        volumeJob?.cancel()
        volumeSeq++
        volumeGraceUntil = 0L
        lastVolumeRefresh = 0L
        _serial.value = serial
        prefs.edit().putString(KEY_SERIAL, serial).apply()
    }

    /** Poll now-playing while the app is visible ([foreground]) or slower in the background. */
    fun startPolling(foreground: Boolean) {
        pollingForeground = foreground
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            var failures = 0
            var lastPresenceRefresh = 0L
            while (isActive) {
                try {
                    refresh()
                    val presenceNow = android.os.SystemClock.elapsedRealtime()
                    val presenceInterval = if (selectedDevice?.online == false) 5_000L else 30_000L
                    if (pollingForeground && presenceNow - lastPresenceRefresh >= presenceInterval) {
                        lastPresenceRefresh = presenceNow
                        safely { refreshDeviceStatus() }
                    }
                    failures = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (e: BackendAuthException) {
                    onSignedOut()
                    return@launch
                } catch (e: Exception) {
                    failures++
                    Log.w(TAG, "Now-playing poll failed")
                }
                delay(remotePollDelayMs(pollingForeground, _state.value.playing, _state.value.processing, failures))
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
        pollingForeground = false
    }

    /** One now-playing poll (and a volume read every 15 s). */
    suspend fun refresh(force: Boolean = false, stateOnly: Boolean = false) {
        val serial = _serial.value.ifBlank { return }
        if (!force && SystemClock.elapsedRealtime() - refreshedAt < 200) return
        val np = pollLock.withLock {
            val epoch = mutationEpoch
            Backend.get("/alexa/now_playing/", mapOf("serial" to serial, "queue_version" to _state.value.queueVersion.toString())).objectOrEmpty().also {
                if (serial == _serial.value && epoch == mutationEpoch) {
                    apply(it)
                    refreshedAt = SystemClock.elapsedRealtime()
                }
            }
        }
        if (serial != _serial.value) return
        np["playback_error"].objectOrEmpty().text("message").takeIf { it.isNotBlank() }?.let(onError)
        if (stateOnly) return
        if (np["liked_version"] is JsonPrimitive) {
            val version = np.number("liked_version")
            if (version != likedVersion) {
                likedVersion = version
                scope.launch { safely { AccountRepository.refreshLiked() } }
            }
        }
        val now = SystemClock.elapsedRealtime()
        if (force || now - lastVolumeRefresh > (if (_state.value.playing && pollingForeground) 15_000 else 60_000)) {
            lastVolumeRefresh = now
            // Volume/account refresh must never hold up playback confirmation.
            scope.launch { safely {
                val volume = Backend.get("/alexa/volume/", mapOf("serial" to serial)).objectOrEmpty()
                val value = (volume["volume"] as? JsonPrimitive)?.intOrNull
                if (serial == _serial.value && value != null && SystemClock.elapsedRealtime() >= volumeGraceUntil) {
                    _state.update { it.copy(volume = value.coerceIn(0, 100)) }
                }
            } }
        }
    }

    private suspend fun apply(np: JsonObject) {
        val now = SystemClock.elapsedRealtime()
        val previous = _state.value
        val preserveVolume = now < volumeGraceUntil
        val parsed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            parseEchoSnapshot(np, now, previous.volume, preserveVolume, previous.queue, previous)
        }
        if (parsed.sharedOutput.olderThan(MobileDeviceConnection.output.value)) return
        // A pause, close or volume preview during decoding wins over this response.
        _state.compareAndSet(previous, parsed)
    }

    suspend fun refreshSharedPhone() {
        val np = Backend.get("/alexa/now_playing/", mapOf("serial" to "phone",
            "queue_version" to _state.value.queueVersion.toString())).objectOrEmpty()
        apply(np)
    }

    fun phoneDisconnected(output: SharedPlaybackOutput) {
        val now = SystemClock.elapsedRealtime()
        _state.update { old -> old.withDisconnectedPhone(output, now) }
    }

    private fun requireSerial(): String =
        _serial.value.ifBlank { throw IllegalStateException("Choose an Echo device first") }

    private suspend fun send(path: String, body: JsonObject) {
        mutationEpoch++
        refreshedAt = 0
        try { Backend.post(path, body) }
        finally { mutationEpoch++; refreshedAt = 0 }
    }

    suspend fun command(action: String, refreshAfter: Boolean = true, expectedPhoneToken: String? = null) {
        val previous = _state.value
        val transport = action == "play" || action == "pause"
        val intent = ++transportIntent
        // Update before acquiring the lock: even three rapid taps must each
        // reverse the latest requested direction, not the last HTTP response.
        if (transport) _state.update { it.copy(playing = action == "play", positionMs = it.livePosition(), anchoredAt = SystemClock.elapsedRealtime()) }
        transportLock.withLock {
            try {
                send("/alexa/command/", buildJsonObject {
                    put("serial", requireSerial()); put("action", action)
                    if (expectedPhoneToken != null) {
                        put("output_owner", PhonePlaybackOwnership.ownerId); put("output_token", expectedPhoneToken)
                        put("phone_paused", true)
                    }
                })
            } catch (e: Exception) {
                if (transport && intent == transportIntent) _state.value = previous
                throw e
            } finally {
                if (refreshAfter) refreshSoon()
            }
        }
    }

    suspend fun seek(positionMs: Long, refreshAfter: Boolean = true, expectedPhoneToken: String? = null) {
        send("/alexa/seek/", buildJsonObject {
            put("serial", requireSerial()); put("position_ms", positionMs)
            if (expectedPhoneToken != null) {
                put("output_owner", PhonePlaybackOwnership.ownerId); put("output_token", expectedPhoneToken)
                put("phone_paused", true)
            }
        })
        _state.update { it.copy(positionMs = positionMs, anchoredAt = SystemClock.elapsedRealtime()) }
        if (refreshAfter) refreshSoon()
    }

    /** Volume slider: shows the value at once, sends the last value of a drag after 220 ms. */
    fun setVolume(volume: Int) {
        val value = volume.coerceIn(0, 100)
        val serial = _serial.value.ifBlank { return }
        val seq = ++volumeSeq
        volumeGraceUntil = SystemClock.elapsedRealtime() + VOLUME_GRACE_MS
        _state.update { it.copy(volume = value) }
        volumeJob?.cancel()
        volumeJob = scope.launch {
            delay(220)
            if (_serial.value != serial || prefs.getString("playback_output", "PHONE") != "ALEXA") return@launch
            try {
                send("/alexa/command/", buildJsonObject {
                    put("serial", serial); put("action", "volume"); put("value", value)
                })
                if (seq == volumeSeq) volumeGraceUntil = SystemClock.elapsedRealtime() + VOLUME_GRACE_MS
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (seq != volumeSeq) return@launch
                volumeGraceUntil = 0
                onError(e.message ?: "Couldn't change the volume")
                lastVolumeRefresh = 0
                safely { refresh(force = true) }
            }
        }
    }

    /** Replace the Echo queue with [tracks] and start at [index]. */
    suspend fun playQueue(tracks: List<Track>, index: Int) {
        val playable = tracks.filter { !it.ytVideoId.isNullOrBlank() }
        val start = tracks.getOrNull(index)
        require(playable.isNotEmpty()) { "Nothing to play" }
        val seq = nextPlayIntent()
        send("/alexa/play_queue/", buildJsonObject {
            put("serial", requireSerial())
            put("queue_items", JsonArray(playable.map { it.metadata() }))
            put("start_index", playable.indexOf(start).coerceAtLeast(0))
            put("suppress_radio", playable.size > 1)
            put("intent_seq", seq)
            put("intent_client", intentClient)
            // The app never reads this response body (state arrives via polling).
            put("brief_response", true)
        })
        refreshSoon()
    }

    /** Install the paused shared cursor, then use the same Resume path as the web player. */
    suspend fun transferQueue(tracks: List<Track>, index: Int, positionMs: Long, playing: Boolean, expectedPhoneToken: String? = null, reuseSharedQueue: Boolean = false) {
        val playable = tracks.filter { !it.ytVideoId.isNullOrBlank() }
        val selected = tracks.getOrNull(index)
        require(playable.isNotEmpty()) { "Nothing to play" }
        val targetIndex = playable.indexOf(selected).coerceAtLeast(0)
        val videoId = requireNotNull(playable[targetIndex].ytVideoId)
        startTransferredAlexaQueue(playing,
            installPaused = {
                check(expectedPhoneToken == null || PhonePlaybackOwnership.permitsPlayback(expectedPhoneToken)) { "A newer phone play superseded this switch." }
                AlexaBackendApi.updateQueue(if (reuseSharedQueue) "current" else "start", videoId,
                    if (reuseSharedQueue) emptyList() else playable.map(AlexaBackendApi::backendTrack),
                    playing = false, positionMs = positionMs.coerceAtLeast(0), queueIndex = targetIndex,
                    buffering = false, expectedToken = expectedPhoneToken ?: PhonePlaybackOwnership.token,
                    currentEntryId = playable[targetIndex].uuid)
            },
            resume = { command("play", refreshAfter = false, expectedPhoneToken = expectedPhoneToken) },
            keepPaused = { seek(positionMs, refreshAfter = false, expectedPhoneToken = expectedPhoneToken) })
        if (playing) {
            check(awaitPlaying(videoId)) {
                "The Echo did not confirm playback. Playback stayed on the original device."
            }
        } else refresh(stateOnly = true)
    }

    /** Play one song; the server keeps the queue going with its radio ([radio] forces a new one).
     *  [suppressRadio] leaves the queue as just this song: used for fast playlist
     *  starts where the remainder is backfilled right after (see MusicViewModel). */
    suspend fun playSong(track: Track, radio: Boolean, suppressRadio: Boolean = false) {
        val seq = nextPlayIntent()
        playQueueSession = java.util.UUID.randomUUID().toString()
        send("/alexa/play_queue/", JsonObject(track.metadata() + mapOf(
            "queue_session_id" to JsonPrimitive(playQueueSession),
            "serial" to JsonPrimitive(requireSerial()),
            "force_radio" to JsonPrimitive(radio),
            "suppress_radio" to JsonPrimitive(suppressRadio),
            "intent_seq" to JsonPrimitive(seq),
            "intent_client" to JsonPrimitive(intentClient)
        )))
        refreshSoon()
    }

    /** Jump to a song already in the Echo queue. */
    suspend fun playQueueIndex(track: Track, index: Int) {
        val seq = nextPlayIntent()
        send("/alexa/play_queue/", JsonObject(track.metadata() + mapOf(
            "serial" to JsonPrimitive(requireSerial()),
            "queue_index" to JsonPrimitive(index),
            "intent_seq" to JsonPrimitive(seq),
            "intent_client" to JsonPrimitive(intentClient)
        )))
        refreshSoon()
    }

    suspend fun playPlaylist(playlistId: String) {
        val seq = nextPlayIntent()
        send("/alexa/play_queue/", buildJsonObject {
            put("serial", requireSerial()); put("playlist_id", playlistId)
            put("intent_seq", seq)
            put("intent_client", intentClient)
        })
        refreshSoon()
    }

    suspend fun queueAdd(tracks: List<Track>, next: Boolean, expectedSession: String? = null) {
        val playable = tracks.filter { !it.ytVideoId.isNullOrBlank() }
        if (playable.isEmpty()) return
        val body = if (playable.size == 1) JsonObject(playable.single().metadata() + mapOf(
            "serial" to JsonPrimitive(requireSerial()),
            "position" to JsonPrimitive(if (next) "next" else "last")
        )) else buildJsonObject {
            put("serial", requireSerial())
            put("position", if (next) "next" else "last")
            put("queue_items", JsonArray(playable.map { it.metadata() }))
        }
        send("/alexa/queue_add/", if (expectedSession == null) body else JsonObject(body + ("expected_queue_session" to JsonPrimitive(expectedSession))))
        refreshSoon()
    }

    suspend fun queueRemove(index: Int, videoId: String?) {
        send("/alexa/queue_remove/", buildJsonObject {
            put("serial", requireSerial()); put("index", index)
            videoId?.let { put("video_id", it) }
        })
        refreshSoon()
    }

    suspend fun queueReorder(from: Int, to: Int) = pollLock.withLock {
        val serial = requireSerial()
        val previous = _state.value
        if (from !in previous.queue.indices || to !in previous.queue.indices || from == to) return@withLock
        val moved = previous.queue.toMutableList().apply { add(to, removeAt(from)) }
        _state.update { it.copy(queue = moved, index = queueIndexAfterMove(previous.index, from, to)) }
        try {
            send("/alexa/queue_reorder/", buildJsonObject {
                put("serial", serial); put("from_index", from); put("to_index", to)
            })
            refreshSoon()
        } catch (e: Exception) {
            _state.update { if (it.queue == moved) it.copy(queue = previous.queue, index = previous.index) else it }
            throw e
        }
    }

    suspend fun shuffle() {
        send("/alexa/shuffle_queue/", buildJsonObject { put("serial", requireSerial()) })
        refreshSoon()
    }

    /** Wait until the Echo confirms it is playing [videoId] (used when moving playback to it). */
    suspend fun awaitPlaying(videoId: String, timeoutMs: Long = 45_000): Boolean =
        withTimeoutOrNull(timeoutMs) {
            var confirmed = false
            while (!confirmed) {
                refresh(stateOnly = true)
                val s = _state.value
                check(s.sharedOutput.mode != "phone") { "Playback moved to a phone before the Echo confirmed." }
                check(s.playing || s.processing || s.track?.ytVideoId != videoId) {
                    "The Echo stopped before confirming playback. Retry the switch."
                }
                confirmed = s.confirmedOnAlexa(videoId)
                if (!confirmed) delay(250)
            }
            true
        } ?: false

    private fun refreshSoon() {
        scope.launch {
            delay(300)
            safely { refresh() }
        }
    }

    private suspend fun safely(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackendAuthException) {
            onSignedOut()
        } catch (e: Exception) {
            Log.w(TAG, "Echo refresh failed: ${e.message}")
        }
    }

    fun clear() {
        stopPolling()
        volumeJob?.cancel()
        volumeSeq++
        volumeGraceUntil = 0
        lastVolumeRefresh = 0
        likedVersion = null
        _devices.value = emptyList()
        _amazonConnected.value = null
        _state.value = EchoState()
    }

    companion object {
        private const val TAG = "EchoController"
        private const val KEY_SERIAL = "echo_serial"
        private const val VOLUME_GRACE_MS = 4_000L
    }
}
