package `in`.synthora.musicbox.viewmodels

import android.app.Application
import `in`.synthora.musicbox.utils.SafeLog as Log
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
import `in`.synthora.musicbox.database.MusicDatabase
import `in`.synthora.musicbox.database.toEntity
import `in`.synthora.musicbox.database.toTrack
import `in`.synthora.musicbox.models.Track
import `in`.synthora.musicbox.models.fetchLyricsWithRetry
import `in`.synthora.musicbox.models.withUpdatedLyrics
import `in`.synthora.musicbox.network.AlexaBackendApi
import `in`.synthora.musicbox.network.flag
import `in`.synthora.musicbox.network.number
import `in`.synthora.musicbox.network.Backend
import `in`.synthora.musicbox.network.BackendAuthException
import `in`.synthora.musicbox.network.LyricsApi
import `in`.synthora.musicbox.network.BrowseItem
import `in`.synthora.musicbox.network.BrowseParser
import `in`.synthora.musicbox.network.array
import `in`.synthora.musicbox.network.toTrack
import `in`.synthora.musicbox.network.toAppTrack
import `in`.synthora.musicbox.network.objectOrEmpty
import `in`.synthora.musicbox.network.text
import `in`.synthora.musicbox.network.metadata
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import `in`.synthora.musicbox.services.AccountRepository
import `in`.synthora.musicbox.services.EchoController
import `in`.synthora.musicbox.services.EchoState
import `in`.synthora.musicbox.services.transferPlayback
import `in`.synthora.musicbox.services.PlaybackManager
import `in`.synthora.musicbox.services.QueueManager
import `in`.synthora.musicbox.ui.theme.ExtractedColors
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
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
enum class PlaybackOutput { ALEXA, PHONE, REMOTE_PHONE }

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
    val extractedColors: ExtractedColors? = null,
    val phase: `in`.synthora.musicbox.services.PlaybackPhase = `in`.synthora.musicbox.services.PlaybackPhase.IDLE
)

class MusicViewModel(application: Application) : AndroidViewModel(application) {

    private val database = MusicDatabase.getDatabase(application)
    private val trackDao = database.trackDao()
    val playbackManager = `in`.synthora.musicbox.services.PlaybackCoordinator.phone(application)
    private val queueManager = QueueManager.getInstance(application)
    val recStatus = queueManager.recStatus

    private val settingsPrefs = application.getSharedPreferences(
        "music_settings_prefs",
        android.content.Context.MODE_PRIVATE
    )

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
    val mobileDevices = `in`.synthora.musicbox.services.MobileDeviceConnection.devices
    private val _remoteMobileOutput = MutableStateFlow(`in`.synthora.musicbox.services.SharedPlaybackOutput())
    val remoteMobileOutput = _remoteMobileOutput.asStateFlow()
    private val outputChosen get() = settingsPrefs.contains(KEY_OUTPUT)

    private val outputSwitchRequests = `in`.synthora.musicbox.services.OutputSwitchRequests()
    private val _isSwitchingOutput = MutableStateFlow(false)
    val isSwitchingOutput: StateFlow<Boolean> = _isSwitchingOutput.asStateFlow()
    private val _artworkHandoff = MutableStateFlow(0L)
    val artworkHandoff = _artworkHandoff.asStateFlow()

    /** Short messages for the user (Echo errors, queue failures). */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** The server rejected the web session; the app must show the sign-in screen. */
    private val _signedOut = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val signedOut: SharedFlow<Unit> = _signedOut.asSharedFlow()

    val echo = `in`.synthora.musicbox.services.PlaybackCoordinator.echo(application)
    private val sharedEchoErrors = viewModelScope.launch {
        `in`.synthora.musicbox.services.PlaybackCoordinator.errors.collect { _messages.tryEmit(it) }
    }
    private val sharedEchoAuth = viewModelScope.launch {
        `in`.synthora.musicbox.services.PlaybackCoordinator.signedOut.collect { _signedOut.tryEmit(Unit) }
    }

    // Ticks the Echo progress while the player screen asks for updates.
    private val _tick = MutableStateFlow(0L)
    private val _colors = MutableStateFlow<ExtractedColors?>(null)
    /** Lyrics fetched for Echo songs, keyed by video id (phone songs keep theirs in the database). */
    private val _echoLyrics = MutableStateFlow<Map<String, Track>>(emptyMap())
    private var isForeground = true
    private var signedIn = false
    private var signInJob: Job? = null

    private val _echoRequests = MutableStateFlow(0)
    private val transportLoading = MutableStateFlow(false)
    private var skipLoadingJob: Job? = null
    private val playbackBusy = combine(_isSwitchingOutput, _echoRequests, playbackManager.isBufferingFlow,
        `in`.synthora.musicbox.services.PlaybackService.recoveryLoading, transportLoading) { switching, requests, buffering, recovering, skipping ->
        switching || skipping || ((buffering || recovering) && _output.value == PlaybackOutput.PHONE)
    }
    private val likedQueuePresentation = `in`.synthora.musicbox.services.TrackQueuePresentation()
    private val echoQueuePresentation = `in`.synthora.musicbox.services.TrackQueuePresentation()
    private val combinedState: StateFlow<MusicUiState> = combine(
        _output,
        _uiState,
        echo.state,
        AccountRepository.liked,
        combine(_tick, _colors, _echoLyrics) { _, colors, lyrics -> colors to lyrics }
    ) { output, phone, echoState, liked, (colors, lyrics) ->
        val state = if (output != PlaybackOutput.PHONE) echoUiState(echoState, lyrics) else phone
        state.copy(
            currentTrack = state.currentTrack?.withLike(liked),
            queue = likedQueuePresentation.present(state.queue, liked),
            extractedColors = colors
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MusicUiState())

    private val pendingPlayback = MutableStateFlow<Track?>(null)
    private var pendingPlaybackJob: Job? = null
    private var phoneQueueSyncJob: Job? = null
    private var playlistBackfillJob: Job? = null
    private val phoneQueueMutex = kotlinx.coroutines.sync.Mutex()
    private var sharedPhoneQueueReady = false
    private var serverPlaybackChecked = false
    private var playbackRequestId = 0L

    val uiState: StateFlow<MusicUiState> = combine(combinedState, playbackBusy, pendingPlayback) { state, busy, pending ->
        val presented = withPendingPlayback(state.copy(isLoading = state.isLoading || busy), pending)
        presented.copy(phase = `in`.synthora.musicbox.services.playbackPhase(presented.currentTrack != null,
            _isSwitchingOutput.value, pending != null,
            presented.isLoading, presented.isPlaying, presented.error != null))
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
            queue = echoQueuePresentation.present(state.queue, active = current),
            queueIndex = state.index,
            isPlaying = (isRemotePhone || state.sharedOutput.mode != "phone") && state.playing,
            isLoading = (isRemotePhone || state.sharedOutput.mode != "phone") && state.loading,
            position = state.livePosition(),
            duration = state.durationMs
        )
    }

    private val isRemotePhone get() = _output.value == PlaybackOutput.REMOTE_PHONE
    private val isAlexa get() = _output.value == PlaybackOutput.ALEXA

    private var hasStartedDeferredStartupWork = false

