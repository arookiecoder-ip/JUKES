package com.example.juke.viewmodels

import android.app.Application
import com.example.juke.utils.SafeLog as Log
import android.widget.Toast
import androidx.compose.ui.graphics.Color
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.palette.graphics.Palette
import androidx.room.withTransaction
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.juke.database.MusicDatabase
import com.example.juke.database.toEntity
import com.example.juke.database.toTrack
import com.example.juke.models.Track
import com.example.juke.models.fetchLyricsWithRetry
import com.example.juke.models.withUpdatedLyrics
import com.example.juke.network.AlexaBackendApi
import com.example.juke.network.flag
import com.example.juke.network.number
import com.example.juke.network.Backend
import com.example.juke.network.BackendAuthException
import com.example.juke.network.LyricsApi
import com.example.juke.network.BrowseParser
import com.example.juke.network.array
import com.example.juke.network.toTrack
import com.example.juke.network.toAppTrack
import com.example.juke.network.objectOrEmpty
import com.example.juke.network.text
import com.example.juke.network.metadata
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.example.juke.services.AccountRepository
import com.example.juke.services.EchoController
import com.example.juke.services.EchoState
import com.example.juke.services.transferPlayback
import com.example.juke.services.PlaybackManager
import com.example.juke.services.QueueManager
import com.example.juke.ui.theme.ExtractedColors
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Where music plays: the selected Echo (through the server) or this phone. */
enum class PlaybackOutput { ALEXA, PHONE }

data class MusicUiState(
    val currentTrack: Track? = null,
    val queue: List<Track> = emptyList(),
    val queueIndex: Int = -1,
    val isPlaying: Boolean = false,
    val position: Long = 0,
    val duration: Long = 0,
    val isLoading: Boolean = false,
    val error: String? = null,
    val isQueueOperationInProgress: Boolean = false,
    val isShuffleEnabled: Boolean = false,
    val repeatMode: Int = androidx.media3.common.Player.REPEAT_MODE_OFF,
    val extractedColors: ExtractedColors? = null
)

class MusicViewModel(application: Application) : AndroidViewModel(application) {

    private val database = MusicDatabase.getDatabase(application)
    private val trackDao = database.trackDao()
    val playbackManager = PlaybackManager.getInstance(application)
    private val queueManager = QueueManager.getInstance(application)
    val recStatus = queueManager.recStatus

    private val settingsPrefs = application.getSharedPreferences(
        "music_settings_prefs",
        android.content.Context.MODE_PRIVATE
    )