    fun saveLyricsOffset(track: Track, offsetMs: Long) {
        if (isAlexa) {
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
        if (isAlexa) {
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
                if (!`in`.synthora.musicbox.network.NetworkFeedback.online.value && !isAlexa) return@launch
                if (isRemotePhone) echo.refreshSharedPhone()
                else if (isAlexa) echo.refresh(force = true, stateOnly = true)
                else {
                    if (!sharedPhoneQueueReady) { synchronizePhoneQueue(); phoneQueueSyncJob?.join() }
                    if (!sharedPhoneQueueReady) return@launch
                    val current = _uiState.value.currentTrack ?: return@launch
                    phoneQueueMutex.withLock { queueManager.refreshAlexaQueue(current) }?.let { applyAlexaWindow(it) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _queueLoadError.value = `in`.synthora.musicbox.network.networkErrorMessage(e) ?: "Couldn't load queue. Try again." }
        }
    }

    private fun applyAlexaWindow(window: QueueManager.AlexaQueueWindow) {
        // While this phone owns output, its edits/order win over delayed remote snapshots.
        if (`in`.synthora.musicbox.services.PhonePlaybackOwnership.token.isNotBlank()) return
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
            `in`.synthora.musicbox.services.PhonePlaybackOwnership.queueNeedsSync.collect {
                if (!isAlexa && !isRemotePhone && !_isSwitchingOutput.value && signedIn && phoneQueueSyncJob?.isActive != true) synchronizePhoneQueue()
            }
        }
        viewModelScope.launch {
            var previouslyOffline = false
            `in`.synthora.musicbox.network.NetworkFeedback.online.collect { online ->
                if (!online) { previouslyOffline = true; return@collect }
                if (!previouslyOffline) return@collect
                previouslyOffline = false
                if (!signedIn || isAlexa || _isSwitchingOutput.value || _uiState.value.currentTrack == null) return@collect
                val requestId = playbackRequestId
                try {
                    echo.refresh(stateOnly = true)
                    if (requestId != playbackRequestId || !signedIn) return@collect
                    val latest = echo.state.value
                    if (latest.sharedOutput.mode == "alexa") {
                        adoptRemoteAlexa(latest.sharedOutput)
                    } else if (latest.sharedOutput.mode == "phone" && latest.sharedOutput.owner.isNotBlank() &&
                        latest.sharedOutput.owner != `in`.synthora.musicbox.services.PhonePlaybackOwnership.ownerId && latest.sharedOutput.leaseMs > 0) {
                        onMobileOutput(latest.sharedOutput)
                    } else {
                        val claim = `in`.synthora.musicbox.services.PhonePlaybackOwnership.token
                        if (claim.isNotBlank()) {
                            if (latest.sharedOutput.belongsToPhone(`in`.synthora.musicbox.services.PhonePlaybackOwnership.ownerId, claim)) {
                                val renewed = AlexaBackendApi.phoneOutputRequest("heartbeat",
                                    `in`.synthora.musicbox.services.PhonePlaybackOwnership.ownerId, claim)
                                `in`.synthora.musicbox.services.PhonePlaybackOwnership.accept(renewed)
                            } else { onMobileOutput(latest.sharedOutput); return@collect }
                        } else `in`.synthora.musicbox.services.PhonePlaybackOwnership.claim(echo.serial.value)
                        serverPlaybackChecked = true
                        synchronizePhoneQueue()
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { _queueLoadError.value = "Connection restored, but the queue could not sync. Retry." }
            }
        }

        viewModelScope.launch {
            queueManager.alexaQueueWindow.collect { window ->
                if (window != null && signedIn && !isAlexa && !_isSwitchingOutput.value && sharedPhoneQueueReady && phoneQueueSyncJob?.isActive != true) applyAlexaWindow(window)
            }
        }
        viewModelScope.launch {
            `in`.synthora.musicbox.services.PhonePlaybackOwnership.remoteOutput.collect { output ->
                try {
                    if (output != null && signedIn && !_isSwitchingOutput.value) onMobileOutput(output)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    Log.e(TAG, "Device ownership update failed", e)
                    _messages.tryEmit("Couldn't update playback output. Retry when connected.")
                }
            }
        }
        // While the phone plays, report its song and position to the server so the shared queue
        // (and the web remote) follow it, and pick up queue edits made elsewhere.
        viewModelScope.launch {
            while (isActive) {
                delay(if (isForeground) 10_000 else 30_000)
                if (!serverPlaybackChecked || `in`.synthora.musicbox.services.PhonePlaybackOwnership.token.isBlank()) continue
                if (signedIn && !isAlexa && !_isSwitchingOutput.value && !sharedPhoneQueueReady && phoneQueueSyncJob?.isActive != true && `in`.synthora.musicbox.network.NetworkFeedback.online.value) {
                    synchronizePhoneQueue(); continue
                }
                if (!signedIn || isAlexa || _isSwitchingOutput.value || !sharedPhoneQueueReady || phoneQueueSyncJob?.isActive == true || _uiState.value.isQueueOperationInProgress || !`in`.synthora.musicbox.network.NetworkFeedback.online.value) continue
                val state = _uiState.value
                val current = state.currentTrack ?: continue
                if (current.ytVideoId.isNullOrBlank()) continue
                val claim = `in`.synthora.musicbox.services.PhonePlaybackOwnership.token
                try {
                    val snapshot = AlexaBackendApi.phoneQueueSnapshot()
                    if (claim != `in`.synthora.musicbox.services.PhonePlaybackOwnership.token || state != _uiState.value) continue
                    val output = `in`.synthora.musicbox.services.sharedPlaybackOutput(snapshot)
                    if (!output.belongsToPhone(`in`.synthora.musicbox.services.PhonePlaybackOwnership.ownerId,
                            `in`.synthora.musicbox.services.PhonePlaybackOwnership.token)) {
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
                    Log.w(TAG, "Shared queue refresh failed: ${`in`.synthora.musicbox.network.networkErrorMessage(e) ?: e.message}")
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
                if (`in`.synthora.musicbox.services.PhonePlaybackOwnership.localHandoff &&
                    snapshot.currentId != _uiState.value.currentTrack?.uuid) return@collectLatest
                val old = _uiState.value.queue.associateBy { it.uuid }
                val tracks = withContext(Dispatchers.IO) {
                    val missing = snapshot.queueIds.filter { it !in old }.chunked(500)
                        .flatMap { trackDao.getTracksByUuids(it) }.associate { it.uuid to it.toTrack() }
                    snapshot.queueIds.mapNotNull { old[it] ?: missing[it] }
                }
                if (snapshot != playbackManager.snapshot.value ||
                    (`in`.synthora.musicbox.services.PhonePlaybackOwnership.localHandoff && snapshot.currentId != _uiState.value.currentTrack?.uuid)) return@collectLatest
                val previous = _uiState.value
                val state = reconcilePhonePlayback(previous, tracks, snapshot.currentId)
                _uiState.value = state
                if (previous.currentTrack?.uuid != state.currentTrack?.uuid) {
                    state.currentTrack?.let { track -> queueManager.onPlaybackAdvanced(track, tracks.size - state.queueIndex - 1) }
                }
            }
        }

    }

    val downloads = `in`.synthora.musicbox.services.DownloadRepository.get(getApplication())
    val downloadedCollections = downloads.collections
    val downloadedTracks = downloads.tracks
    val downloadProgress = downloads.progress
    fun download(track: Track) = downloads.download(track)
    fun removeDownload(track: Track) = downloads.remove(track)

    fun playDownloaded(tracks: List<Track>, index: Int) {
        if (index !in tracks.indices) return
        if (isRemotePhone) {
            if (_isSwitchingOutput.value) return
            _isSwitchingOutput.value = true
            viewModelScope.launch {
                try {
                    if (`in`.synthora.musicbox.network.NetworkFeedback.online.value) {
                        val before = AlexaBackendApi.phoneOutputStatus()
                        val target = `in`.synthora.musicbox.services.MobileDeviceConnection.transfer(
                            `in`.synthora.musicbox.services.PhonePlaybackOwnership.ownerId, before, echo.serial.value)
                        `in`.synthora.musicbox.services.PhonePlaybackOwnership.accept(target)
                    } else `in`.synthora.musicbox.services.PhonePlaybackOwnership.forget(allowOffline = true)
                    setOutputPreference(PlaybackOutput.PHONE)
                    updatePolling()
                    setQueue(tracks, index)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { _messages.tryEmit(e.message ?: "Couldn't move downloads to this device") }
                finally { _isSwitchingOutput.value = false }
            }
            return
        }
        if (isAlexa) {
            // Offline copies always play on this device. Pause the source when reachable.
            if (`in`.synthora.musicbox.network.NetworkFeedback.online.value) launchEcho { echo.command("pause") }
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
        collectionCache.clear()
        downloads.collections.value.filter { it.title.isBlank() || it.title == "Untitled" }.forEach { refreshDownloadedDetails(it.key) }
        signedIn = true
        `in`.synthora.musicbox.services.MobileDeviceConnection.start(getApplication(), ::onMobileOutput, ::onMobileCommand)
        serverPlaybackChecked = false
        signInJob?.cancel()
        signInJob = viewModelScope.launch {
            val initialClaim = `in`.synthora.musicbox.services.PhonePlaybackOwnership.token
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
                Log.w(TAG, "Echo list failed: ${`in`.synthora.musicbox.network.networkErrorMessage(e) ?: e.message}")
                false
            }
            if (!outputChosen) setOutputPreference(if (hasEcho) PlaybackOutput.ALEXA else PlaybackOutput.PHONE)
            val latest = echo.state.value.sharedOutput
            if (initialRequest == playbackRequestId && initialClaim == `in`.synthora.musicbox.services.PhonePlaybackOwnership.token && latest.mode == "alexa" && echo.state.value.track != null) {
                adoptRemoteAlexa(latest)
            } else if (initialRequest == playbackRequestId && initialClaim == `in`.synthora.musicbox.services.PhonePlaybackOwnership.token && latest.mode == "phone") {
                `in`.synthora.musicbox.services.PhonePlaybackOwnership.restoreIfCurrent(latest)
            }
            serverPlaybackChecked = true
            if (isForeground) selectIdleForegroundDevice()
            updatePolling()
        }
    }

    fun onSignedOut() {
        skipLoadingJob?.cancel()
        transportLoading.value = false
        `in`.synthora.musicbox.services.MobileDeviceConnection.stop()
        collectionCache.clear()
        serverPlaybackChecked = false
        `in`.synthora.musicbox.services.PhonePlaybackOwnership.forget()
        phoneQueueSyncJob?.cancel()
        playlistBackfillJob?.cancel()
        ++playbackRequestId
        pendingPlaybackJob?.cancel()
        pendingPlayback.value = null
        signedIn = false
        outputSwitchRequests.clearPending()
        `in`.synthora.musicbox.services.RemotePlaybackService.stop(getApplication())
        signInJob?.cancel()
        `in`.synthora.musicbox.services.PlaybackCoordinator.clear(getApplication())
        AccountRepository.clear()
        playbackManager.pause()
    }

    /** The app moved to the foreground or background: poll the Echo every 3 s or 10 s. */
    fun setForeground(foreground: Boolean) {
        isForeground = foreground
        if (foreground && signedIn) `in`.synthora.musicbox.services.DeviceConnectionService.startWhileVisible(getApplication())
        if (foreground) settingsPrefs.edit { putBoolean("remote_controls_dismissed", false) }
        if (foreground && signedIn && serverPlaybackChecked) viewModelScope.launch {
            try {
                val claim = `in`.synthora.musicbox.services.PhonePlaybackOwnership.token
                val requestId = playbackRequestId
                selectIdleForegroundDevice()
                val latest = AlexaBackendApi.phoneOutputStatus()
                if (requestId == playbackRequestId && latest.mode == "alexa") adoptRemoteAlexa(latest, claim)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Retain the existing output while offline. */ }
        }
        updatePolling()
    }

    private var idlePhoneSelection = false
    private suspend fun selectIdleForegroundDevice() {
        if (!isForeground || !signedIn || _isSwitchingOutput.value || pendingPlayback.value != null || (_output.value == PlaybackOutput.PHONE && playbackManager.shouldResumeAfterTrackChange())) return
        val request = playbackRequestId
        `in`.synthora.musicbox.services.PhonePlaybackOwnership.localHandoff = true
        try {
            val selected = withTimeout(6_000) { `in`.synthora.musicbox.services.MobileDeviceConnection.selectForegroundDefault() } ?: return
            if (request != playbackRequestId || !isForeground) return
            `in`.synthora.musicbox.services.PhonePlaybackOwnership.accept(selected)
            // Restore only metadata. Audio preparation waits for an explicit play action.
            if (_output.value != PlaybackOutput.PHONE) {
                `in`.synthora.musicbox.services.PlaybackService.discardIdlePhoneQueue()
                queueManager.clearQueue()
                playbackManager.release()
                selected.nowPlaying?.let { snapshot ->
                    val state = `in`.synthora.musicbox.services.parseEchoSnapshot(snapshot, android.os.SystemClock.elapsedRealtime(), null, false)
                    _uiState.value = MusicUiState(currentTrack = state.track, queue = state.queue,
                        queueIndex = state.index, position = state.livePosition(), duration = state.durationMs)
                }
                idlePhoneSelection = true
            }
            setOutputPreference(PlaybackOutput.PHONE)
            sharedPhoneQueueReady = true
            updatePolling()
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) { /* Retry on the next foreground visit. */ }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* Keep the existing selection when offline or on older servers. */ }
        finally { `in`.synthora.musicbox.services.PhonePlaybackOwnership.localHandoff = false }
    }

    private suspend fun adoptRemoteAlexa(output: `in`.synthora.musicbox.services.SharedPlaybackOutput, expectedClaim: String? = null) {
        if (_isSwitchingOutput.value) return
        if (expectedClaim != null && expectedClaim != `in`.synthora.musicbox.services.PhonePlaybackOwnership.token) return
        phoneQueueSyncJob?.cancel()
        playlistBackfillJob?.cancel()
        ++playbackRequestId
        pendingPlaybackJob?.cancel()
        pendingPlayback.value = null
        playbackManager.pause()
        sharedPhoneQueueReady = false
        if (`in`.synthora.musicbox.services.PhonePlaybackOwnership.token.isNotBlank()) {
            try { `in`.synthora.musicbox.services.PhonePlaybackOwnership.releaseTo(output) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Phone is already paused; the server lease will expire. */ }
        }
        if (`in`.synthora.musicbox.services.PhonePlaybackOwnership.token.isNotBlank()) return
        if (output.serial.isNotBlank()) echo.select(output.serial, refreshAfter = false)
        setOutputPreference(PlaybackOutput.ALEXA)
        echo.refresh(force = true, stateOnly = true)
        updatePolling()
    }

    private fun updatePolling() {
        if (signedIn && isRemotePhone) {
            // Presence already refreshes the remote phone cursor; avoid a duplicate poller.
            `in`.synthora.musicbox.services.PlaybackCoordinator.observe(getApplication(), "ui", false)
            `in`.synthora.musicbox.services.RemotePlaybackService.start(getApplication(), echo.state.value, isForeground)
        } else if (signedIn && isAlexa && echo.serial.value.isNotBlank()) {
            `in`.synthora.musicbox.services.PlaybackCoordinator.observe(getApplication(), "ui", isForeground, isForeground)
            `in`.synthora.musicbox.services.RemotePlaybackService.start(getApplication(), echo.state.value, isForeground)
        } else {
            `in`.synthora.musicbox.services.PlaybackCoordinator.observe(getApplication(), "ui", false)
            `in`.synthora.musicbox.services.RemotePlaybackService.stop(getApplication())
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

    private fun remoteControl(action: String, payload: JsonObject = buildJsonObject {}, requestedTrack: Track? = null) {
        if (requestedTrack != null) {
            launchPlayback(requestedTrack) {
                `in`.synthora.musicbox.services.MobileDeviceConnection.control(_remoteMobileOutput.value, action, payload)
            }
            return
        }
        viewModelScope.launch { runEcho {
            if (action == "play") {
                `in`.synthora.musicbox.services.PhonePlaybackOwnership.localHandoff = true
                try {
                    val selected = `in`.synthora.musicbox.services.MobileDeviceConnection.resume(_remoteMobileOutput.value)
                    if (selected != null) startTransferredPhone(selected, playOverride = true)
                } finally { `in`.synthora.musicbox.services.PhonePlaybackOwnership.localHandoff = false }
            } else `in`.synthora.musicbox.services.MobileDeviceConnection.control(_remoteMobileOutput.value, action, payload)
        } }
    }

    /** Select another online phone without starting the same song on this phone. */
    fun switchToMobile(deviceId: String) {
        if (deviceId == `in`.synthora.musicbox.services.PhonePlaybackOwnership.ownerId) { switchOutput(null); return }
        if (_isSwitchingOutput.value) { outputSwitchRequests.request("mobile:$deviceId"); return }
        outputSwitchRequests.request("mobile:$deviceId")
        _isSwitchingOutput.value = true
        ++playbackRequestId
        pendingPlaybackJob?.cancel()
        pendingPlayback.value = null
        playlistBackfillJob?.cancel()
        viewModelScope.launch {
            try {
                val before = AlexaBackendApi.phoneOutputStatus()
                val latest = `in`.synthora.musicbox.services.MobileDeviceConnection.transfer(deviceId, before, echo.serial.value)
                _remoteMobileOutput.value = latest
                setOutputPreference(PlaybackOutput.REMOTE_PHONE)
                if (latest.nowPlaying != null) echo.applySharedSnapshot(latest.nowPlaying)
                else echo.refreshSharedPhone() // Older servers omit the handoff snapshot.
                updatePolling()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _messages.tryEmit(`in`.synthora.musicbox.network.networkErrorMessage(e) ?: e.message ?: "Couldn't switch devices") }
            finally {
                _isSwitchingOutput.value = false
                val next = outputSwitchRequests.finish()
                if (signedIn && next != null) switchOutput(next.serial)
            }
        }
    }

    private suspend fun moveRemotePhoneToThisPhone() {
        val before = AlexaBackendApi.phoneOutputStatus()
        val transferred = `in`.synthora.musicbox.services.MobileDeviceConnection.transfer(`in`.synthora.musicbox.services.PhonePlaybackOwnership.ownerId, before, echo.serial.value)
        startTransferredPhone(transferred)
    }

    private suspend fun startTransferredPhone(output: `in`.synthora.musicbox.services.SharedPlaybackOutput, playOverride: Boolean? = null) {
        if (output.handoffPending || output.owner != `in`.synthora.musicbox.services.PhonePlaybackOwnership.ownerId) return
        _artworkHandoff.update { it + 1 }
        val snapshot = `in`.synthora.musicbox.services.PlaybackDiagnostics.measure(`in`.synthora.musicbox.services.PlaybackDiagnostics.Stage.HANDOFF_STATE) {
            output.nowPlaying ?: AlexaBackendApi.phoneQueueSnapshot()
        }
        val state = withContext(Dispatchers.Default) {
            `in`.synthora.musicbox.services.parseEchoSnapshot(snapshot, android.os.SystemClock.elapsedRealtime(), null, false)
        }
        val track = state.track ?: run { `in`.synthora.musicbox.services.PhonePlaybackOwnership.accept(output); setOutputPreference(PlaybackOutput.PHONE); return }
        val queue = state.queue.ifEmpty { listOf(track) }
        val index = state.index.takeIf { it in queue.indices } ?: 0
        `in`.synthora.musicbox.services.PhonePlaybackOwnership.localHandoff = true
        try {
            `in`.synthora.musicbox.services.PhonePlaybackOwnership.accept(output)
            _uiState.update { it.copy(currentTrack = track, queue = queue, queueIndex = index,
                position = state.positionMs, duration = state.durationMs, isPlaying = false, isLoading = true) }
            setOutputPreference(PlaybackOutput.PHONE)
            // Freeze remote clocks while the destination prepares, without delaying
            // its audio connection on a queue request. The ready report follows this
            // write, so a delayed buffering report cannot overwrite ready playback.
            val preparationReport = viewModelScope.launch {
                try {
                    withTimeoutOrNull(1_500) {
                        AlexaBackendApi.updateQueue("current", requireNotNull(track.ytVideoId), emptyList(),
                            playing = playOverride ?: state.playing, positionMs = state.positionMs,
                            queueIndex = index, buffering = true, expectedToken = output.token,
                            currentEntryId = queue[index].uuid)
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* The service retries its actual cursor. */ }
            }
            coroutineScope {
                // Stream resolution may outlive the initial transfer lease before a service exists.
                val renewal = launch {
                    while (isActive) {
                        delay(3_000)
                        try {
                            val current = withTimeout(4_000) { AlexaBackendApi.phoneOutputRequest("heartbeat", `in`.synthora.musicbox.services.PhonePlaybackOwnership.ownerId, output.token) }
                            if (!current.belongsToPhone(`in`.synthora.musicbox.services.PhonePlaybackOwnership.ownerId, output.token)) break
                            `in`.synthora.musicbox.services.PhonePlaybackOwnership.accept(current)
                        } catch (_: kotlinx.coroutines.TimeoutCancellationException) { /* Retry while preparing. */ }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { /* The bounded lease still prevents two outputs. */ }
                    }
                }
                try {
                    phoneSetQueue(queue, index, state.positionMs, play = playOverride ?: state.playing, synchronizeQueue = false, reuseOwnership = true)
                    // phoneSetQueue waits for this exact selected item to be READY;
                    // retain the foreground/ownership guards until it returns.
                } finally { renewal.cancel() }

            }
            viewModelScope.launch {
                preparationReport.join()
                if (!`in`.synthora.musicbox.services.PhonePlaybackOwnership.permitsPlayback(output.token) ||
                    playbackManager.snapshot.value.currentId != _uiState.value.currentTrack?.uuid) return@launch
                val current = _uiState.value.currentTrack ?: return@launch
                try {
                    withTimeoutOrNull(1_500) {
                        AlexaBackendApi.updateQueue("current", requireNotNull(current.ytVideoId), emptyList(),
                            playing = playbackManager.isPlayingFlow.value, positionMs = playbackManager.getCurrentPosition(),
                            queueIndex = _uiState.value.queueIndex, buffering = playbackManager.isBufferingFlow.value,
                            expectedToken = output.token, currentEntryId = current.uuid)
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Periodic cursor reporting recovers. */ }
            }
            updatePolling()
        } finally {
            `in`.synthora.musicbox.services.PhonePlaybackOwnership.localHandoff = false
            `in`.synthora.musicbox.services.PlaybackService.finishPhoneNotificationHandoff()
            updatePolling()
        }
    }

    private var remotePhoneRefreshJob: Job? = null
    private suspend fun onMobileOutput(output: `in`.synthora.musicbox.services.SharedPlaybackOutput) {
        if (!signedIn || `in`.synthora.musicbox.services.PhonePlaybackOwnership.localHandoff) return
        val ownId = `in`.synthora.musicbox.services.PhonePlaybackOwnership.ownerId
        val claim = `in`.synthora.musicbox.services.PhonePlaybackOwnership.token
        if (claim.isNotBlank() && (output.mode != "phone" || output.owner != ownId)) {
            // Do not wait for the foreground service's next heartbeat to silence the source.
            val stopped = `in`.synthora.musicbox.services.PlaybackService.pausePhoneForHandoff()
            `in`.synthora.musicbox.services.PhonePlaybackOwnership.releaseTo(output, stopped.second, stopped.first)
        }
        if (_isSwitchingOutput.value) return
        if (output.mode == "phone" && output.owner == ownId && !output.handoffPending) {
            if (claim != output.token) {
                _isSwitchingOutput.value = true
                viewModelScope.launch {
                    try { startTransferredPhone(output) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { _messages.tryEmit(e.message ?: "Couldn't start playback on this device") }
                    finally { _isSwitchingOutput.value = false }
                }
            }
        } else if (output.mode == "phone" && output.owner != ownId) {
            // A downloaded track may have continued without a token while offline.
            // On reconnection it must stop before following another phone's ownership.
            playbackManager.pause()
            `in`.synthora.musicbox.services.PhonePlaybackOwnership.forget()
            phoneQueueSyncJob?.cancel()
            playlistBackfillJob?.cancel()
            _remoteMobileOutput.value = output
            setOutputPreference(PlaybackOutput.REMOTE_PHONE)
            echo.phoneDisconnected(output)
            if (remotePhoneRefreshJob?.isActive != true) remotePhoneRefreshJob = viewModelScope.launch {
                runEcho { echo.refreshSharedPhone() }
            }
            updatePolling()
        } else if (output.mode == "alexa" && !isAlexa && echo.serial.value.isNotBlank()) adoptRemoteAlexa(output)
    }

    private suspend fun onMobileCommand(action: String, payload: JsonObject) {
        if (isAlexa || isRemotePhone) return
        fun tracks(): List<Track> = if (payload["tracks"] != null) kotlinx.serialization.json.Json.decodeFromJsonElement(
            kotlinx.serialization.builtins.ListSerializer(Track.serializer()), requireNotNull(payload["tracks"]))
            else (payload["queue_items"] as? JsonArray)?.map { BrowseParser.item(it.objectOrEmpty()).toTrack() }
                ?: listOfNotNull(payload.takeIf { it.text("video_id").isNotBlank() }?.let { BrowseParser.item(it).toTrack() })
        when (action) {
            "volume" -> setPhoneVolume(payload.number("value").toInt())
            "play" -> if (!playbackManager.shouldResumeAfterTrackChange()) togglePlayPause()
            "pause" -> playbackManager.pause()
            "next" -> skipToNext()
            "previous" -> skipToPrevious()
            "seek" -> seekTo(payload.number("position_ms").coerceAtLeast(0))
            "shuffle" -> toggleShuffle()
            "repeat" -> toggleRepeat()
            "tool" -> runCatching { QueueTool.valueOf(payload.text("tool")) }.getOrNull()?.let(::applyQueueTool)
            "song" -> when {
                payload["track"] != null -> startRadio(kotlinx.serialization.json.Json.decodeFromJsonElement(Track.serializer(), requireNotNull(payload["track"])))
                payload.text("video_id").isNotBlank() -> startRadio(BrowseParser.item(payload).toTrack())
                else -> {
                    val query = payload.text("query")
                    val link = `in`.synthora.musicbox.network.youtubeLink(query)
                    if (link != null) playYoutubeLink(link)
                    else {
                        val result = Backend.get("/alexa/search/", mapOf("q" to query)).objectOrEmpty()
                        val song = (result.array("songs") + result.array("all")).map { BrowseParser.item(it.objectOrEmpty()) }
                            .firstOrNull { it.videoId.isNotBlank() } ?: error("No playable songs found")
                        startRadio(song.toTrack())
                    }
                }
            }
            "next_items", "append" -> {
                if (payload.text("playlist_id").isNotBlank()) queueCollection(BrowseParser.item(buildJsonObject { put("playlistId", payload.text("playlist_id")) }), next = action == "next_items")
                else if (action == "next_items") addNext(tracks()) else addToQueue(tracks())
            }
            "remove" -> {
                val id = payload.text("id")
                val rows = _uiState.value.queue
                val indexed = payload["index"]?.let { rows.getOrNull(payload.number("index").toInt()) }
                val target = indexed?.takeIf { it.uuid == id || it.ytVideoId == id }
                    ?: rows.firstOrNull { it.uuid == id || it.ytVideoId == id }
                target?.let { removeFromQueue(it.uuid) }
            }
            "reorder" -> moveInQueue(payload.number("from").toInt(), payload.number("to").toInt())
            "queue" -> when {
                payload["collection"] != null -> playCollection(BrowseParser.item(payload["collection"].objectOrEmpty(), payload.text("kind")), payload.flag("shuffle"))
                payload["queue_index"] != null -> _uiState.value.queue.getOrNull(payload.number("queue_index").toInt())?.let(::playTrackFromQueue)
                payload.text("playlist_id").isNotBlank() -> {
                    val video = payload.text("target_video_id", "video_id")
                    if (video.isNotBlank() && !payload.flag("shuffle")) {
                        val metadata = Backend.get("/api/track/$video/metadata").objectOrEmpty()
                        val selected = BrowseParser.item(JsonObject(metadata + mapOf("video_id" to JsonPrimitive(video)))).toTrack()
                        playPlaylist(payload.text("playlist_id"), listOf(selected), 0)
                    } else if (payload["tracks"] != null && !payload.flag("shuffle")) {
                        val items = tracks()
                        val index = payload.number("index").toInt()
                        if (index in items.indices) playPlaylist(payload.text("playlist_id"), items, index)
                    } else playCollection(BrowseParser.item(buildJsonObject { put("playlistId", payload.text("playlist_id")); put("title", "Playlist") }), payload.flag("shuffle"))
                }
                else -> {
                    val items = tracks()
                    val index = if (payload["start_index"] != null) payload.number("start_index").toInt() else payload.number("index").toInt()
                    val ordered = if (payload.flag("shuffle")) items.shuffled() else items
                    if (ordered.isNotEmpty()) setQueue(ordered, if (payload.flag("shuffle")) 0 else index.coerceIn(ordered.indices))
                }
            }
        }
    }

    // ---------- Output switching ----------

    /**
     * Play on [serial] (an Echo) or, when null, on this phone. The current queue, song, position
     * and play/pause state move with it.
     */
    fun switchOutput(serial: String?) {
        if (serial?.startsWith("mobile:") == true) { switchToMobile(serial.removePrefix("mobile:")); return }
        if (_isSwitchingOutput.value) {
            outputSwitchRequests.request(serial)
            return
        }
        val toPhone = serial == null
        if (toPhone && !isAlexa && !isRemotePhone) return
        if (!toPhone && isAlexa && serial == echo.serial.value) return
        phoneQueueSyncJob?.cancel()
        playlistBackfillJob?.cancel()
        ++playbackRequestId
        pendingPlaybackJob?.cancel()
        pendingPlayback.value = null
        outputSwitchRequests.request(serial)
        _isSwitchingOutput.value = true
        viewModelScope.launch {
            try {
                when {
                    toPhone && isRemotePhone -> moveRemotePhoneToThisPhone()
                    toPhone -> moveEchoToPhone()
                    isRemotePhone -> { echo.select(requireNotNull(serial)); echo.command("play"); setOutputPreference(PlaybackOutput.ALEXA); updatePolling() }
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
                Log.e(TAG, "Output switch failed: ${`in`.synthora.musicbox.network.networkErrorMessage(e) ?: e.message}", e)
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
        var wasPlaying = echo.state.value.playing
        var claim: `in`.synthora.musicbox.services.SharedPlaybackOutput? = null
        `in`.synthora.musicbox.services.PhonePlaybackOwnership.localHandoff = true
        try { transferPlayback(
            pauseSource = { },
            startTarget = {
                // One authoritative response contains the stopped cursor and current queue.
                // No preliminary Echo poll or second queue fetch is needed, even after voice changes.
                val claimed = AlexaBackendApi.phoneOutputRequest("claim", `in`.synthora.musicbox.services.PhonePlaybackOwnership.ownerId,
                    serial = echo.serial.value, includeState = true)
                claim = claimed
                wasPlaying = claimed.nowPlaying?.flag("playing") ?: wasPlaying
                startTransferredPhone(claimed, playOverride = if (claimed.nowPlaying == null) wasPlaying else null)
            },
            restoreSource = {
                val current = AlexaBackendApi.phoneOutputStatus()
                val owned = claim
                if (wasPlaying && owned != null && current.belongsToPhone(`in`.synthora.musicbox.services.PhonePlaybackOwnership.ownerId, owned.token))
                    echo.command("play", expectedPhoneToken = owned.token)
            },
            stopTarget = { playbackManager.pause() },
            commit = { setOutputPreference(PlaybackOutput.PHONE); updatePolling() }
        ) } finally { `in`.synthora.musicbox.services.PhonePlaybackOwnership.localHandoff = false }
    }

    private suspend fun movePhoneToEcho() {
        val phone = _uiState.value
        var wasPlaying = playbackManager.shouldResumeAfterTrackChange()
        var position = playbackManager.getCurrentPosition()
        val track = phone.currentTrack
        if (track == null) {
            echo.refresh(force = true)
            setOutputPreference(PlaybackOutput.ALEXA)
            updatePolling()
            return
        }
        val originalClaim = `in`.synthora.musicbox.services.PhonePlaybackOwnership.token
        `in`.synthora.musicbox.services.PhonePlaybackOwnership.localHandoff = true
        try { transferPlayback(
            pauseSource = {
                if (originalClaim.isNotBlank() && !`in`.synthora.musicbox.services.PhonePlaybackOwnership.permitsPlayback(originalClaim)) {
                    val renewed = withTimeout(5_000) { AlexaBackendApi.phoneOutputRequest("heartbeat",
                        `in`.synthora.musicbox.services.PhonePlaybackOwnership.ownerId, originalClaim) }
                    check(renewed.belongsToPhone(`in`.synthora.musicbox.services.PhonePlaybackOwnership.ownerId, originalClaim)) {
                        "Playback moved to another device. Refresh and try again."
                    }
                    `in`.synthora.musicbox.services.PhonePlaybackOwnership.accept(renewed)
                }
                val snapshot = `in`.synthora.musicbox.services.PlaybackService.pausePhoneForHandoff(wasPlaying, position)
                wasPlaying = snapshot.first
                position = snapshot.second
            },
            startTarget = { moveQueueToEcho(phone.queue, phone.queueIndex, track, position, wasPlaying, originalClaim) },
            restoreSource = {
                if (signedIn && `in`.synthora.musicbox.services.shouldRestorePhoneSource(originalClaim,
                        `in`.synthora.musicbox.services.PhonePlaybackOwnership.token)) {
                    `in`.synthora.musicbox.services.PhonePlaybackOwnership.claim(echo.serial.value)
                    if (wasPlaying && !playbackManager.shouldResumeAfterTrackChange()) playbackManager.togglePlayPause()
                }
            },
            stopTarget = { echo.command("pause") },
            commit = { setOutputPreference(PlaybackOutput.ALEXA); updatePolling() }
        ) } finally { `in`.synthora.musicbox.services.PhonePlaybackOwnership.localHandoff = false }
    }

    private suspend fun moveQueueToEcho(queue: List<Track>, index: Int, track: Track, positionMs: Long, play: Boolean, expectedPhoneToken: String? = null) {
        val items = queue.ifEmpty { listOf(track) }
        val start = index.takeIf { it in items.indices && items[it].ytVideoId == track.ytVideoId }
            ?: items.indexOfFirst { it.ytVideoId == track.ytVideoId }.coerceAtLeast(0)
        echo.transferQueue(items, start, positionMs, play, expectedPhoneToken, reuseSharedQueue = expectedPhoneToken != null && sharedPhoneQueueReady)
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
        Log.w(TAG, "Echo request failed: ${`in`.synthora.musicbox.network.networkErrorMessage(e) ?: e.message}")
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
        // A new song supersedes any in-flight playlist backfill: its chunks
        // belong to the previous song's queue and must not be appended.
        playlistBackfillJob?.cancel()
        pendingPlayback.value = track
        pendingPlaybackJob = viewModelScope.launch {
            try {
                if (isRemotePhone) {
                    block()
                    val confirmed = withTimeoutOrNull(45_000) {
                        echo.state.first { it.track?.ytVideoId == track.ytVideoId && it.confirmed && it.playing && !it.processing }
                        true
                    }
                    if (confirmed != true) _messages.tryEmit("The other device hasn't confirmed playback yet")
                } else if (isAlexa) {
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
            catch (e: Exception) { _messages.tryEmit(`in`.synthora.musicbox.network.networkErrorMessage(e) ?: e.message ?: "Couldn't play this song") }
            finally {
                // A cancelled earlier request must not clear the next song's loading state.
                if (requestId == playbackRequestId) pendingPlayback.value = null
            }
        }
    }

    /** Play one song; the queue continues with its radio. */
    fun playYoutubeLink(link: `in`.synthora.musicbox.network.YoutubeLink) {
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
            catch (e: Exception) { _messages.tryEmit(`in`.synthora.musicbox.network.networkErrorMessage(e) ?: "Couldn't play this YouTube link") }
        }
    }

    fun playTrack(track: Track) {
        if (isRemotePhone) { remoteControl("song", buildJsonObject { put("track", kotlinx.serialization.json.Json.encodeToJsonElement(Track.serializer(), track)); put("radio", true) }, requestedTrack = track); return }
        if (isAlexa) {
            launchPlayback(track) { echo.playSong(track, radio = false) }
            return
        }
        launchPlayback(track) { phoneSetQueue(listOf(track), 0) }
    }

    fun startRadio(track: Track) {
        if (isRemotePhone) { remoteControl("song", buildJsonObject { put("track", kotlinx.serialization.json.Json.encodeToJsonElement(Track.serializer(), track)); put("radio", true) }, requestedTrack = track); return }
        if (isAlexa) { launchPlayback(track) { echo.playSong(track, radio = true) }; return }
        launchPlayback(track) {
            phoneSetQueue(listOf(track), 0, throwOnFailure = true)
            if (_uiState.value.currentTrack?.ytVideoId == track.ytVideoId) {
                queueManager.initializeQueue(listOf(_uiState.value.currentTrack!!), isRadioMode = true)
            }
        }
    }

    private val collectionCache = object : LinkedHashMap<String, `in`.synthora.musicbox.network.CollectionContent>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, `in`.synthora.musicbox.network.CollectionContent>?) = size > 12
    }
    private val collectionLoadMutex = kotlinx.coroutines.sync.Mutex()
    private suspend fun collectionContent(item: BrowseItem): `in`.synthora.musicbox.network.CollectionContent = collectionLoadMutex.withLock {
        val key = "${item.kind}:${item.id}"
        collectionCache[key]?.takeIf { System.currentTimeMillis() - it.fetchedAtMs < 60_000 }?.let { return@withLock it }
        val path = if (item.kind == "album") "/api/album/${item.id}" else "/api/library/playlists/${item.playlistId.ifBlank { item.id.removePrefix("VL") }}"
        val data = if (item.kind == "album") Backend.get(path).objectOrEmpty() else
            `in`.synthora.musicbox.network.completeCollectionDetails { offset, bulk ->
                Backend.get(path, if (bulk) mapOf("playback" to "1", "limit" to "5000")
                    else mapOf("offset" to offset.toString(), "limit" to "100")).objectOrEmpty()
            }
        parseCollectionContent(item, data).also { collectionCache[key] = it }
    }

    private suspend fun parseCollectionContent(item: BrowseItem, data: JsonObject): `in`.synthora.musicbox.network.CollectionContent {
        val resolved = `in`.synthora.musicbox.network.resolvedCollectionItem(item, data)
        val tracks = withContext(Dispatchers.Default) {
            data.array("tracks").mapNotNull { (it as? JsonObject)?.let(BrowseParser::item) }
                .filter { it.videoId.isNotBlank() }.map { it.toTrack(AccountRepository.liked.value, resolved.image) }
        }
        return `in`.synthora.musicbox.network.CollectionContent(resolved, tracks)
    }
    private suspend fun collectionTracks(item: BrowseItem): List<Track> {
        `in`.synthora.musicbox.services.offlineCollectionTracks(item, downloads.collections.value, downloads.tracks.value)?.let { return it }
        return collectionContent(item).tracks
    }

    /** First browser page of a playlist: enough to start playback at once while
     *  the full list loads in the background. Offline and album collections are
     *  small/local already, so they keep the direct path. */
    private suspend fun collectionFirstPage(item: BrowseItem): List<Track> {
        `in`.synthora.musicbox.services.offlineCollectionTracks(item, downloads.collections.value, downloads.tracks.value)?.let { return it }
        if (item.kind == "album") return collectionTracks(item)
        val path = "/api/library/playlists/${item.playlistId.ifBlank { item.id.removePrefix("VL") }}"
        val data = Backend.get(path, mapOf("offset" to "0", "limit" to "100")).objectOrEmpty()
        return parseCollectionContent(item, data).tracks
    }

    private fun playlistIdOf(item: BrowseItem): String =
        item.playlistId.ifBlank { item.id.removePrefix("VL") }.takeIf { item.kind != "album" }.orEmpty()

    fun refreshDownloadedDetails(key: String) {
        val saved = downloads.collections.value.firstOrNull { it.key == key } ?: return
        if (!`in`.synthora.musicbox.network.NetworkFeedback.online.value) return
        viewModelScope.launch {
            try { downloads.updateCollectionDetails(collectionContent(saved.browseItem()).item) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Keep the saved metadata and offline songs if the provider is unavailable. */ }
        }
    }
    fun downloadCollection(item: `in`.synthora.musicbox.network.BrowseItem) {
        viewModelScope.launch {
            try {
                val content = collectionContent(item)
                check(content.tracks.isNotEmpty()) { "This collection has no downloadable songs" }
                downloads.downloadCollection(content.item, content.tracks)
            } catch (e: CancellationException) { throw e }
            catch (e: BackendAuthException) { _signedOut.tryEmit(Unit) }
            catch (e: Exception) { _messages.tryEmit(e.message ?: "Couldn't download collection") }
        }
    }

    /**
     * Play All / shuffle: start the first song at once, fill the rest afterwards.
     *
     * Fetching a very large playlist (and uploading it as one giant queue) before
     * playing anything is what made Play All / shuffle take so long. Instead the
     * first page loads (one small request), its first song plays immediately,
     * and the full list backfills Up Next in the background.
     */
    fun playCollection(item: `in`.synthora.musicbox.network.BrowseItem, shuffle: Boolean = false) {
        if (item.raw.flag("offline")) {
            val tracks = `in`.synthora.musicbox.services.offlineCollectionTracks(item, downloads.collections.value, downloads.tracks.value).orEmpty()
            if (tracks.isNotEmpty()) playDownloaded(if (shuffle) tracks.shuffled() else tracks, 0)
            else _messages.tryEmit("This collection has no completed downloads yet")
            return
        }
        if (isRemotePhone) { remoteControl("queue", buildJsonObject { put("collection", item.raw); put("kind", item.kind); put("shuffle", shuffle) }); return }
        viewModelScope.launch {
            _echoRequests.update { it + 1 }
            try {
                // Albums are one small request already; playlists start from the
                // first browser page so the first song needs no bulk fetch.
                val preview = if (shuffle) collectionTracks(item) else collectionFirstPage(item)
                check(preview.isNotEmpty()) { "This collection is empty" }
                if (item.kind == "album") {
                    val ordered = if (shuffle) preview.shuffled() else preview
                    launchPlayback(ordered.first()) { if (isAlexa) echo.playQueue(ordered, 0) else phoneSetQueue(ordered, 0) }
                    return@launch
                }
                val first = if (shuffle) preview.random() else preview.first()
                if (isAlexa) launchPlayback(first) {
                    echo.playSong(first, radio = false, suppressRadio = true)
                    startPlaylistBackfill(playlistIdOf(item), first, shuffle)
                }
                else launchPlayback(first) {
                    phoneSetQueue(listOf(first), 0, deferRadioSeed = true,
                        onPublished = { backfillPhoneQueue(first, playlistIdOf(item), shuffle) })
                }
            } catch (e: CancellationException) { throw e }
            catch (e: BackendAuthException) { _signedOut.tryEmit(Unit) }
            catch (e: Exception) { _messages.tryEmit(e.message ?: "Could not load collection") }
            finally { _echoRequests.update { (it - 1).coerceAtLeast(0) } }
        }
    }

    fun queueCollection(item: `in`.synthora.musicbox.network.BrowseItem, next: Boolean) {
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
        if (isRemotePhone) { uiState.value.currentTrack?.let { startRadio(it) }; return }
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
        if (isRemotePhone) { remoteControl("queue", buildJsonObject { put("queue_index", uiState.value.queue.indexOfFirst { it.uuid == track.uuid }) }, requestedTrack = track); return }
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
                    position = 0L,
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
        if (isRemotePhone) { remoteControl("queue", buildJsonObject { put("tracks", kotlinx.serialization.json.Json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(Track.serializer()), tracks)); put("index", startIndex) }, requestedTrack = tracks[startIndex]); return }
        if (isAlexa) {
            launchPlayback(tracks[startIndex]) { echo.playQueue(tracks, startIndex) }
            return
        }
        launchPlayback(tracks[startIndex]) { phoneSetQueue(tracks, startIndex) }
    }

    /**
     * Tap-a-song in a playlist: play the tapped song at once, fill the rest afterwards.
     *
     * Resolving every page of a large playlist before playing is what delayed the
     * tapped song. The tapped song's metadata is already loaded, so it starts
     * immediately while the remaining songs backfill Up Next in the background.
     */
    fun playPlaylist(playlistId: String, tracks: List<Track>, startIndex: Int = 0) {
        val selected = tracks.getOrNull(startIndex) ?: return
        if (isRemotePhone) { remoteControl("queue", buildJsonObject { put("playlist_id", playlistId); put("tracks", kotlinx.serialization.json.Json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(Track.serializer()), tracks)); put("index", startIndex) }, requestedTrack = selected); return }
        if (isAlexa) launchPlayback(selected) {
            echo.playSong(selected, radio = false, suppressRadio = true)
            startPlaylistBackfill(playlistId, selected, shuffle = false, selectedIndex = startIndex)
        }
        else launchPlayback(selected) {
            phoneSetQueue(listOf(selected), 0, deferRadioSeed = true,
                onPublished = { backfillPhoneQueue(selected, playlistId, shuffle = false, selectedIndex = startIndex) })
        }
    }

    /**
     * Fill Up Next behind an already-playing fast start. The full playlist fetch
     * runs in the background (playback is already going) and each chunk aborts
     * unless the fast-started song is still current, so a newer tap/switch wins.
     */
    private fun startPlaylistBackfill(playlistId: String, selected: Track, shuffle: Boolean, selectedIndex: Int? = null) {
        if (playlistId.isBlank()) return
        playlistBackfillJob?.cancel()
        val generation = playbackRequestId
        val serverSession = echo.playQueueSession
        playlistBackfillJob = viewModelScope.launch {
            try {
                val item = BrowseParser.item(buildJsonObject {
                    put("playlistId", playlistId); put("title", "Playlist")
                }, "playlists")
                val all = collectionTracks(item)
                coroutineContext.ensureActive()
                val selectedVideo = selected.ytVideoId ?: return@launch
                echo.refresh(force = true, stateOnly = true)
                if (!isAlexa || generation != playbackRequestId) return@launch
                val remainder = playlistRemainder(all, selectedVideo, shuffle, selectedIndex)
                if (remainder.isEmpty()) return@launch
                remainder.chunked(PLAYLIST_BACKFILL_CHUNK).forEachIndexed { chunkIndex, chunk ->
                    coroutineContext.ensureActive()
                    echo.refresh(force = true, stateOnly = true)
                    if (!isAlexa || generation != playbackRequestId) return@launch
                    // First chunk goes right after the playing song, the rest append.
                    echo.queueAdd(chunk, next = false, expectedSession = serverSession)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: BackendAuthException) { _signedOut.tryEmit(Unit) }
            catch (e: Exception) {
                // Playback already started; only the Up Next fill failed.
                Log.w(TAG, "Playlist backfill failed: ${e.javaClass.simpleName}")
                _messages.tryEmit("Playing now. Couldn't load the rest of the playlist.")
            }
        }
    }

    /**
     * Phone-side backfill, chained after the fast-start publish ([onPublished])
     * so the initial install always lands first. Appends locally and to the
     * shared queue in server-sized chunks.
     */
    private suspend fun backfillPhoneQueue(selected: Track, playlistId: String, shuffle: Boolean, selectedIndex: Int? = null) {
        if (playlistId.isBlank()) {
            if (shuffle) synchronizePhoneQueue(startRadio = true)
            return
        }
        val generation = playbackRequestId
        val claim = `in`.synthora.musicbox.services.PhonePlaybackOwnership.token
        try {
            val item = BrowseParser.item(buildJsonObject { put("playlistId", playlistId); put("title", "Playlist") }, "playlists")
            val all = collectionTracks(item)
            coroutineContext.ensureActive()
            val selectedVideo = selected.ytVideoId ?: return
            if (claim.isBlank() || isAlexa || isRemotePhone || generation != playbackRequestId) return
            val remainder = playlistRemainder(all, selectedVideo, shuffle, selectedIndex)
            if (remainder.isEmpty()) { synchronizePhoneQueue(startRadio = true); return }
            sharedPhoneQueueReady = false
            // Fill the complete local playlist first in Binder-safe batches. Server
            // rejection must not discard the unprocessed remainder of a playlist.
            fun stillCurrent() = !isAlexa && !isRemotePhone && generation == playbackRequestId &&
                `in`.synthora.musicbox.services.PhonePlaybackOwnership.permitsPlayback(claim)
            val filled = `in`.synthora.musicbox.services.fillPhonePlaylist(remainder, PLAYLIST_BACKFILL_CHUNK,
                stillCurrent = ::stillCurrent,
                append = { chunk ->
                    val resolved = withContext(Dispatchers.IO) { resolveForPhone(chunk, -1) }
                    coroutineContext.ensureActive()
                    if (!stillCurrent()) throw CancellationException("Playlist output changed during preparation")
                    playbackManager.addToQueue(resolved)
                    _uiState.update { it.copy(queue = it.queue + resolved) }
                },
                publish = {
                    val live = _uiState.value
                    publishPhoneQueue(live.queue, live.queueIndex, playbackManager.getCurrentPosition(),
                        playbackManager.shouldResumeAfterTrackChange())
                })
            if (!filled) return
            sharedPhoneQueueReady = true
            queueManager.initializeQueue(_uiState.value.queue, preserveHistory = true)
        } catch (e: CancellationException) { throw e }
        catch (e: BackendAuthException) { _signedOut.tryEmit(Unit) }
        catch (e: Exception) {
            sharedPhoneQueueReady = false
            Log.w(TAG, "Playlist backfill failed: ${e.javaClass.simpleName}")
            _messages.tryEmit("Playing locally. Retrying playlist synchronization.")
            if (!isAlexa && !isRemotePhone && generation == playbackRequestId) synchronizePhoneQueue()
        }
    }

    private suspend fun publishPhoneQueue(tracks: List<Track>, index: Int, positionMs: Long, playing: Boolean, startRadio: Boolean = false) = phoneQueueMutex.withLock {
        require(index in tracks.indices)
        require(tracks.size <= 5_000) { "This queue exceeds the server's 5,000-song limit" }
        val seed = requireNotNull(tracks[index].ytVideoId)
        val claim = `in`.synthora.musicbox.services.PhonePlaybackOwnership.token
        var firstPublication = true
        `in`.synthora.musicbox.services.publishPhoneQueueInStages(
            initial = tracks.map(AlexaBackendApi::backendTrack),
            publish = { items ->
                if (!`in`.synthora.musicbox.services.PhonePlaybackOwnership.permitsPlayback(claim))
                    throw CancellationException("Phone queue publication was superseded")
                // Publish before radio lookup so web Play always sees the actual phone song.
                AlexaBackendApi.updateQueue("start", seed, items,
                    if (firstPublication) playing else playbackManager.isPlayingFlow.value,
                    if (firstPublication) positionMs else playbackManager.getCurrentPosition(),
                    queueIndex = index, buffering = playbackManager.isBufferingFlow.value || `in`.synthora.musicbox.services.PhonePlaybackOwnership.preparingSong, expectedToken = claim)
                firstPublication = false
            },
            expand = expand@{ initial ->
                if (!startRadio || tracks.size != 1) return@expand initial
                val radio = try {
                    withTimeoutOrNull(8_000) { AlexaBackendApi.getRadio(seed) }.orEmpty()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    Log.w(TAG, "Phone radio unavailable: ${e.javaClass.simpleName}")
                    emptyList()
                }
                coroutineContext.ensureActive()
                val items = (initial + radio.filter { it.videoId != seed }).distinctBy { it.videoId }
                if (items.size <= initial.size) return@expand initial
                if (_uiState.value.currentTrack?.uuid != tracks[index].uuid ||
                    _uiState.value.queue.map { it.uuid } != tracks.map { it.uuid } ||
                    !`in`.synthora.musicbox.services.PhonePlaybackOwnership.permitsPlayback(claim))
                    throw CancellationException("Local queue changed while radio was loading")
                val full = tracks + items.drop(1).map { it.toAppTrack(AlexaBackendApi.audioUrl(it.videoId)) }
                withContext(Dispatchers.IO) { trackDao.insertTracks(full.map { it.toEntity() }) }
                coroutineContext.ensureActive()
                if (!`in`.synthora.musicbox.services.PhonePlaybackOwnership.permitsPlayback(claim))
                    throw CancellationException("Playback moved while radio was loading")
                playbackManager.replaceUpcoming(full, index)
                _uiState.update { it.copy(queue = full) }
                items
            }
        )
    }

    private fun synchronizePhoneQueue(startRadio: Boolean = false) {
        if (isAlexa || isRemotePhone || !signedIn || !serverPlaybackChecked || `in`.synthora.musicbox.services.PhonePlaybackOwnership.token.isBlank() || !`in`.synthora.musicbox.network.NetworkFeedback.online.value) return
        val state = _uiState.value
        if (!`in`.synthora.musicbox.services.canPublishPhoneQueue(
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
                val expired = `in`.synthora.musicbox.network.confirmSessionExpired {
                    Backend.get("/alexa/status/")
                }
                _queueLoadError.value = if (expired) "Your session expired. Sign in again to sync the queue."
                    else "Queue sync rejected at ${e.endpoint ?: "the queue endpoint"} (HTTP ${e.statusCode}). Your login has been kept."
                Log.w(TAG, "Queue sync rejected: endpoint=${e.endpoint}; HTTP ${e.statusCode}; sessionExpired=$expired")
                if (expired) _signedOut.tryEmit(Unit)
            }
            catch (e: Exception) {
                if (e is `in`.synthora.musicbox.network.BackendHttpException && e.statusCode == 409) {
                    _queueLoadError.value = null
                    val latest = runCatching { AlexaBackendApi.phoneOutputStatus() }.getOrNull()
                    if (latest?.mode == "alexa") adoptRemoteAlexa(latest)
                    else if (latest?.belongsToPhone(`in`.synthora.musicbox.services.PhonePlaybackOwnership.ownerId,
                            `in`.synthora.musicbox.services.PhonePlaybackOwnership.token) == true) viewModelScope.launch {
                        delay(300)
                        if (!isAlexa && !_isSwitchingOutput.value) synchronizePhoneQueue()
                    }
                    return@launch
                }
                val detail = `in`.synthora.musicbox.network.networkErrorMessage(e)
                    ?: (e as? `in`.synthora.musicbox.network.BackendHttpException)?.let { "Server error (HTTP ${it.statusCode}). Try again." }
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
     *
     * [deferRadioSeed] publishes just this song with no radio expansion: used for fast
     * playlist starts where the remainder is backfilled right after [onPublished].
     * [onPublished] runs after the shared-queue publish completed, so a backfill can
     * safely append without racing the initial install.
     */
    private suspend fun phoneSetQueue(tracks: List<Track>, startIndex: Int, positionMs: Long = 0, play: Boolean = true, throwOnFailure: Boolean = true, synchronizeQueue: Boolean = true,
        deferRadioSeed: Boolean = false, reuseOwnership: Boolean = false, onPublished: (suspend () -> Unit)? = null) {
        idlePhoneSelection = false
        `in`.synthora.musicbox.services.PhonePlaybackOwnership.beginSongPreparation()
        _uiState.update { it.copy(isLoading = true, error = null) }
        var startupClaim = ""
        try {
            downloads.awaitReady()
            phoneQueueSyncJob?.cancel()
            sharedPhoneQueueReady = !synchronizeQueue
            queueManager.clearQueue()
            phoneQueueSyncJob = `in`.synthora.musicbox.services.DeviceQueueStartup.start(
                scope = viewModelScope,
                prepare = {
                    coroutineScope {
                        val playable = async(Dispatchers.IO) { resolveForPhone(tracks, startIndex) }
                        if (`in`.synthora.musicbox.network.NetworkFeedback.online.value) {
                            if (!reuseOwnership || !`in`.synthora.musicbox.services.PhonePlaybackOwnership.permitsPlayback())
                                `in`.synthora.musicbox.services.PhonePlaybackOwnership.claim(echo.serial.value)
                            serverPlaybackChecked = true
                        } else `in`.synthora.musicbox.services.PhonePlaybackOwnership.forget(allowOffline = true)
                        startupClaim = `in`.synthora.musicbox.services.PhonePlaybackOwnership.token
                        if (synchronizeQueue && tracks.size == 1 && startupClaim.isNotBlank()) {
                            // Mirror immediately while stream preparation runs in parallel.
                            // This state is settled/paused; only real Media3 buffering is loading.
                            try {
                                withTimeoutOrNull(1_500) {
                                    AlexaBackendApi.updateQueue("start", requireNotNull(tracks[0].ytVideoId), tracks.map(AlexaBackendApi::backendTrack),
                                        playing = false, positionMs = positionMs, queueIndex = 0, expectedToken = startupClaim)
                                }
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { /* Normal publication retries after local playback starts. */ }
                        }
                        playable.await()
                    }
                },
                play = { playable ->
                    if (!`in`.synthora.musicbox.services.PhonePlaybackOwnership.permitsPlayback(startupClaim))
                        throw CancellationException("Playback moved to another output during preparation")
                    val start = playable[startIndex]
                    playbackManager.setQueue(playable, startIndex,
                        startPositionMs = if (positionMs > 0) positionMs else androidx.media3.common.C.TIME_UNSET,
                        playWhenReady = play)
                    _uiState.update { it.copy(queue = playable, queueIndex = startIndex, currentTrack = start,
                        isPlaying = play, position = positionMs.coerceAtLeast(0), duration = start.durationSec * 1000L) }
                },
                synchronize = sync@{ playable ->
                    if (!synchronizeQueue || !`in`.synthora.musicbox.network.NetworkFeedback.online.value) return@sync
                    publishPhoneQueue(playable, startIndex, positionMs, play, startRadio = !deferRadioSeed && playable.size == 1)
                    sharedPhoneQueueReady = true
                    queueManager.initializeQueue(_uiState.value.queue)
                    onPublished?.invoke()
                },
                onSyncError = { error ->
                    Log.w(TAG, "Device playback started but server queue sync failed", error)
                    _messages.tryEmit("Playing on this device. The server queue couldn't sync.")
                }
            )
            // MediaController commands are asynchronous: queue installation alone is
            // not enough to release ownership/foreground guards in the background.
            playbackManager.awaitReady(requireNotNull(_uiState.value.currentTrack).uuid)
            if (!synchronizeQueue) queueManager.initializeQueue(_uiState.value.queue)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (throwOnFailure) throw e
            Log.e(TAG, "Phone playback failed: ${`in`.synthora.musicbox.network.networkErrorMessage(e) ?: e.message}", e)
            _uiState.update { it.copy(error = "Playback failed: ${`in`.synthora.musicbox.network.networkErrorMessage(e) ?: e.message}") }
            _messages.tryEmit("Playback failed: ${`in`.synthora.musicbox.network.networkErrorMessage(e) ?: e.message}")
        } finally {
            `in`.synthora.musicbox.services.PhonePlaybackOwnership.endSongPreparation()
            `in`.synthora.musicbox.services.PlaybackService.finishSongPreparation()
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    private suspend fun resolveForPhone(tracks: List<Track>, startIndex: Int): List<Track> =
        `in`.synthora.musicbox.services.stableQueueEntries(tracks, if (startIndex < 0) _uiState.value.queue.map { it.uuid }.toSet() else emptySet()).mapIndexed { i, track ->
            val videoId = requireNotNull(track.ytVideoId) { "${track.title} can't be played" }
            downloads.localTrack(track) ?: run {
                val url = if (i == startIndex) AlexaBackendApi.getStreamUrl(videoId) else AlexaBackendApi.audioUrl(videoId)
                track.copy(localUri = url, isStream = true)
            }
        }.also { resolved ->
            if (resolved.size > 200 && startIndex in resolved.indices) {
                // The selected song must exist before its service callback runs.
                trackDao.insertTracks(listOf(resolved[startIndex].toEntity()))
                viewModelScope.launch(Dispatchers.IO) {
                    try { trackDao.insertTracks(resolved.filterIndexed { index, _ -> index != startIndex }.map { it.toEntity() }) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { Log.w(TAG, "Background queue metadata save failed: ${e.javaClass.simpleName}") }
                }
            } else trackDao.insertTracks(resolved.map { it.toEntity() })
        }

    fun togglePlayPause() {
        if (isRemotePhone) { remoteControl(if (_remoteMobileOutput.value.owner.isNotBlank() && uiState.value.isPlaying) "pause" else "play"); return }
        if (isAlexa) {
            val playing = echo.state.value.playing
            launchEcho { echo.command(if (playing) "pause" else "play") }
            return
        }
        if (idlePhoneSelection && _uiState.value.currentTrack != null) {
            val state = _uiState.value
            val current = requireNotNull(state.currentTrack)
            val queue = state.queue.ifEmpty { listOf(current) }
            val index = state.queueIndex.takeIf { it in queue.indices } ?: 0
            launchPlayback(current) { phoneSetQueue(queue, index, state.position.coerceAtLeast(0)) }
            return
        }
        if (playbackManager.needsPlaybackReload() && _uiState.value.currentTrack != null) {
            val state = _uiState.value
            val current = requireNotNull(state.currentTrack)
            val queue = state.queue.ifEmpty { listOf(current) }
            val index = state.queueIndex.takeIf { it in queue.indices } ?: 0
            launchPlayback(current) { phoneSetQueue(queue, index, playbackManager.getResumePosition(current.uuid, state.position)) }
        } else viewModelScope.launch {
            runEcho {
                if (!playbackManager.isPlayingFlow.value && !`in`.synthora.musicbox.services.PhonePlaybackOwnership.permitsPlayback()) {
                    if (`in`.synthora.musicbox.network.NetworkFeedback.online.value) {
                        `in`.synthora.musicbox.services.PhonePlaybackOwnership.claim(echo.serial.value)
                        serverPlaybackChecked = true
                    } else `in`.synthora.musicbox.services.PhonePlaybackOwnership.forget(allowOffline = true)
                }
                playbackManager.togglePlayPause()
                synchronizePhoneQueue()
            }
        }
    }

    fun toggleShuffle() {
        if (isRemotePhone) { remoteControl("shuffle", buildJsonObject {}); return }
        if (isAlexa) {
            launchEcho { echo.shuffle() }
            return
        }
        val enabled = !playbackManager.isShuffleEnabledFlow.value
        playbackManager.setShuffleEnabled(enabled)
        if (enabled) applyQueueTool(QueueTool.SHUFFLE_UPCOMING)
    }

    fun toggleRepeat() {
        if (isRemotePhone) { remoteControl("repeat", buildJsonObject {}); return }
        if (isAlexa) return
        playbackManager.toggleRepeatMode()
    }

    fun addNext(track: Track) = addNext(listOf(track))

    /** Insert songs right after the current one. */
    fun addNext(tracks: List<Track>) {
        if (isRemotePhone) { remoteControl("next_items", buildJsonObject { put("tracks", kotlinx.serialization.json.Json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(Track.serializer()), tracks)) }); return }
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
                Log.e(TAG, "Play next failed: ${`in`.synthora.musicbox.network.networkErrorMessage(e) ?: e.message}", e)
                _messages.tryEmit("Couldn't add to the queue: ${`in`.synthora.musicbox.network.networkErrorMessage(e) ?: e.message}")
            } finally {
                _uiState.update { it.copy(isQueueOperationInProgress = false) }
            }
        }
    }

    /** Add songs to the end of the queue. */
    fun addToQueue(tracks: List<Track>) {
        if (isRemotePhone) { remoteControl("append", buildJsonObject { put("tracks", kotlinx.serialization.json.Json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(Track.serializer()), tracks)) }); return }
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
                Log.e(TAG, "Add to queue failed: ${`in`.synthora.musicbox.network.networkErrorMessage(e) ?: e.message}", e)
                _messages.tryEmit("Couldn't add to the queue: ${`in`.synthora.musicbox.network.networkErrorMessage(e) ?: e.message}")
            } finally {
                _uiState.update { it.copy(isQueueOperationInProgress = false) }
            }
        }
    }

    private fun skipRemote(action: String) {
        val previous = echo.state.value.track?.ytVideoId
        skipLoadingJob?.cancel()
        transportLoading.value = true
        skipLoadingJob = viewModelScope.launch {
            try {
                if (isRemotePhone) `in`.synthora.musicbox.services.MobileDeviceConnection.control(_remoteMobileOutput.value, action)
                else echo.command(action)
                kotlinx.coroutines.withTimeout(30_000) {
                    echo.state.first { it.track?.ytVideoId != previous && it.playing && it.confirmed && !it.processing }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                _messages.tryEmit("Playback has not started. Check the selected device and connection.")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _messages.tryEmit(e.message ?: "Couldn't change the song") }
            finally { if (kotlinx.coroutines.currentCoroutineContext().isActive) transportLoading.value = false }
        }
        // A timeout cancels its child scope; completion still clears this request,
        // while a superseded request cannot clear the next request's spinner.
        val current = skipLoadingJob
        current?.invokeOnCompletion { if (skipLoadingJob === current) transportLoading.value = false }
    }

    fun skipToNext() {
        if (isRemotePhone || isAlexa) { skipRemote("next"); return }
        if (isAlexa) {
            launchEcho { echo.command("next") }
            return
        }
        playbackManager.skipToNext()
    }

    fun skipToPrevious() {
        if (isRemotePhone || isAlexa) { skipRemote("previous"); return }
        if (isAlexa) {
            launchEcho { echo.command("previous") }
            return
        }
        playbackManager.skipToPrevious()
    }

    fun seekTo(positionMs: Long) {
        if (isRemotePhone) { remoteControl("seek", buildJsonObject { put("position_ms", positionMs) }); return }
        if (isAlexa) {
            launchEcho { echo.seek(positionMs) }
            return
        }
        playbackManager.seekTo(positionMs)
        _uiState.update { it.copy(position = positionMs) }
    }

    fun updateProgress() {
        if (isAlexa || isRemotePhone) {
            _tick.value++
            return
        }
        // The destination's previous Media3 item is not the transferred song's clock.
        if (`in`.synthora.musicbox.services.PhonePlaybackOwnership.localHandoff ||
            playbackManager.snapshot.value.currentId != _uiState.value.currentTrack?.uuid) return
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

    /** Route physical keys to the selected output; local keys retain Android's normal behavior. */
    fun adjustSelectedDeviceVolume(direction: Int): Boolean {
        if (!signedIn || _output.value == PlaybackOutput.PHONE) return false
        if (_isSwitchingOutput.value) return true
        return `in`.synthora.musicbox.services.RemoteVolumeControl.adjust(getApplication(), direction)
    }

    fun setRemotePhoneVolume(volume: Int) = `in`.synthora.musicbox.services.RemoteVolumeControl.set(getApplication(), volume)

    private fun setPhoneVolume(percent: Int) {
        val audio = getApplication<Application>().getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val maximum = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, `in`.synthora.musicbox.services.volumePercentToStreamIndex(percent, maximum), 0)
    }


    fun removeFromQueue(trackId: String, onComplete: ((Boolean) -> Unit)? = null) {
        if (isRemotePhone) {
            viewModelScope.launch {
                try { `in`.synthora.musicbox.services.MobileDeviceConnection.control(_remoteMobileOutput.value, "remove", buildJsonObject { put("id", trackId) }); onComplete?.invoke(true) }
                catch (e: Exception) { onComplete?.invoke(false); _messages.tryEmit(e.message ?: "Couldn't remove song") }
            }
            return
        }
        viewModelScope.launch {
            // Let the optimistic row finish its collapse; the request survives sheet dismissal.
            if (onComplete != null) delay(280)
            val currentState = uiState.value
            val index = currentState.queue.indexOfFirst { it.uuid == trackId }
            if (index < 0) { onComplete?.invoke(true); return@launch }
            if (index == currentState.queueIndex) { onComplete?.invoke(false); return@launch }
            if (isAlexa) {
                try {
                    echo.queueRemove(index, currentState.queue[index].ytVideoId)
                    onComplete?.invoke(true)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    onComplete?.invoke(false)
                    if (e is BackendAuthException) _signedOut.tryEmit(Unit)
                    _messages.tryEmit(`in`.synthora.musicbox.network.networkErrorMessage(e) ?: "Couldn't delete the song. It has been restored.")
                }
                return@launch
            }
            _uiState.update { it.copy(isQueueOperationInProgress = true) }
            try {
                if (!playbackManager.removeFromQueue(trackId)) { onComplete?.invoke(false); return@launch }
                queueManager.removeFromQueue(trackId)
                sharedPhoneQueueReady = false
                val newQueue = currentState.queue.toMutableList().apply { removeAt(index) }
                val newIndex = (if (index < currentState.queueIndex) currentState.queueIndex - 1 else currentState.queueIndex)
                    .coerceIn(0, (newQueue.size - 1).coerceAtLeast(0))
                _uiState.update { it.copy(queue = newQueue, queueIndex = newIndex, currentTrack = newQueue.getOrNull(newIndex)) }
                onComplete?.invoke(true)
                synchronizePhoneQueue()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { onComplete?.invoke(false); _messages.tryEmit("Couldn't delete the song. Please retry.") }
            finally { _uiState.update { it.copy(isQueueOperationInProgress = false) } }
        }
    }

    fun moveInQueue(fromIndex: Int, toIndex: Int) {
        if (isRemotePhone) { remoteControl("reorder", buildJsonObject { put("from", fromIndex); put("to", toIndex) }); return }
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
        `in`.synthora.musicbox.services.PlaybackCoordinator.observe(getApplication(), "ui", false)
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
                _messages.tryEmit("Couldn't update the like: ${`in`.synthora.musicbox.network.networkErrorMessage(e) ?: e.message}")
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
        val isEcho = isAlexa
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
                Log.e(TAG, "Failed to refresh lyrics: ${`in`.synthora.musicbox.network.networkErrorMessage(e) ?: e.message}", e)
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
            Log.e(TAG, "Failed to load restored queue: ${`in`.synthora.musicbox.network.networkErrorMessage(e) ?: e.message}", e)
        }
    }

    enum class QueueTool { SHUFFLE_UPCOMING, SORT_UPCOMING, CLEAR_PLAYED }

    /** Power tools for the phone queue. The playing song and its position are untouched. */
    fun applyQueueTool(tool: QueueTool) {
        if (isRemotePhone) { remoteControl("tool", buildJsonObject { put("tool", tool.name) }); return }
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
                _messages.tryEmit("Couldn't save the playlist: ${`in`.synthora.musicbox.network.networkErrorMessage(e) ?: e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "MusicViewModel"
        private const val KEY_OUTPUT = "playback_output"
        private const val ECHO_PREFIX = "echo:"
        /** Server `extend`/bulk-append sized chunks: small POSTs that stay resumable. */
        internal const val PLAYLIST_BACKFILL_CHUNK = 200
    }
}

internal fun withPendingPlayback(state: MusicUiState, pending: Track?): MusicUiState =
    if (pending == null) state else state.copy(currentTrack = pending, position = 0L,
        duration = pending.durationSec * 1000L, isPlaying = false, isLoading = true)

/**
 * Songs that backfill Up Next behind an already-playing fast start.
 *
 * Ordered mode keeps playlist order after the playing song (no wrap-around,
 * like tapping through a playlist); shuffle mode returns every other song in
 * random order with the playing song kept first by the caller. When the
 * playing song is no longer in the fetched list (playlist edited meanwhile),
 * every other song is returned so Up Next still fills. Pure: unit-tested.
 */
fun playlistRemainder(all: List<Track>, selectedVideoId: String, shuffle: Boolean, selectedIndex: Int? = null): List<Track> {
    val index = selectedIndex?.takeIf { it in all.indices && all[it].ytVideoId == selectedVideoId }
        ?: all.indexOfFirst { it.ytVideoId == selectedVideoId }
    if (shuffle) {
        // Shuffled Up Next is every other song in random order; only the
        // playing occurrence itself is excluded.
        val rest = if (index < 0) all.filter { it.ytVideoId != selectedVideoId }
            else all.filterIndexed { i, _ -> i != index }
        return rest.shuffled()
    }
    return if (index < 0) all.filter { it.ytVideoId != selectedVideoId }
        else all.drop(index + 1)
}

internal fun reconcilePhonePlayback(state: MusicUiState, queue: List<Track>, currentId: String): MusicUiState {
    val index = queue.indexOfFirst { it.uuid == currentId }
    if (index < 0) return state
    val track = queue[index]
    val changed = state.currentTrack?.uuid != currentId
    return state.copy(queue = queue, queueIndex = index, currentTrack = track,
        position = if (changed) 0 else state.position, duration = track.durationSec * 1000L)
}