    private val speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
    private val _playbackSpeed = MutableStateFlow(1f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed
    fun cyclePlaybackSpeed() {
        val next = speeds[(speeds.indexOf(_playbackSpeed.value) + 1) % speeds.size]
        _playbackSpeed.value = next
        playbackManager.setPlaybackSpeed(next)
    }

    private val audioPrefs = application.getSharedPreferences(
        "audio_effects_prefs",
        android.content.Context.MODE_PRIVATE
    )

    // Skip Silence State
    private val _isSkipSilenceEnabled =
        MutableStateFlow(audioPrefs.getBoolean("skip_silence_enabled", false))
    val isSkipSilenceEnabled: StateFlow<Boolean> = _isSkipSilenceEnabled.asStateFlow()

    fun toggleSkipSilence(enabled: Boolean) {
        _isSkipSilenceEnabled.value = enabled
        audioPrefs.edit().putBoolean("skip_silence_enabled", enabled).apply()
    }

    /** Phone player state; [uiState] shows it while the output is [PlaybackOutput.PHONE]. */
    private val _uiState = MutableStateFlow(MusicUiState())

    private val _output = MutableStateFlow(
        runCatching { PlaybackOutput.valueOf(settingsPrefs.getString(KEY_OUTPUT, "") ?: "") }
            .getOrDefault(PlaybackOutput.PHONE)
    )
    val output: StateFlow<PlaybackOutput> = _output.asStateFlow()
    private val outputChosen get() = settingsPrefs.contains(KEY_OUTPUT)

    private val outputSwitchRequests = com.example.juke.services.OutputSwitchRequests()
    private val _isSwitchingOutput = MutableStateFlow(false)
    val isSwitchingOutput: StateFlow<Boolean> = _isSwitchingOutput.asStateFlow()

    /** Short messages for the user (Echo errors, queue failures). */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** The server rejected the web session; the app must show the sign-in screen. */
    private val _signedOut = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val signedOut: SharedFlow<Unit> = _signedOut.asSharedFlow()

    val echo = EchoController(
        scope = viewModelScope,
        prefs = settingsPrefs,
        onError = { _messages.tryEmit(it) },
        onSignedOut = { _signedOut.tryEmit(Unit) }
    )

    // Ticks the Echo progress while the player screen asks for updates.
    private val _tick = MutableStateFlow(0L)
    private val _colors = MutableStateFlow<ExtractedColors?>(null)
    /** Lyrics fetched for Echo songs, keyed by video id (phone songs keep theirs in the database). */
    private val _echoLyrics = MutableStateFlow<Map<String, Track>>(emptyMap())
    private var isForeground = true
    private var signedIn = false
    private var signInJob: Job? = null

    private val _echoRequests = MutableStateFlow(0)
    private val playbackBusy = combine(_isSwitchingOutput, _echoRequests, playbackManager.isBufferingFlow) { switching, requests, buffering ->
        switching || requests > 0 || (buffering && !isAlexa)
    }
    private val combinedState: StateFlow<MusicUiState> = combine(
        _output,
        _uiState,
        echo.state,
        AccountRepository.liked,
        combine(_tick, _colors, _echoLyrics) { _, colors, lyrics -> colors to lyrics }
    ) { output, phone, echoState, liked, (colors, lyrics) ->
        val state = if (output == PlaybackOutput.ALEXA) echoUiState(echoState, lyrics) else phone
        state.copy(
            currentTrack = state.currentTrack?.withLike(liked),
            queue = state.queue.map { it.withLike(liked) },
            extractedColors = colors
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MusicUiState())

    private val pendingPlayback = MutableStateFlow<Track?>(null)
    private var pendingPlaybackJob: Job? = null
    private var phoneQueueSyncJob: Job? = null
    private val phoneQueueMutex = kotlinx.coroutines.sync.Mutex()
    private var sharedPhoneQueueReady = false
    private var serverPlaybackChecked = false
    private var playbackRequestId = 0L

    val uiState: StateFlow<MusicUiState> = combine(combinedState, playbackBusy, pendingPlayback) { state, busy, pending ->
        withPendingPlayback(state.copy(isLoading = state.isLoading || busy), pending)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MusicUiState())

    private fun Track.withLike(liked: Set<String>) =
        if (isFavourite == (ytVideoId in liked)) this else copy(isFavourite = ytVideoId in liked)

    private fun echoUiState(state: EchoState, lyrics: Map<String, Track>): MusicUiState {
        fun Track.withLyrics(): Track {
            val cached = lyrics[ytVideoId] ?: return this
            return copy(
                syncedLyrics = cached.syncedLyrics, plainLyrics = cached.plainLyrics,
                romanizedSyncedLyrics = cached.romanizedSyncedLyrics,
                romanizedPlainLyrics = cached.romanizedPlainLyrics,
                lyricsOffsetMs = cached.lyricsOffsetMs
            )
        }
        val current = state.track?.withLyrics()
        return MusicUiState(
            currentTrack = current,
            queue = state.queue.map { if (it.uuid == current?.uuid) current else it },
            queueIndex = state.index,
            isPlaying = state.sharedOutput.mode != "phone" && state.playing,
            isLoading = state.sharedOutput.mode != "phone" && (state.processing || (state.playing && !state.confirmed)),
            position = state.livePosition(),
            duration = state.durationMs
        )
    }

    private val isAlexa get() = _output.value == PlaybackOutput.ALEXA

    private var hasStartedDeferredStartupWork = false

    fun saveLyricsOffset(track: Track, offsetMs: Long) {
        if (track.uuid.startsWith(ECHO_PREFIX)) {
            val videoId = track.ytVideoId ?: return
            _echoLyrics.update { it + (videoId to (it[videoId] ?: track).copy(lyricsOffsetMs = offsetMs)) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            trackDao.updateLyricsOffset(track.uuid, offsetMs)

            _uiState.update { state ->
                state.copy(
                    currentTrack = if (state.currentTrack?.uuid == track.uuid) {
                        state.currentTrack.copy(lyricsOffsetMs = offsetMs)
                    } else state.currentTrack,
                    queue = state.queue.map { queuedTrack ->
                        if (queuedTrack.uuid == track.uuid) queuedTrack.copy(lyricsOffsetMs = offsetMs)
                        else queuedTrack
                    }
                )
            }
        }
    }

    private fun updateTrackInUiState(updatedTrack: Track) {
        _uiState.update { state ->
            state.copy(
                currentTrack = if (state.currentTrack?.uuid == updatedTrack.uuid) updatedTrack else state.currentTrack,
                queue = state.queue.map { queuedTrack ->
                    if (queuedTrack.uuid == updatedTrack.uuid) updatedTrack else queuedTrack
                }
            )
        }
    }

    fun persistRomanizedLyrics(
        track: Track,
        romanizedSyncedLyrics: String?,
        romanizedPlainLyrics: String?
    ) {
        if (romanizedSyncedLyrics.isNullOrBlank() && romanizedPlainLyrics.isNullOrBlank()) {
            return
        }
        if (track.uuid.startsWith(ECHO_PREFIX)) {
            val videoId = track.ytVideoId ?: return
            _echoLyrics.update {
                val base = it[videoId] ?: track
                it + (videoId to base.copy(
                    romanizedSyncedLyrics = romanizedSyncedLyrics ?: base.romanizedSyncedLyrics,
                    romanizedPlainLyrics = romanizedPlainLyrics ?: base.romanizedPlainLyrics
                ))
            }
            return
        }

        viewModelScope.launch {
            val updatedTrack = withContext(Dispatchers.IO) {
                val latest = trackDao.getTrackByUuid(track.uuid)?.toTrack() ?: track
                val mergedTrack = latest.copy(
                    romanizedSyncedLyrics = romanizedSyncedLyrics ?: latest.romanizedSyncedLyrics,
                    romanizedPlainLyrics = romanizedPlainLyrics ?: latest.romanizedPlainLyrics
                )

                if (mergedTrack.romanizedSyncedLyrics == latest.romanizedSyncedLyrics &&
                    mergedTrack.romanizedPlainLyrics == latest.romanizedPlainLyrics
                ) {
                    null
                } else {
                    trackDao.insertTrack(mergedTrack.toEntity())
                    mergedTrack
                }
            } ?: return@launch

            queueManager.replaceTrackInQueue(track.uuid, updatedTrack)
            updateTrackInUiState(updatedTrack)
        }
    }

    /** Reorder only upcoming media items; the current track and its position are preserved. */
    private val _queueLoadError = MutableStateFlow<String?>(null)
    val queueLoadError = _queueLoadError.asStateFlow()
    fun refreshQueue() {
        viewModelScope.launch {
            _queueLoadError.value = null
            try {
                if (isAlexa) echo.refresh(force = true, stateOnly = true)
                else {
                    if (!sharedPhoneQueueReady) { synchronizePhoneQueue(); phoneQueueSyncJob?.join() }
                    if (!sharedPhoneQueueReady) return@launch
                    val current = _uiState.value.currentTrack ?: return@launch
                    phoneQueueMutex.withLock { queueManager.refreshAlexaQueue(current) }?.let { applyAlexaWindow(it) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _queueLoadError.value = com.example.juke.network.networkErrorMessage(e) ?: "Couldn't load queue. Try again." }
        }
    }

    private fun applyAlexaWindow(window: QueueManager.AlexaQueueWindow) {
        // While this phone owns output, its edits/order win over delayed remote snapshots.
        if (com.example.juke.services.PhonePlaybackOwnership.token.isNotBlank()) return
        val state = _uiState.value
        val current = state.currentTrack ?: return
        if (current.uuid != window.currentUuid || state.isQueueOperationInProgress || !sharedPhoneQueueReady || phoneQueueSyncJob?.isActive == true) return
        val currentIndex = state.queue.indexOfFirst { it.uuid == current.uuid }
        if (currentIndex < 0) return
        val queue = state.queue.take(currentIndex + 1) + window.tracks
        if (queue.map { it.uuid } == state.queue.map { it.uuid }) return
        playbackManager.replaceUpcoming(queue, currentIndex)
        _uiState.update { it.copy(queue = queue) }
    }

    init {
        playbackManager.initialize()

        viewModelScope.launch {
            queueManager.alexaQueueWindow.collect { window ->
                if (window != null && signedIn && !isAlexa && !_isSwitchingOutput.value && sharedPhoneQueueReady && phoneQueueSyncJob?.isActive != true) applyAlexaWindow(window)
            }
        }
        viewModelScope.launch {
            com.example.juke.services.PhonePlaybackOwnership.remoteOutput.collect { output ->
                if (output?.mode == "alexa" && signedIn && !_isSwitchingOutput.value) adoptRemoteAlexa(output)
            }
        }
        // While the phone plays, report its song and position to the server so the shared queue
        // (and the web remote) follow it, and pick up queue edits made elsewhere.
        viewModelScope.launch {
            while (isActive) {
                delay(3_000)
                if (!serverPlaybackChecked || com.example.juke.services.PhonePlaybackOwnership.token.isBlank()) continue
                if (signedIn && !isAlexa && !_isSwitchingOutput.value && !sharedPhoneQueueReady && phoneQueueSyncJob?.isActive != true && com.example.juke.network.NetworkFeedback.online.value) {
                    synchronizePhoneQueue(); continue
                }
                if (!signedIn || isAlexa || _isSwitchingOutput.value || !sharedPhoneQueueReady || phoneQueueSyncJob?.isActive == true || _uiState.value.isQueueOperationInProgress || !com.example.juke.network.NetworkFeedback.online.value) continue
                val state = _uiState.value
                val current = state.currentTrack ?: continue
                if (current.ytVideoId.isNullOrBlank()) continue
                val claim = com.example.juke.services.PhonePlaybackOwnership.token
                try {
                    val snapshot = AlexaBackendApi.phoneQueueSnapshot()
                    if (claim != com.example.juke.services.PhonePlaybackOwnership.token || state != _uiState.value) continue
                    val output = com.example.juke.services.sharedPlaybackOutput(snapshot)
                    if (!output.belongsToPhone(com.example.juke.services.PhonePlaybackOwnership.ownerId,
                            com.example.juke.services.PhonePlaybackOwnership.token)) {
                        if (output.mode == "alexa") adoptRemoteAlexa(output, claim)
                        continue
                    }
                    val serverIds = snapshot.array("queue").map { it.objectOrEmpty().text("video_id") }
                    if (serverIds != state.queue.map { it.ytVideoId }) {
                        synchronizePhoneQueue()
                        continue
                    }
                    // The foreground service reports position/buffering even after the Activity closes.
                    continue
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Shared queue refresh failed: ${com.example.juke.network.networkErrorMessage(e) ?: e.message}")
                }
            }
        }

        // Observe restored state and update UI with saved queue
        viewModelScope.launch {
            playbackManager.hasRestoredState.collect { hasState ->
                if (hasState) loadRestoredQueue()
            }
        }

        viewModelScope.launch {
            playbackManager.isPlayingFlow.collect { playing ->
                _uiState.update { it.copy(isPlaying = playing) }
            }
        }

        viewModelScope.launch {
            playbackManager.isShuffleEnabledFlow.collect { shuffleEnabled ->
                _uiState.update { it.copy(isShuffleEnabled = shuffleEnabled) }
            }
        }

        viewModelScope.launch {
            playbackManager.repeatModeFlow.collect { mode ->
                _uiState.update { it.copy(repeatMode = mode) }
            }
        }

        // Lyrics for whatever is playing, on the phone or the Echo.
        viewModelScope.launch {
            uiState.map { it.currentTrack }
                .distinctUntilChanged { old, new -> old?.uuid == new?.uuid }
                .collect { track ->
                    if (track != null && track.syncedLyrics.isNullOrBlank() && track.plainLyrics.isNullOrBlank()) {
                        refreshLyrics(track)
                    }
                }
        }

        // Synch UI queue with PlaybackManager source of truth
        // Resolve queue and current song from one Media3 event snapshot, never from an old index.
        viewModelScope.launch {
            playbackManager.snapshot.collectLatest { snapshot ->
                if (snapshot.currentId == null || snapshot.queueIds.isEmpty()) return@collectLatest
                val old = _uiState.value.queue.associateBy { it.uuid }
                val tracks = withContext(Dispatchers.IO) {
                    val missing = snapshot.queueIds.filter { it !in old }.chunked(500)
                        .flatMap { trackDao.getTracksByUuids(it) }.associate { it.uuid to it.toTrack() }
                    snapshot.queueIds.mapNotNull { old[it] ?: missing[it] }
                }
                if (snapshot != playbackManager.snapshot.value) return@collectLatest
                val previous = _uiState.value
                val state = reconcilePhonePlayback(previous, tracks, snapshot.currentId)
                _uiState.value = state
                if (previous.currentTrack?.uuid != state.currentTrack?.uuid) {
                    state.currentTrack?.let { track -> queueManager.onPlaybackAdvanced(track, tracks.size - state.queueIndex - 1) }
                }
            }
        }

    }

    val downloads = com.example.juke.services.DownloadRepository.get(getApplication())
    val downloadedCollections = downloads.collections
    val downloadedTracks = downloads.tracks
    val downloadProgress = downloads.progress
    fun download(track: Track) = downloads.download(track)
    fun removeDownload(track: Track) = downloads.remove(track)

    fun playDownloaded(tracks: List<Track>, index: Int) {
        if (index !in tracks.indices) return
        if (isAlexa) {
            // Offline copies always play on this device. Pause the source when reachable.
            if (com.example.juke.network.NetworkFeedback.online.value) launchEcho { echo.command("pause") }
            setOutputPreference(PlaybackOutput.PHONE)
            updatePolling()
        }
        setQueue(tracks, index)
    }

    fun startDeferredStartupWork() {
        if (hasStartedDeferredStartupWork) return
        hasStartedDeferredStartupWork = true
        // DownloadManager persists account song downloads across app upgrades.
    }

    // ---------- Account session ----------

    /**
     * Called once the web session is open: loads the Echo list and liked songs, and picks the
     * output. The first time, playback goes to the Echo when one is connected; after that the
     * last choice is kept.
     */
    fun onSignedIn() {
        signedIn = true
        serverPlaybackChecked = false
        signInJob?.cancel()
        signInJob = viewModelScope.launch {
            val initialClaim = com.example.juke.services.PhonePlaybackOwnership.token
            val initialRequest = playbackRequestId
            launch { runEcho { AccountRepository.refreshLiked() } }
            val hasEcho = try {
                echo.loadDevices()
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackendAuthException) {
                _signedOut.tryEmit(Unit)
                return@launch
            } catch (e: Exception) {
                Log.w(TAG, "Echo list failed: ${com.example.juke.network.networkErrorMessage(e) ?: e.message}")
                false
            }
            if (!outputChosen) setOutputPreference(if (hasEcho) PlaybackOutput.ALEXA else PlaybackOutput.PHONE)
            val latest = echo.state.value.sharedOutput
            if (initialRequest == playbackRequestId && initialClaim == com.example.juke.services.PhonePlaybackOwnership.token && latest.mode == "alexa" && echo.state.value.track != null) {
                adoptRemoteAlexa(latest)
            } else if (initialRequest == playbackRequestId && initialClaim == com.example.juke.services.PhonePlaybackOwnership.token && latest.mode == "phone") {
                com.example.juke.services.PhonePlaybackOwnership.restoreIfCurrent(latest)
            }
            serverPlaybackChecked = true
            updatePolling()
        }
    }

    fun onSignedOut() {
        serverPlaybackChecked = false
        com.example.juke.services.PhonePlaybackOwnership.forget()
        phoneQueueSyncJob?.cancel()
        ++playbackRequestId
        pendingPlaybackJob?.cancel()
        pendingPlayback.value = null
        signedIn = false
        outputSwitchRequests.clearPending()
        com.example.juke.services.RemotePlaybackService.stop(getApplication())
        signInJob?.cancel()
        echo.clear()
        AccountRepository.clear()
        playbackManager.pause()
    }

    /** The app moved to the foreground or background: poll the Echo every 3 s or 10 s. */
    fun setForeground(foreground: Boolean) {
        isForeground = foreground
        if (foreground && signedIn && serverPlaybackChecked) viewModelScope.launch {
            try {
                val claim = com.example.juke.services.PhonePlaybackOwnership.token
                val requestId = playbackRequestId
                val latest = AlexaBackendApi.phoneOutputStatus()
                if (requestId == playbackRequestId && latest.mode == "alexa") adoptRemoteAlexa(latest, claim)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Retain the existing output while offline. */ }
        }
        updatePolling()
    }

    private suspend fun adoptRemoteAlexa(output: com.example.juke.services.SharedPlaybackOutput, expectedClaim: String? = null) {
        if (_isSwitchingOutput.value) return
        if (expectedClaim != null && expectedClaim != com.example.juke.services.PhonePlaybackOwnership.token) return
        phoneQueueSyncJob?.cancel()
        ++playbackRequestId
        pendingPlaybackJob?.cancel()
        pendingPlayback.value = null
        playbackManager.pause()
        sharedPhoneQueueReady = false
        if (com.example.juke.services.PhonePlaybackOwnership.token.isNotBlank()) {
            try { com.example.juke.services.PhonePlaybackOwnership.releaseTo(output) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Phone is already paused; the server lease will expire. */ }
        }
        if (com.example.juke.services.PhonePlaybackOwnership.token.isNotBlank()) return
        if (output.serial.isNotBlank()) echo.select(output.serial, refreshAfter = false)
        setOutputPreference(PlaybackOutput.ALEXA)
        echo.refresh(force = true, stateOnly = true)
        updatePolling()
    }

    private fun updatePolling() {
        if (signedIn && isAlexa && echo.serial.value.isNotBlank()) {
            echo.startPolling(isForeground)
            com.example.juke.services.RemotePlaybackService.start(getApplication(), echo.state.value, isForeground)
        } else {
            echo.stopPolling()
            com.example.juke.services.RemotePlaybackService.stop(getApplication())
        }
    }

    private fun setOutputPreference(output: PlaybackOutput) {
        _output.value = output
        settingsPrefs.edit { putString(KEY_OUTPUT, output.name) }
    }

    fun refreshDevices() {
        viewModelScope.launch {
            runEcho { echo.loadDevices() }
            updatePolling()
        }
    }

    // ---------- Output switching ----------

    /**
     * Play on [serial] (an Echo) or, when null, on this phone. The current queue, song, position
     * and play/pause state move with it.
     */
    fun switchOutput(serial: String?) {
        if (_isSwitchingOutput.value) {
            outputSwitchRequests.request(serial)
            return
        }
        val toPhone = serial == null
        if (toPhone && !isAlexa) return
        if (!toPhone && isAlexa && serial == echo.serial.value) return
        phoneQueueSyncJob?.cancel()
        ++playbackRequestId
        pendingPlaybackJob?.cancel()
        pendingPlayback.value = null
        outputSwitchRequests.request(serial)
        _isSwitchingOutput.value = true
        viewModelScope.launch {
            try {
                when {
                    toPhone -> moveEchoToPhone()
                    isAlexa -> {
                        // Echo to another Echo: start the same queue there.
                        val from = echo.state.value
                        val sourceSerial = echo.serial.value
                        if (from.track == null) {
                            echo.select(serial!!, refreshAfter = false)
                            echo.refresh(force = true)
                        } else {
                            transferPlayback(
                                pauseSource = { if (from.playing) echo.command("pause", refreshAfter = false) },
                                startTarget = {
                                    echo.select(serial!!, refreshAfter = false)
                                    moveQueueToEcho(from.queue, from.index, from.track, from.livePosition(), from.playing)
                                },
                                restoreSource = {
                                    echo.select(sourceSerial)
                                    if (from.playing) echo.command("play")
                                },
                                stopTarget = { echo.command("pause") },
                                commit = { updatePolling() }
                            )
                        }
                    }
                    else -> {
                        echo.select(serial!!, refreshAfter = false)
                        movePhoneToEcho()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackendAuthException) {
                _signedOut.tryEmit(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Output switch failed: ${com.example.juke.network.networkErrorMessage(e) ?: e.message}", e)
                _messages.tryEmit(e.message ?: "Couldn't switch playback")
            } finally {
                _isSwitchingOutput.value = false
                if (!isAlexa && !sharedPhoneQueueReady) synchronizePhoneQueue()
                val next = outputSwitchRequests.finish()
                if (signedIn && next != null) switchOutput(next.serial)
            }
        }
    }

    private suspend fun moveEchoToPhone() {
        if (android.os.SystemClock.elapsedRealtime() - echo.state.value.anchoredAt > 1_000) echo.refresh(stateOnly = true)
        val state = echo.state.value
        val wasPlaying = state.playing
        val track = state.track
        if (track == null) {
            setOutputPreference(PlaybackOutput.PHONE)
            updatePolling()
            return
        }
        val queue = state.queue.ifEmpty { listOf(track) }
        val index = state.index.takeIf { it in queue.indices } ?: 0
        transferPlayback(
            pauseSource = { if (wasPlaying) echo.command("pause", refreshAfter = false) },
            startTarget = {
                phoneSetQueue(queue.map { it.copy(uuid = java.util.UUID.randomUUID().toString()) }, index,
                    positionMs = state.livePosition(), play = wasPlaying, throwOnFailure = true)
                playbackManager.awaitReady()
            },
            restoreSource = { if (wasPlaying) echo.command("play") },
            stopTarget = { playbackManager.pause() },
            commit = { setOutputPreference(PlaybackOutput.PHONE); updatePolling() }
        )
    }

    private suspend fun movePhoneToEcho() {
        val phone = _uiState.value
        val wasPlaying = playbackManager.shouldResumeAfterTrackChange()
        val position = playbackManager.getCurrentPosition()
        val track = phone.currentTrack
        if (track == null) {
            echo.refresh(force = true)
            setOutputPreference(PlaybackOutput.ALEXA)
            updatePolling()
            return
        }
        com.example.juke.services.PhonePlaybackOwnership.localHandoff = true
        try { transferPlayback(
            pauseSource = { playbackManager.pause() },
            startTarget = { moveQueueToEcho(phone.queue, phone.queueIndex, track, position, wasPlaying) },
            restoreSource = {
                com.example.juke.services.PhonePlaybackOwnership.claim(echo.serial.value)
                if (wasPlaying) playbackManager.togglePlayPause()
            },
            stopTarget = { echo.command("pause") },
            commit = { setOutputPreference(PlaybackOutput.ALEXA); updatePolling() }
        ) } finally { com.example.juke.services.PhonePlaybackOwnership.localHandoff = false }
    }

    private suspend fun moveQueueToEcho(queue: List<Track>, index: Int, track: Track, positionMs: Long, play: Boolean) {
        val items = queue.ifEmpty { listOf(track) }
        val start = index.takeIf { it in items.indices && items[it].ytVideoId == track.ytVideoId }
            ?: items.indexOfFirst { it.ytVideoId == track.ytVideoId }.coerceAtLeast(0)
        echo.transferQueue(items, start, positionMs, play)
    }

    // ---------- Colors ----------

    private var paletteJob: Job? = null

    private fun extractColors(thumbnailUri: String?) {
        paletteJob?.cancel()
        if (thumbnailUri == null) {
            _colors.value = null
            return
        }

        paletteJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val loader = getApplication<Application>().imageLoader
                val request = ImageRequest.Builder(getApplication())
                    .data(thumbnailUri)
                    .size(128)
                    .allowHardware(false) // Palette needs software bitmap
                    .build()

                val result = (loader.execute(request) as? SuccessResult)?.drawable
                val bitmap = (result as? android.graphics.drawable.BitmapDrawable)?.bitmap

                if (bitmap != null) {
                    val palette = Palette.from(bitmap).generate()
                    ensureActive()
                    val vibrant = palette.vibrantSwatch
                    val lightVibrant = palette.lightVibrantSwatch
                    val darkVibrant = palette.darkVibrantSwatch
                    val dominant = palette.dominantSwatch
                    val muted = palette.mutedSwatch

                    // Primary color priority: Vibrant -> Light Vibrant -> Dark Vibrant -> Dominant -> Muted -> Default Purple
                    val primaryInt = vibrant?.rgb
                        ?: lightVibrant?.rgb
                        ?: darkVibrant?.rgb
                        ?: dominant?.rgb
                        ?: muted?.rgb
                        ?: 0xFF6650a4.toInt()

                    // Ensure primary has enough luminance to be visible against a dark background
                    val finalPrimaryInt = if (ColorUtils.calculateLuminance(primaryInt) < 0.15) {
                        lightVibrant?.rgb
                            ?: vibrant?.rgb
                            ?: palette.lightMutedSwatch?.rgb
                            ?: 0xFF6650a4.toInt()
                    } else {
                        primaryInt
                    }
                    val finalOnPrimary = if (ColorUtils.calculateLuminance(finalPrimaryInt) > 0.35) {
                        android.graphics.Color.BLACK
                    } else {
                        vibrant?.bodyTextColor ?: android.graphics.Color.WHITE
                    }

                    val secondaryInt = darkVibrant?.rgb
                        ?: muted?.rgb
                        ?: palette.darkMutedSwatch?.rgb
                        ?: dominant?.rgb
                        ?: 0xFF625b71.toInt()

                    val tertiaryInt = lightVibrant?.rgb
                        ?: palette.lightMutedSwatch?.rgb
                        ?: dominant?.rgb
                        ?: 0xFF7D5260.toInt()

                    _colors.value = ExtractedColors(
                        primary = Color(finalPrimaryInt),
                        secondary = Color(secondaryInt),
                        tertiary = Color(tertiaryInt),
                        background = Color.Black,
                        surface = Color.Black,
                        onPrimary = Color(finalOnPrimary),
                        onSecondary = Color.White,
                        onTertiary = Color.White,
                        onBackground = Color.White,
                        onSurface = Color.White
                    )
                } else {
                    _colors.value = null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to extract colors", e)
                _colors.value = null
            }
        }
    }

    // ---------- Playback (routed to the Echo or the phone) ----------

    /** Run an Echo request, reporting failures and an expired session. */
    private suspend fun <T> runEcho(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: BackendAuthException) {
        _signedOut.tryEmit(Unit)
        null
    } catch (e: Exception) {
        Log.w(TAG, "Echo request failed: ${com.example.juke.network.networkErrorMessage(e) ?: e.message}")
        _messages.tryEmit(e.message ?: "The Echo didn't respond")
        null
    }

    private fun launchEcho(block: suspend () -> Unit) {
        viewModelScope.launch {
            _echoRequests.update { it + 1 }
            try { runEcho(block) } finally { _echoRequests.update { (it - 1).coerceAtLeast(0) } }
        }
    }

    /** Show the requested song immediately, until local preparation or Echo confirmation finishes. */
    private fun launchPlayback(track: Track, block: suspend () -> Unit) {
        val requestId = ++playbackRequestId
        pendingPlaybackJob?.cancel()
        pendingPlayback.value = track
        pendingPlaybackJob = viewModelScope.launch {
            try {
                if (isAlexa) {
                    runEcho {
                        block()
                        val videoId = requireNotNull(track.ytVideoId)
                        if (!echo.awaitPlaying(videoId)) _messages.tryEmit("The Echo hasn't confirmed playback yet")
                    }
                } else {
                    block()
                    val ready = withTimeoutOrNull(45_000) {
                        playbackManager.snapshot.first { it.currentId == track.uuid }
                        playbackManager.isBufferingFlow.first { !it }
                        true
                    }
                    if (ready != true) _messages.tryEmit("Playback is taking longer than usual")
                }
            } catch (e: CancellationException) { throw e }
            catch (e: BackendAuthException) { _signedOut.tryEmit(Unit) }
            catch (e: Exception) { _messages.tryEmit(com.example.juke.network.networkErrorMessage(e) ?: e.message ?: "Couldn't play this song") }
            finally {
                // A cancelled earlier request must not clear the next song's loading state.
                if (requestId == playbackRequestId) pendingPlayback.value = null
            }
        }
    }

    /** Play one song; the queue continues with its radio. */
    fun playYoutubeLink(link: com.example.juke.network.YoutubeLink) {
        viewModelScope.launch {
            try {
                if (link.videoId != null) {
                    val metadata = Backend.get("/api/track/${link.videoId}/metadata").objectOrEmpty()
                    playTrack(BrowseParser.item(JsonObject(metadata + mapOf("video_id" to JsonPrimitive(link.videoId)))).toTrack(AccountRepository.liked.value))
                } else if (link.playlistId != null) {
                    playCollection(BrowseParser.item(buildJsonObject { put("playlistId", link.playlistId); put("title", "YouTube playlist") }))
                }
            } catch (e: CancellationException) { throw e }
            catch (e: BackendAuthException) { _signedOut.tryEmit(Unit) }
            catch (e: Exception) { _messages.tryEmit(com.example.juke.network.networkErrorMessage(e) ?: "Couldn't play this YouTube link") }
        }
    }

    fun playTrack(track: Track) {
        if (isAlexa) {
            launchPlayback(track) { echo.playSong(track, radio = false) }
            return
        }
        launchPlayback(track) { phoneSetQueue(listOf(track), 0) }
    }

    fun startRadio(track: Track) {
        if (isAlexa) { launchPlayback(track) { echo.playSong(track, radio = true) }; return }
        launchPlayback(track) {
            phoneSetQueue(listOf(track), 0, throwOnFailure = true)
            if (_uiState.value.currentTrack?.ytVideoId == track.ytVideoId) {
                queueManager.initializeQueue(listOf(_uiState.value.currentTrack!!), isRadioMode = true)
            }
        }
    }

    private suspend fun collectionTracks(item: com.example.juke.network.BrowseItem): List<Track> {
        com.example.juke.services.offlineCollectionTracks(item, downloads.collections.value, downloads.tracks.value)?.let { return it }
        val path = if (item.kind == "album") "/api/album/${item.id}" else "/api/library/playlists/${item.playlistId.ifBlank { item.id.removePrefix("VL") }}"
        val tracks = mutableListOf<Track>()
        var offset = 0L
        do {
            val data = Backend.get(path, if (item.kind == "album") emptyMap() else mapOf("limit" to "100", "offset" to offset.toString())).objectOrEmpty()
            tracks += data.array("tracks").mapNotNull { (it as? JsonObject)?.let(BrowseParser::item) }.filter { it.videoId.isNotBlank() }.map { it.toTrack(AccountRepository.liked.value, item.image) }
            if (item.kind == "album" || !data.flag("has_more")) break
            val next = data.number("next_offset")
            check(next > offset) { "Could not load the rest of this playlist" }
            offset = next
        } while (true)
        return tracks
    }

    fun downloadCollection(item: com.example.juke.network.BrowseItem) {
        viewModelScope.launch {
            try {
                val tracks = collectionTracks(item)
                check(tracks.isNotEmpty()) { "This collection has no downloadable songs" }
                downloads.downloadCollection(item, tracks)
            } catch (e: CancellationException) { throw e }
            catch (e: BackendAuthException) { _signedOut.tryEmit(Unit) }
            catch (e: Exception) { _messages.tryEmit(e.message ?: "Couldn't download collection") }
        }
    }

    fun playCollection(item: com.example.juke.network.BrowseItem, shuffle: Boolean = false) {
        if (item.raw.flag("offline")) {
            val tracks = com.example.juke.services.offlineCollectionTracks(item, downloads.collections.value, downloads.tracks.value).orEmpty()
            if (tracks.isNotEmpty()) playDownloaded(if (shuffle) tracks.shuffled() else tracks, 0)
            else _messages.tryEmit("This collection has no completed downloads yet")
            return
        }
        viewModelScope.launch {
            _echoRequests.update { it + 1 }
            try {
                val tracks = collectionTracks(item).let { if (shuffle) it.shuffled() else it }
                check(tracks.isNotEmpty()) { "This collection is empty" }
                launchPlayback(tracks.first()) { if (isAlexa) echo.playQueue(tracks, 0) else phoneSetQueue(tracks, 0) }
            } catch (e: CancellationException) { throw e }
            catch (e: BackendAuthException) { _signedOut.tryEmit(Unit) }
            catch (e: Exception) { _messages.tryEmit(e.message ?: "Could not load collection") }
            finally { _echoRequests.update { (it - 1).coerceAtLeast(0) } }
        }
    }

    fun queueCollection(item: com.example.juke.network.BrowseItem, next: Boolean) {
        viewModelScope.launch {
            try {
                val tracks = collectionTracks(item)
                check(tracks.isNotEmpty()) { "This collection has no available songs" }
                if (next) addNext(tracks) else addToQueue(tracks)
            } catch (e: CancellationException) { throw e }
            catch (e: BackendAuthException) { _signedOut.tryEmit(Unit) }
            catch (e: Exception) { _messages.tryEmit(e.message ?: "Could not load collection") }
        }
    }

    /** Mix: replace the upcoming songs with a fresh radio from the current song. */
    fun startRadio() {
        val current = uiState.value.currentTrack ?: return
        if (isAlexa) {
            launchEcho { echo.playSong(current, radio = true) }
            return
        }
        viewModelScope.launch {
            playbackManager.keepOnlyCurrentTrack()
            queueManager.initializeQueue(listOf(current), isRadioMode = true)
            _uiState.update { it.copy(queue = listOf(current), queueIndex = 0) }
            synchronizePhoneQueue(startRadio = true)
        }
    }

    fun playTrackFromQueue(track: Track) {
        if (isAlexa) {
            val index = uiState.value.queue.indexOfFirst { it.uuid == track.uuid }
            if (index >= 0) launchPlayback(track) { echo.playQueueIndex(track, index) }
            return
        }
        launchPlayback(track) {
            val currentState = _uiState.value
            val queue = currentState.queue
            val shouldResumePlayback = playbackManager.shouldResumeAfterTrackChange()

            val trackIndex = queue.indexOfFirst { it.uuid == track.uuid }
            if (trackIndex == -1) return@launchPlayback

            playbackManager.seekToIndex(trackIndex)
            _uiState.update {
                it.copy(
                    currentTrack = track,
                    queueIndex = trackIndex,
                    isPlaying = shouldResumePlayback,
                    duration = track.durationSec.toLong() * 1000
                )
            }

            val remainingTracks = queue.drop(trackIndex)
            if (remainingTracks.isNotEmpty()) {
                queueManager.initializeQueue(queue, isRadioMode = false, preserveHistory = true)
                synchronizePhoneQueue()
            }
        }
    }

    /** Play [tracks] (a shelf, album, playlist or list) starting at [startIndex]. */
    fun setQueue(tracks: List<Track>, startIndex: Int = 0) {
        if (startIndex !in tracks.indices) return
        if (isAlexa) {
            launchPlayback(tracks[startIndex]) { echo.playQueue(tracks, startIndex) }
            return
        }
        launchPlayback(tracks[startIndex]) { phoneSetQueue(tracks, startIndex) }
    }

    /** Fetch every page before playing or shuffling a playlist; the visible preview is not the queue. */
    fun playPlaylist(playlistId: String, tracks: List<Track>, startIndex: Int = 0) {
        val selected = tracks.getOrNull(startIndex)
        viewModelScope.launch {
            try {
                val item = BrowseParser.item(buildJsonObject {
                    put("playlistId", playlistId); put("title", "Playlist")
                }, "playlists")
                val all = collectionTracks(item)
                check(all.isNotEmpty()) { "This playlist is empty" }
                val index = startIndex.takeIf { all.getOrNull(it)?.ytVideoId == selected?.ytVideoId }
                    ?: all.indexOfFirst { it.ytVideoId == selected?.ytVideoId }.coerceAtLeast(0)
                setQueue(all, index)
            } catch (e: CancellationException) { throw e }
            catch (e: BackendAuthException) { _signedOut.tryEmit(Unit) }
            catch (e: Exception) { _messages.tryEmit("Couldn't load the playlist: ${com.example.juke.network.networkErrorMessage(e) ?: e.message}") }
        }
    }

    private suspend fun publishPhoneQueue(tracks: List<Track>, index: Int, positionMs: Long, playing: Boolean, startRadio: Boolean = false) = phoneQueueMutex.withLock {
        require(index in tracks.indices)
        require(tracks.size <= 5_000) { "This queue exceeds the server's 5,000-song limit" }
        val seed = requireNotNull(tracks[index].ytVideoId)
        var items = tracks.map(AlexaBackendApi::backendTrack)
        if (startRadio && tracks.size == 1) {
            try {
                val radio = withTimeoutOrNull(8_000) { AlexaBackendApi.getRadio(seed) }.orEmpty()
                items = (items + radio.filter { it.videoId != seed }).distinctBy { it.videoId }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Log.w(TAG, "Phone radio unavailable: ${e.javaClass.simpleName}") }
        }
        // Install queue and cursor atomically on the same keyed backend used by phone reads/audio.
        // This route never dispatches an Echo command.
        AlexaBackendApi.updateQueue("start", seed, items, playing, positionMs, queueIndex = index,
            buffering = playbackManager.isBufferingFlow.value)
        coroutineContext.ensureActive()
        if (startRadio && tracks.size == 1 && items.size > 1) {
            val full = tracks + items.drop(1).map { it.toAppTrack(AlexaBackendApi.audioUrl(it.videoId)) }
            withContext(Dispatchers.IO) { trackDao.insertTracks(full.map { it.toEntity() }) }
            if (_uiState.value.currentTrack?.uuid == tracks[index].uuid) {
                playbackManager.replaceUpcoming(full, index)
                _uiState.update { it.copy(queue = full) }
            }
        }
    }

    private fun synchronizePhoneQueue(startRadio: Boolean = false) {
        if (isAlexa || !signedIn || !serverPlaybackChecked || com.example.juke.services.PhonePlaybackOwnership.token.isBlank() || !com.example.juke.network.NetworkFeedback.online.value) return
        val state = _uiState.value
        if (!com.example.juke.services.canPublishPhoneQueue(
                state.queue.size, state.queueIndex, state.queue.getOrNull(state.queueIndex)?.ytVideoId
            )) return
        sharedPhoneQueueReady = false
        queueManager.clearQueue()
        phoneQueueSyncJob?.cancel()
        val position = playbackManager.getCurrentPosition()
        phoneQueueSyncJob = viewModelScope.launch {
            try {
                publishPhoneQueue(state.queue, state.queueIndex, position, playbackManager.shouldResumeAfterTrackChange(), startRadio)
                sharedPhoneQueueReady = true
                _queueLoadError.value = null
                queueManager.initializeQueue(_uiState.value.queue, preserveHistory = true)
            } catch (e: CancellationException) { throw e }
            catch (e: BackendAuthException) {
                val expired = com.example.juke.network.confirmSessionExpired {
                    Backend.get("/alexa/status/")
                }
                _queueLoadError.value = if (expired) "Your session expired. Sign in again to sync the queue."
                    else "Queue sync rejected at ${e.endpoint ?: "the queue endpoint"} (HTTP ${e.statusCode}). Your login has been kept."
                Log.w(TAG, "Queue sync rejected: endpoint=${e.endpoint}; HTTP ${e.statusCode}; sessionExpired=$expired")
                if (expired) _signedOut.tryEmit(Unit)
            }
            catch (e: Exception) {
                val detail = com.example.juke.network.networkErrorMessage(e)
                    ?: (e as? com.example.juke.network.BackendHttpException)?.let { "Server error (HTTP ${it.statusCode}). Try again." }
                    ?: "Try again."
                val message = "The shared queue couldn't sync. $detail"
                // Keep diagnostics useful without logging server bodies, URLs or credentials.
                Log.w(TAG, "Shared queue sync failed: ${e.javaClass.simpleName}; $detail")
                if (_queueLoadError.value == null) _messages.tryEmit(message)
                _queueLoadError.value = message
            }
        }
    }

    /**
     * Phone queue: the first song gets a freshly warmed stream from the server, the rest use
     * their proxy URLs. The shared queue on the server is replaced so the Echo and the web
     * remote see the same songs.
     */
    private suspend fun phoneSetQueue(tracks: List<Track>, startIndex: Int, positionMs: Long = 0, play: Boolean = true, throwOnFailure: Boolean = true, synchronizeQueue: Boolean = true) {
        _uiState.update { it.copy(isLoading = true, error = null) }
        try {
            phoneQueueSyncJob?.cancel()
            sharedPhoneQueueReady = !synchronizeQueue
            queueManager.clearQueue()
            phoneQueueSyncJob = com.example.juke.services.DeviceQueueStartup.start(
                scope = viewModelScope,
                prepare = {
                    if (com.example.juke.network.NetworkFeedback.online.value) {
                        com.example.juke.services.PhonePlaybackOwnership.claim(echo.serial.value)
                        serverPlaybackChecked = true
                    } else com.example.juke.services.PhonePlaybackOwnership.forget()
                    withContext(Dispatchers.IO) { resolveForPhone(tracks, startIndex) }
                },
                play = { playable ->
                    val start = playable[startIndex]
                    playbackManager.setQueue(playable, startIndex,
                        startPositionMs = if (positionMs > 0) positionMs else androidx.media3.common.C.TIME_UNSET,
                        playWhenReady = play)
                    _uiState.update { it.copy(queue = playable, queueIndex = startIndex, currentTrack = start,
                        isPlaying = play, duration = start.durationSec * 1000L) }
                },
                synchronize = sync@{ playable ->
                    if (!synchronizeQueue || !com.example.juke.network.NetworkFeedback.online.value) return@sync
                    publishPhoneQueue(playable, startIndex, positionMs, play, startRadio = playable.size == 1)
                    sharedPhoneQueueReady = true
                    queueManager.initializeQueue(_uiState.value.queue)

                },
                onSyncError = { error ->
                    Log.w(TAG, "Device playback started but server queue sync failed", error)
                    _messages.tryEmit("Playing on this device. The server queue couldn't sync.")
                }
            )
            if (!synchronizeQueue) queueManager.initializeQueue(_uiState.value.queue)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (throwOnFailure) throw e
            Log.e(TAG, "Phone playback failed: ${com.example.juke.network.networkErrorMessage(e) ?: e.message}", e)
            _uiState.update { it.copy(error = "Playback failed: ${com.example.juke.network.networkErrorMessage(e) ?: e.message}") }
            _messages.tryEmit("Playback failed: ${com.example.juke.network.networkErrorMessage(e) ?: e.message}")
        } finally {
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    private suspend fun resolveForPhone(tracks: List<Track>, startIndex: Int): List<Track> =
        tracks.mapIndexed { i, track ->
            val videoId = requireNotNull(track.ytVideoId) { "${track.title} can't be played" }
            downloads.localTrack(track) ?: run {
                val url = if (i == startIndex) AlexaBackendApi.getStreamUrl(videoId) else AlexaBackendApi.audioUrl(videoId)
                track.copy(localUri = url, isStream = true)
            }
        }.also { resolved -> trackDao.insertTracks(resolved.map { it.toEntity() }) }

    fun togglePlayPause() {
        if (isAlexa) {
            val playing = echo.state.value.playing
            launchEcho { echo.command(if (playing) "pause" else "play") }
            return
        }
        if (playbackManager.isPlayingFlow.value) {
            playbackManager.togglePlayPause()
        } else viewModelScope.launch {
            runEcho {
                if (com.example.juke.network.NetworkFeedback.online.value) {
                    com.example.juke.services.PhonePlaybackOwnership.claim(echo.serial.value)
                    serverPlaybackChecked = true
                } else com.example.juke.services.PhonePlaybackOwnership.forget()
                playbackManager.togglePlayPause()
                synchronizePhoneQueue()
            }
        }
    }

    fun toggleShuffle() {
        if (isAlexa) {
            launchEcho { echo.shuffle() }
            return
        }
        val enabled = !playbackManager.isShuffleEnabledFlow.value
        playbackManager.setShuffleEnabled(enabled)
        if (enabled) applyQueueTool(QueueTool.SHUFFLE_UPCOMING)
    }

    fun toggleRepeat() {
        if (isAlexa) return
        playbackManager.toggleRepeatMode()
    }

    fun addNext(track: Track) = addNext(listOf(track))

    /** Insert songs right after the current one. */
    fun addNext(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        if (isAlexa) {
            launchEcho { echo.queueAdd(tracks, next = true); _messages.tryEmit("Added to play next") }
            return
        }
        val current = _uiState.value.currentTrack
        if (current == null || _uiState.value.queueIndex < 0) {
            setQueue(tracks, 0)
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isQueueOperationInProgress = true) }
            try {
                val resolved = withContext(Dispatchers.IO) { resolveForPhone(tracks, -1) }
                val state = _uiState.value
                val insertIndex = (state.queueIndex + 1).coerceAtMost(state.queue.size)
                if (playbackManager.addToQueueAt(resolved, insertIndex)) {
                    val queue = state.queue.toMutableList().apply { addAll(insertIndex, resolved) }
                    _uiState.update { it.copy(queue = queue) }
                    resolved.forEachIndexed { i, track -> queueManager.insertQueueItem(1 + i, track) }
                    _messages.tryEmit("Added to play next")
                }
                synchronizePhoneQueue()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Play next failed: ${com.example.juke.network.networkErrorMessage(e) ?: e.message}", e)
                _messages.tryEmit("Couldn't add to the queue: ${com.example.juke.network.networkErrorMessage(e) ?: e.message}")
            } finally {
                _uiState.update { it.copy(isQueueOperationInProgress = false) }
            }
        }
    }

    /** Add songs to the end of the queue. */
    fun addToQueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        if (isAlexa) {
            launchEcho { echo.queueAdd(tracks, next = false); _messages.tryEmit("Added to queue") }
            return
        }
        val current = _uiState.value.currentTrack
        if (current == null || _uiState.value.queueIndex < 0) {
            setQueue(tracks, 0)
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isQueueOperationInProgress = true) }
            try {
                val resolved = withContext(Dispatchers.IO) { resolveForPhone(tracks, -1) }
                playbackManager.addToQueue(resolved)
                _messages.tryEmit("Added to queue")
                _uiState.update { it.copy(queue = it.queue + resolved) }
                synchronizePhoneQueue()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Add to queue failed: ${com.example.juke.network.networkErrorMessage(e) ?: e.message}", e)
                _messages.tryEmit("Couldn't add to the queue: ${com.example.juke.network.networkErrorMessage(e) ?: e.message}")
            } finally {
                _uiState.update { it.copy(isQueueOperationInProgress = false) }
            }
        }
    }

    fun skipToNext() {
        if (isAlexa) {
            launchEcho { echo.command("next") }
            return
        }
        playbackManager.skipToNext()
    }

    fun skipToPrevious() {
        if (isAlexa) {
            launchEcho { echo.command("previous") }
            return
        }
        playbackManager.skipToPrevious()
    }

    fun seekTo(positionMs: Long) {
        if (isAlexa) {
            launchEcho { echo.seek(positionMs) }
            return
        }
        playbackManager.seekTo(positionMs)
        _uiState.update { it.copy(position = positionMs) }
    }

    fun updateProgress() {
        if (isAlexa) {
            _tick.value++
            return
        }
        val currentPos = playbackManager.getCurrentPosition()
        val durationMs = playbackManager.getDuration()
        _uiState.update { state ->
            state.copy(
                position = currentPos,
                duration = if (durationMs > 0) durationMs else state.duration
            )
        }
    }

    /** Echo volume (0–100), shown by the player's volume slider. */
    val echoVolume: StateFlow<Int?> = echo.state.map { it.volume }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setEchoVolume(volume: Int) = echo.setVolume(volume)

    fun removeFromQueue(trackId: String) {
        if (isAlexa) {
            val queue = uiState.value.queue
            val index = queue.indexOfFirst { it.uuid == trackId }
            if (index >= 0) launchEcho { echo.queueRemove(index, queue[index].ytVideoId) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isQueueOperationInProgress = true) }

            val currentState = _uiState.value
            val currentQueue = currentState.queue
            val currentIndex = currentState.queueIndex

            val trackIndex = currentQueue.indexOfFirst { it.uuid == trackId }
            if (trackIndex == -1) {
                _uiState.update { it.copy(isQueueOperationInProgress = false) }
                return@launch
            }

            if (playbackManager.removeFromQueue(trackId)) {
                queueManager.removeFromQueue(trackId)
                sharedPhoneQueueReady = false
                val newQueue = currentQueue.toMutableList().apply { removeAt(trackIndex) }
                val newQueueIndex = when {
                    trackIndex < currentIndex -> currentIndex - 1
                    else -> currentIndex
                }.coerceIn(0, (newQueue.size - 1).coerceAtLeast(0))

                _uiState.update {
                    it.copy(
                        queue = newQueue,
                        queueIndex = newQueueIndex,
                        currentTrack = newQueue.getOrNull(newQueueIndex),
                        isQueueOperationInProgress = false
                    )
                }
                synchronizePhoneQueue()
            } else {
                _uiState.update { it.copy(isQueueOperationInProgress = false) }
            }
        }
    }

    fun moveInQueue(fromIndex: Int, toIndex: Int) {
        if (isAlexa) {
            launchEcho { echo.queueReorder(fromIndex, toIndex) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isQueueOperationInProgress = true) }

            val currentState = _uiState.value
            val currentQueue = currentState.queue
            val currentQueueIndex = currentState.queueIndex

            if (fromIndex !in currentQueue.indices || toIndex !in currentQueue.indices) {
                _uiState.update { it.copy(isQueueOperationInProgress = false) }
                return@launch
            }

            if (playbackManager.moveInQueue(fromIndex, toIndex)) {
                val newQueue = currentQueue.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
                var newQueueIndex = currentQueueIndex
                if (fromIndex == currentQueueIndex) {
                    newQueueIndex = toIndex
                } else if (currentQueueIndex in (fromIndex + 1)..toIndex) {
                    newQueueIndex = currentQueueIndex - 1
                } else if (currentQueueIndex in toIndex..<fromIndex) {
                    newQueueIndex = currentQueueIndex + 1
                }

                _uiState.update {
                    it.copy(
                        queue = newQueue,
                        queueIndex = newQueueIndex,
                        currentTrack = newQueue.getOrNull(newQueueIndex),
                        isQueueOperationInProgress = false
                    )
                }
                queueManager.initializeQueue(newQueue, preserveHistory = true)
                synchronizePhoneQueue()
            } else {
                _uiState.update { it.copy(isQueueOperationInProgress = false) }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        echo.stopPolling()
        playbackManager.release()
        queueManager.cleanup()
    }

    /** Like or un-like the song on the YouTube account. */
    fun toggleFavorite(track: Track) {
        val videoId = track.ytVideoId ?: return
        viewModelScope.launch {
            try {
                AccountRepository.setLiked(videoId, !AccountRepository.isLiked(videoId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackendAuthException) {
                _signedOut.tryEmit(Unit)
            } catch (e: Exception) {
                _messages.tryEmit("Couldn't update the like: ${com.example.juke.network.networkErrorMessage(e) ?: e.message}")
            }
        }
    }

    // Sleep Timer
    val sleepTimerRemaining = playbackManager.sleepTimerRemaining

    fun startSleepTimer(minutes: Int) {
        playbackManager.startSleepTimer(minutes)
    }

    fun cancelSleepTimer() {
        playbackManager.cancelSleepTimer()
    }

    private val lyricsRefreshJobs = mutableMapOf<String, Job>()

    fun refreshLyrics(track: Track) {
        if (lyricsRefreshJobs[track.uuid]?.isActive == true) return
        val isEcho = track.uuid.startsWith(ECHO_PREFIX)
        lyricsRefreshJobs[track.uuid] = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    fetchLyricsWithRetry {
                        val latest = if (isEcho) track else trackDao.getTrackByUuid(track.uuid)?.toTrack() ?: track
                        LyricsApi.searchLyrics(
                            title = latest.title,
                            artist = latest.artist,
                            duration = latest.durationSec,
                            ytVideoId = latest.ytVideoId
                        )
                    }
                } ?: return@launch

                if (isEcho) {
                    val videoId = track.ytVideoId ?: return@launch
                    _echoLyrics.update {
                        it + (videoId to track.withUpdatedLyrics(
                            syncedLyrics = result.syncedLyrics?.takeIf { s -> s.isNotBlank() },
                            plainLyrics = result.plainLyrics?.takeIf { s -> s.isNotBlank() }
                        ))
                    }
                    return@launch
                }

                val updatedTrack = withContext(Dispatchers.IO) {
                    database.withTransaction {
                        val latest = trackDao.getTrackByUuid(track.uuid)?.toTrack() ?: track
                        val refreshedTrack = latest.withUpdatedLyrics(
                            syncedLyrics = result.syncedLyrics?.takeIf { it.isNotBlank() } ?: latest.syncedLyrics,
                            plainLyrics = result.plainLyrics?.takeIf { it.isNotBlank() } ?: latest.plainLyrics
                        )
                        trackDao.insertTrack(refreshedTrack.toEntity())
                        refreshedTrack
                    }
                }
                queueManager.replaceTrackInQueue(track.uuid, updatedTrack)
                updateTrackInUiState(updatedTrack)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh lyrics: ${com.example.juke.network.networkErrorMessage(e) ?: e.message}", e)
            } finally {
                lyricsRefreshJobs.remove(track.uuid)
            }
        }
    }

    val equalizerBands = playbackManager.audioEffectController.equalizerBands
    val isEqualizerEnabled = playbackManager.audioEffectController.isEqualizerEnabled

    fun toggleEqualizer(enabled: Boolean) {
        playbackManager.audioEffectController.setEqualizerEnabled(enabled)
    }

    fun setEqualizerBand(bandIndex: Int, level: Int) {
        playbackManager.audioEffectController.setEqualizerBandLevel(bandIndex, level)
    }

    fun resetEqualizer() {
        playbackManager.audioEffectController.resetEqualizer()
    }

    fun getEqualizerLevelRange(): Pair<Int, Int> {
        return playbackManager.audioEffectController.getEqualizerBandLevelRange()
    }

    // Volume Booster
    val boosterLevel = playbackManager.audioEffectController.boosterLevel
    val bassLevel = playbackManager.audioEffectController.bassLevel
    fun setBassLevel(level: Int) = playbackManager.audioEffectController.setBassLevel(level)
    val isBoosterEnabled = playbackManager.audioEffectController.isBoosterEnabled

    fun toggleVolumeBooster(enabled: Boolean) {
        playbackManager.audioEffectController.setBoosterEnabled(enabled)
    }

    fun setVolumeBoosterLevel(level: Int) {
        playbackManager.audioEffectController.setBoosterLevel(level)
    }

    // Volume Normalization
    val isNormalizationEnabled = playbackManager.audioEffectController.isNormalizationEnabled

    fun toggleVolumeNormalization(enabled: Boolean) {
        playbackManager.audioEffectController.setNormalizationEnabled(enabled)
    }

    private val _isRomanizedLyricsEnabled = MutableStateFlow(
        settingsPrefs.getBoolean("romanized_lyrics_enabled", false)
    )
    val isRomanizedLyricsEnabled: StateFlow<Boolean> = _isRomanizedLyricsEnabled.asStateFlow()

    private val _isMiniPlayerLyricsEnabled = MutableStateFlow(
        settingsPrefs.getBoolean("miniplayer_lyrics_enabled", true)
    )
    val isMiniPlayerLyricsEnabled: StateFlow<Boolean> = _isMiniPlayerLyricsEnabled.asStateFlow()

    fun toggleRomanizedLyrics() {
        val enabled = !_isRomanizedLyricsEnabled.value
        _isRomanizedLyricsEnabled.value = enabled
        settingsPrefs.edit { putBoolean("romanized_lyrics_enabled", enabled) }
    }

    fun toggleMiniPlayerLyrics(enabled: Boolean) {
        _isMiniPlayerLyricsEnabled.value = enabled
        settingsPrefs.edit { putBoolean("miniplayer_lyrics_enabled", enabled) }
    }

    private val _recommendationCount =
        MutableStateFlow(settingsPrefs.getInt("recommendation_count", 5))
    val recommendationCount: StateFlow<Int> = _recommendationCount.asStateFlow()

    fun setRecommendationCount(count: Int) {
        val clampedCount = count.coerceIn(3, 15)
        _recommendationCount.value = clampedCount
        settingsPrefs.edit { putInt("recommendation_count", clampedCount) }
    }

    private suspend fun loadRestoredQueue() {
        try {
            val prefs = getApplication<Application>().getSharedPreferences(
                "playback_state_prefs",
                android.content.Context.MODE_PRIVATE
            )
            val trackIds = prefs.getString("queue_track_ids", "") ?: ""
            val savedIndex = prefs.getInt("queue_start_index", 0)
            if (trackIds.isEmpty()) return

            val tracks = withContext(Dispatchers.IO) {
                trackIds.split(",").mapNotNull { id ->
                    runCatching { trackDao.getTrackByUuid(id)?.toTrack() }.getOrNull()
                }
            }
            if (tracks.isNotEmpty()) {
                val currentTrack = tracks.getOrNull(savedIndex)
                _uiState.update {
                    it.copy(
                        queue = tracks,
                        queueIndex = savedIndex,
                        currentTrack = currentTrack,
                        duration = currentTrack?.durationSec?.toLong()?.times(1000) ?: 0L
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load restored queue: ${com.example.juke.network.networkErrorMessage(e) ?: e.message}", e)
        }
    }

    enum class QueueTool { SHUFFLE_UPCOMING, SORT_UPCOMING, CLEAR_PLAYED }

    /** Power tools for the phone queue. The playing song and its position are untouched. */
    fun applyQueueTool(tool: QueueTool) {
        if (isAlexa) {
            if (tool == QueueTool.SHUFFLE_UPCOMING) launchEcho { echo.shuffle() }
            return
        }
        val state = _uiState.value
        val current = state.currentTrack ?: return
        val index = state.queueIndex
        val queue = state.queue
        if (index !in queue.indices) return
        val played = queue.take(index)
        val upcoming = queue.drop(index + 1)
        val newQueue = when (tool) {
            QueueTool.SHUFFLE_UPCOMING -> played + current + upcoming.shuffled()
            QueueTool.SORT_UPCOMING -> played + current + upcoming.sortedBy { it.title.lowercase() }
            QueueTool.CLEAR_PLAYED -> listOf(current) + upcoming
        }
        val newIndex = newQueue.indexOfFirst { it.uuid == current.uuid }
        _uiState.update { it.copy(queue = newQueue, queueIndex = newIndex) }
        sharedPhoneQueueReady = false
        playbackManager.replaceUpcoming(newQueue, newIndex)
        synchronizePhoneQueue()
        Toast.makeText(
            getApplication(),
            when (tool) {
                QueueTool.SHUFFLE_UPCOMING -> "Shuffled ${upcoming.size} upcoming songs"
                QueueTool.SORT_UPCOMING -> "Upcoming songs sorted A–Z"
                QueueTool.CLEAR_PLAYED -> "Cleared ${played.size} played songs"
            },
            Toast.LENGTH_SHORT
        ).show()
    }

    /** Save the queue as a new playlist on the YouTube account. */
    fun saveQueueAsPlaylist() {
        val queue = uiState.value.queue.filter { !it.ytVideoId.isNullOrBlank() }
        if (queue.isEmpty()) return
        viewModelScope.launch {
            val name = "Queue " + java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
            try {
                val created = Backend.post("/api/library/playlists/", JsonObject(mapOf("name" to JsonPrimitive(name)))).objectOrEmpty()
                val playlistId = created.text("playlistId", "playlist_id", "id")
                require(playlistId.isNotBlank()) { "The server didn't return the new playlist" }
                queue.forEach { track ->
                    Backend.post("/api/library/playlists/$playlistId/tracks",
                        JsonObject(mapOf("video_id" to JsonPrimitive(track.ytVideoId!!))))
                }
                _messages.tryEmit("Saved ${queue.size} songs as \"$name\"")
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackendAuthException) {
                _signedOut.tryEmit(Unit)
            } catch (e: Exception) {
                _messages.tryEmit("Couldn't save the playlist: ${com.example.juke.network.networkErrorMessage(e) ?: e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "MusicViewModel"
        private const val KEY_OUTPUT = "playback_output"
        private const val ECHO_PREFIX = "echo:"
    }
}

internal fun withPendingPlayback(state: MusicUiState, pending: Track?): MusicUiState =
    if (pending == null) state else state.copy(currentTrack = pending, position = 0L,
        duration = pending.durationSec * 1000L, isLoading = true)

internal fun reconcilePhonePlayback(state: MusicUiState, queue: List<Track>, currentId: String): MusicUiState {
    val index = queue.indexOfFirst { it.uuid == currentId }
    if (index < 0) return state
    val track = queue[index]
    val changed = state.currentTrack?.uuid != currentId
    return state.copy(queue = queue, queueIndex = index, currentTrack = track,
        position = if (changed) 0 else state.position, duration = track.durationSec * 1000L)
}
