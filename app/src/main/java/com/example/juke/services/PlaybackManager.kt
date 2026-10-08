package com.example.juke.services

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.example.juke.utils.SafeLog as Log
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.example.juke.R
import com.example.juke.analytics.AnalyticsManager
import com.example.juke.database.MusicDatabase
import com.example.juke.network.toAppTrack
import com.example.juke.database.toEntity
import com.example.juke.database.toTrack
import com.example.juke.models.Track
import com.example.juke.network.AlexaBackendApi
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.put
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Playback Manager for controlling media playback.
 * This connects to PlaybackService via MediaController to enable notification controls.
 */
class PlaybackManager private constructor(private val context: Context) {

    companion object {
        @SuppressLint("StaticFieldLeak")
        @field:Volatile
        private var INSTANCE: PlaybackManager? = null

        fun getInstance(context: Context): PlaybackManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PlaybackManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val TAG = "PlaybackManager"
    private val prefs = context.getSharedPreferences("playback_state_prefs", Context.MODE_PRIVATE)
    private var controllerPoll: kotlinx.coroutines.Job? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var playerListener: Player.Listener? = null
    private val _isPlaying = MutableStateFlow(false)
    val isPlayingFlow: StateFlow<Boolean> = _isPlaying.asStateFlow()
    private val _isBuffering = MutableStateFlow(false)
    val isBufferingFlow: StateFlow<Boolean> = _isBuffering.asStateFlow()
    private val database: MusicDatabase = MusicDatabase.getDatabase(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO +
        kotlinx.coroutines.CoroutineExceptionHandler { _, error ->
            Log.e(TAG, "Playback worker failed", error)
            com.example.juke.network.NetworkFeedback.notify("Playback interrupted. Tap play to retry.")
        })

    private val _snapshot = MutableStateFlow(PhonePlaybackSnapshot())
    val snapshot: StateFlow<PhonePlaybackSnapshot> = _snapshot.asStateFlow()
    private var connectionFailures = 0
    private var connectionRetry: kotlinx.coroutines.Job? = null
    private var pendingQueueAction: ((MediaController) -> Unit)? = null
    private var userQueueRequested = false
    private var pausedAtMs = android.os.SystemClock.elapsedRealtime()
    private fun publishSnapshot(ctrl: MediaController) {
        _snapshot.value = PhonePlaybackSnapshot((0 until ctrl.mediaItemCount).map { ctrl.getMediaItemAt(it).mediaId },
            ctrl.currentMediaItem?.mediaId)
    }

    // Flow to emit current track UUID changes
    private val _currentTrackId = MutableStateFlow<String?>(null)
    val currentTrackIdFlow: StateFlow<String?> = _currentTrackId.asStateFlow()

    // Flow to indicate if restored state is available
    private val _hasRestoredState = MutableStateFlow(false)
    val hasRestoredState: StateFlow<Boolean> = _hasRestoredState.asStateFlow()

    // Flow to emit current queue index
    private val _currentQueueIndex = MutableStateFlow(0)
    val currentQueueIndexFlow: StateFlow<Int> = _currentQueueIndex.asStateFlow()

    // Flow to emit current queue structure (list of Media IDs/UUIDs)
    private val _queueFlow = MutableStateFlow<List<String>>(emptyList())
    val queueFlow: StateFlow<List<String>> = _queueFlow.asStateFlow()

    // Sleep timer state
    private var sleepTimerJob: kotlinx.coroutines.Job? = null
    private val _sleepTimerRemaining = MutableStateFlow<Long?>(null)
    val sleepTimerRemaining: StateFlow<Long?> = _sleepTimerRemaining.asStateFlow()

    // Audio effect controller
    val audioEffectController: AudioEffectController by lazy { AudioEffectController.get(context) }

    // Shuffle state
    private val _isShuffleEnabled = MutableStateFlow(false)
    val isShuffleEnabledFlow: StateFlow<Boolean> = _isShuffleEnabled.asStateFlow()

    // Repeat state
    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatModeFlow: StateFlow<Int> = _repeatMode.asStateFlow()

    // Queue manager for recommendations
    private val queueManager: QueueManager by lazy { QueueManager.getInstance(context) }

    /**
     * Helper function to create validated MediaItem with artwork checking
     */
    @OptIn(UnstableApi::class)
    private fun createValidatedMediaItem(track: Track): MediaItem? {
        if (track.localUri == null) return null

        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)

        PlaybackService.applyArtwork(metadataBuilder, track.thumbnailUri, track.ytVideoId)

        return MediaItem.Builder()
            .setMediaId(track.uuid)
            .setUri(track.localUri)
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }

    @OptIn(UnstableApi::class)
    fun initialize() {
        if (controllerFuture == null) {
            val sessionToken =
                SessionToken(context, ComponentName(context, PlaybackService::class.java))
            controllerFuture = MediaController.Builder(ControllerBindingContext(context), sessionToken).setListener(object : MediaController.Listener {
                override fun onDisconnected(disconnected: MediaController) {
                    if (controller === disconnected) {
                        controller = null; controllerFuture = null
                        controllerPoll?.cancel()
                        controllerPoll = null
                        playerListener = null
                        connectionRetry?.cancel()
                        connectionRetry = scope.launch(Dispatchers.Main) {
                            kotlinx.coroutines.delay(1_000)
                            if (controller == null && controllerFuture == null) initialize()
                        }
                        _isPlaying.value = false; _isBuffering.value = false
                    }
                }
            }).buildAsync()
            val connection = controllerFuture ?: return
            connection.addListener(
                {
                    if (controllerFuture !== connection) return@addListener
                    val connected = try { connection.get() }
                    catch (e: Exception) {
                        controllerFuture = null
                        MediaController.releaseFuture(connection)
                        connectionFailures++
                        if (pendingQueueAction != null && connectionFailures <= 3) {
                            connectionRetry?.cancel()
                            connectionRetry = scope.launch(Dispatchers.Main) {
                                kotlinx.coroutines.delay(1_000L * connectionFailures)
                                if (controllerFuture == null && controller == null) initialize()
                            }
                        }
                        _isPlaying.value = false
                        _isBuffering.value = false
                        com.example.juke.network.NetworkFeedback.notify("Player service unavailable. Tap play to reconnect.")
                        Log.w(TAG, "Player service connection failed: ${e.javaClass.simpleName}")
                        return@addListener
                    } ?: return@addListener
                    connectionFailures = 0
                    controller = connected
                    Log.d(TAG, "MediaController connected to PlaybackService")

                    // Immediately sync UI with current background state
                    controller?.let { ctrl ->
                        _isPlaying.value = ctrl.isPlaying
                        val count = ctrl.mediaItemCount
                        val currentQueue =
                            (0 until count).map { i -> ctrl.getMediaItemAt(i).mediaId }
                        _queueFlow.value = currentQueue
                        _currentQueueIndex.value = ctrl.currentMediaItemIndex
                        _currentTrackId.value = ctrl.currentMediaItem?.mediaId
                    }

                    // Add a Player.Listener on the controller's underlying player
                    playerListener = object : Player.Listener {
                        override fun onEvents(player: Player, events: Player.Events) {
                            if (events.contains(Player.EVENT_TIMELINE_CHANGED) || events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION))
                                controller?.let(::publishSnapshot)
                        }
                        override fun onTimelineChanged(
                            timeline: androidx.media3.common.Timeline,
                            reason: Int
                        ) {
                            super.onTimelineChanged(timeline, reason)
                            // Update queue flow when timeline changes (add/remove/move)
                            controller?.let { ctrl ->
                                val count = ctrl.mediaItemCount
                                val newScan = (0 until count).map { i ->
                                    ctrl.getMediaItemAt(i).mediaId
                                }
                                _queueFlow.value = newScan

                                // Also update queue index as it might have shifted
                                val currentIndex = ctrl.currentMediaItemIndex
                                if (currentIndex != _currentQueueIndex.value) {
                                    _currentQueueIndex.value = currentIndex
                                }

                                Log.d(
                                    TAG,
                                    "Timeline changed (reason=$reason), updated queue flow with ${newScan.size} items"
                                )
                            }
                        }

                        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                            if (playWhenReady) pausedAtMs = 0L
                            else if (pausedAtMs == 0L) pausedAtMs = android.os.SystemClock.elapsedRealtime()
                        }

                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            _isPlaying.value = isPlaying
                            Log.d(TAG, "PlayerListener onIsPlayingChanged: $isPlaying")
                        }

                        override fun onPlaybackStateChanged(playbackState: Int) {
                            _isBuffering.value = playbackState == Player.STATE_BUFFERING
                            Log.d(TAG, "PlayerListener playbackStateChanged: $playbackState")
                            if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) {
                                savePlaybackState()
                            }
                        }

                        override fun onPlayerErrorChanged(error: androidx.media3.common.PlaybackException?) {
                            if (error == null) return
                            _isBuffering.value = false
                            Log.e(TAG, "Player error: ${error.message}", error)
                            // PlaybackService owns recovery, including notification/background play.
                        }

                        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                            mediaItem?.let { item ->
                                val trackId = item.mediaId
                                _currentTrackId.value = trackId

                                // Update the queue index immediately
                                val currentIndex = controller?.currentMediaItemIndex ?: 0
                                _currentQueueIndex.value = currentIndex

                                Log.d(TAG, "Media item transition: $trackId at index $currentIndex")
                                savePlaybackState()

                                // Save queue structure to persist auto-added songs
                                scope.launch {
                                    saveQueueStructure()
                                }


                                // Track song play in analytics
                                scope.launch {
                                    try {
                                        Log.d(TAG, "Analytics: Looking up track with ID: $trackId")
                                        val track =
                                            database.trackDao().getTrackByUuid(trackId)?.toTrack()
                                        if (track != null) {
                                            Log.d(
                                                TAG,
                                                "Analytics: Found track '${track.title}' by ${track.artist}, calling trackSongPlayed"
                                            )
                                            AnalyticsManager.getInstance(context).trackSongPlayed(
                                                songId = "${track.title} - ${track.artist}",
                                                songTitle = track.title,
                                                songArtist = track.artist,
                                                songDuration = track.durationSec * 1000L,
                                                positionInQueue = currentIndex
                                            )
                                        } else {
                                            Log.w(
                                                TAG,
                                                "Analytics: Track not found in database for ID: $trackId"
                                            )
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error tracking song play: ${e.message}", e)
                                    }
                                }
                            }
                        }

                        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                            // ExoPlayer's native shuffle is never intentionally enabled.
                            // Shuffle is handled by pre-shuffling the track list before setQueue.
                            Log.d(TAG, "Shuffle mode changed (internal): $shuffleModeEnabled")
                        }

                        override fun onRepeatModeChanged(repeatMode: Int) {
                            _repeatMode.value = repeatMode
                            Log.d(TAG, "Repeat mode changed: $repeatMode")
                        }
                    }
                    try {
                        controller?.addListener(playerListener!!)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to add player listener: ${e.message}")
                    }

                    // Start a small polling loop as a fallback to ensure external changes
                    controllerPoll?.cancel()
                    controllerPoll = scope.launch {
                        var last = _isPlaying.value
                        while (controller != null) {
                            try {
                                val playing = withContext(Dispatchers.Main) {
                                    controller?.isPlaying == true
                                }

                                if (playing != last) {
                                    _isPlaying.value = playing
                                    Log.d(TAG, "Polled controller isPlaying: $playing")
                                    last = playing
                                }

                                // Also poll index to ensure sync
                                val currentIndex = withContext(Dispatchers.Main) {
                                    controller?.currentMediaItemIndex ?: 0
                                }
                                if (currentIndex != _currentQueueIndex.value) {
                                    _currentQueueIndex.value = currentIndex
                                    Log.d(TAG, "Polled controller index updated: $currentIndex")
                                }

                                // Save position every 5 seconds when playing
                                if (playing) {
                                    withContext(Dispatchers.Main) {
                                        savePlaybackState()
                                    }
                                }
                                kotlinx.coroutines.delay(5000)
                            } catch (e: Exception) {
                                Log.w(TAG, "Polling loop error: ${e.message}")
                                break
                            }
                        }
                    }

                    publishSnapshot(requireNotNull(controller))
                    val queued = pendingQueueAction
                    pendingQueueAction = null
                    if (queued != null) queued(requireNotNull(controller))
                    else if (!userQueueRequested) scope.launch { restorePlaybackState() }
                },
                androidx.core.content.ContextCompat.getMainExecutor(context)
            )

            Log.d(TAG, "PlaybackManager initialized")
        }
    }

    fun playTrack(track: Track) {
        val mediaItem = createValidatedMediaItem(track)
        if (mediaItem == null) {
            Log.e(TAG, "Cannot play track without local URI")
            return
        }

        initialize()
        _isShuffleEnabled.value = false

        controller?.apply {
            setMediaItem(mediaItem)
            prepare()
            play()
        }

        // Emit the current track ID
        _currentTrackId.value = track.uuid
        _currentQueueIndex.value = 0

        Log.d(TAG, "Playing track: ${track.title}")
    }

    @OptIn(UnstableApi::class)
    fun setQueue(
        tracks: List<Track>,
        startIndex: Int = 0,
        startPositionMs: Long = C.TIME_UNSET,
        keepShuffleMode: Boolean = false,
        playWhenReady: Boolean = true
    ) {
        userQueueRequested = true
        if (controller == null && controllerFuture == null) connectionFailures = 0
        initialize()

        if (!keepShuffleMode) {
            _isShuffleEnabled.value = false
        }

        // Audio URLs are stable per video. Keep cached bytes for immediate handoffs and seeks.

        val mediaItems = tracks.mapNotNull { track -> createValidatedMediaItem(track) }

        val claim = PhonePlaybackOwnership.token
        val action: (MediaController) -> Unit = action@{ ctrl ->
            if (!PhonePlaybackOwnership.permitsPlayback(claim)) return@action
            ctrl.apply {
            // When starting a fresh queue the caller is responsible for ordering the tracks
            // (pre-shuffling in Kotlin when shuffle is on). Disabling ExoPlayer's own shuffle
            // prevents double-shuffling where ExoPlayer would override the intended playback
            // order with its own random permutation, causing auto-advance to skip to the
            // wrong track when a song ends naturally.
            if (!keepShuffleMode) {
                shuffleModeEnabled = false
            }
            val reusable = mediaItemCount == mediaItems.size && mediaItems.indices.all { index ->
                val old = getMediaItemAt(index)
                val next = mediaItems[index]
                old.mediaId == next.mediaId && old.localConfiguration?.uri == next.localConfiguration?.uri &&
                    old.mediaMetadata.title?.toString() == next.mediaMetadata.title?.toString() &&
                    old.mediaMetadata.artist?.toString() == next.mediaMetadata.artist?.toString() &&
                    old.mediaMetadata.artworkUri == next.mediaMetadata.artworkUri
            }
            // A failed same-URI item needs a fresh loader, not a metadata-only update.
            if (playerError != null) stop()
            val seededPlayback = !reusable && mediaItems.size > 200
            if (reusable) {
                val position = startPositionMs.takeIf { it != C.TIME_UNSET } ?: 0
                if (currentMediaItemIndex != startIndex || kotlin.math.abs(currentPosition - position) > 250)
                    seekTo(startIndex, position)
            } else if (seededPlayback) {
                installQueueInBatches(mediaItems, startIndex,
                    seed = { item -> setMediaItems(listOf(item), 0, startPositionMs) },
                    insert = { index, batch -> addMediaItems(index, batch) },
                    startSelected = { pause(); prepare() })
            } else setMediaItems(mediaItems, startIndex, startPositionMs)
            if (!seededPlayback && (!reusable || playbackState != Player.STATE_READY)) prepare()
            // Prepare in parallel with large-queue installation, but do not
            // let a nearly finished seed auto-advance before its tail exists.
            if (playWhenReady) play() else pause()
        } }
        val ready = controller
        if (ready != null) action(ready) else pendingQueueAction = action

        // Emit the initial track ID
        tracks.getOrNull(startIndex)?.let { startTrack ->
            _currentTrackId.value = startTrack.uuid
            _currentQueueIndex.value = startIndex
        }

        Log.d(
            TAG,
            "Queue set with ${mediaItems.size} tracks, starting at index $startIndex pos $startPositionMs"
        )

        // Save queue to preferences
        scope.launch {
            saveQueueStructure()
        }
    }

    /**
     * Add tracks to the end of the current queue without interrupting playback.
     *
     * @param tracks Tracks to add
     */
    fun addToQueue(tracks: List<Track>) {
        initialize()

        val mediaItems = tracks.mapNotNull { track -> createValidatedMediaItem(track) }

        if (mediaItems.isNotEmpty()) {
            controller?.addMediaItems(mediaItems)
            Log.d(TAG, "Added ${mediaItems.size} tracks to queue")

            // Save updated queue structure
            scope.launch {
                saveQueueStructure()
            }
        }
    }

    /**
     * Insert a track at a specific position in the current queue without interrupting playback.
     */
    fun addToQueueAt(track: Track, index: Int): Boolean {
        val mediaItem = createValidatedMediaItem(track)
        if (mediaItem == null) {
            Log.w(TAG, "Cannot enqueue track without local URI: ${track.title}")
            return false
        }

        initialize()

        controller?.let { ctrl ->
            val targetIndex = index.coerceIn(0, ctrl.mediaItemCount)
            ctrl.addMediaItem(targetIndex, mediaItem)
            Log.d(TAG, "Inserted track ${track.title} at index $targetIndex")

            // Save updated queue structure
            scope.launch {
                saveQueueStructure()
            }
            return true
        }

        return false
    }

    /**
     * Insert a list of tracks at a specific position in the current queue without interrupting playback.
     */
    fun addToQueueAt(tracks: List<Track>, index: Int): Boolean {
        val mediaItems = tracks.mapNotNull { createValidatedMediaItem(it) }
        if (mediaItems.isEmpty()) return false

        initialize()

        controller?.let { ctrl ->
            val targetIndex = index.coerceIn(0, ctrl.mediaItemCount)
            ctrl.addMediaItems(targetIndex, mediaItems)
            Log.d(TAG, "Inserted ${mediaItems.size} tracks at index $targetIndex")

            // Save updated queue structure
            scope.launch {
                saveQueueStructure()
            }
            return true
        }

        return false
    }


    fun needsPlaybackReload(): Boolean = shouldReloadPhonePlayback(controller != null, controller?.playerError != null,
        controller?.playbackState == Player.STATE_IDLE, if (pausedAtMs == 0L) 0L else (android.os.SystemClock.elapsedRealtime() - pausedAtMs).coerceAtLeast(0))

    fun togglePlayPause() {
        controller?.let {
            if (it.isPlaying || (it.playWhenReady && it.playbackState == Player.STATE_BUFFERING)) {
                it.pause()
                Log.d(TAG, "Paused")
            } else {
                if (it.playbackState == Player.STATE_ENDED) it.seekTo(0)
                if (it.playbackState == Player.STATE_IDLE && it.playerError == null) it.prepare()
                it.play()
                Log.d(TAG, "Playing")
            }
        }
    }

    /** A handoff commits only after the destination has prepared its selected track. */
    suspend fun awaitReady(expectedId: String? = null) {
        PlaybackDiagnostics.measure(PlaybackDiagnostics.Stage.PLAYER_READY) { kotlinx.coroutines.withTimeout(30_000) {
            val started = android.os.SystemClock.elapsedRealtime()
            while (true) {
                val ready = controller
                val selected = expectedId == null || ready?.currentMediaItem?.mediaId == expectedId
                // Posted queue/prepare commands must first replace the previous error.
                if (selected && pendingQueueAction == null && android.os.SystemClock.elapsedRealtime() - started >= 1_000)
                    ready?.playerError?.let { throw it }
                if (ready?.playbackState == Player.STATE_READY && selected && ready.playerError == null && pendingQueueAction == null) return@withTimeout
                kotlinx.coroutines.delay(50)
            }
        } }
    }

    fun pause() {
        controller?.pause()
    }

    private fun runTrackChangePreservingPlayState(action: (Player) -> Unit) {
        controller?.let { ctrl ->
            val shouldPlayWhenReady = ctrl.playWhenReady
            action(ctrl)
            ctrl.playWhenReady = shouldPlayWhenReady
        }
    }

    private fun runTrackChangeStartingPlaybackIfTrackChanged(action: (Player) -> Unit) {
        controller?.let { ctrl ->
            val startingIndex = ctrl.currentMediaItemIndex
            action(ctrl)
            if (ctrl.currentMediaItemIndex != startingIndex) {
                ctrl.play()
            }
        }
    }

    fun shouldResumeAfterTrackChange(): Boolean {
        return controller?.playWhenReady ?: _isPlaying.value
    }

    fun skipToNext() {
        runTrackChangeStartingPlaybackIfTrackChanged { it.seekToNext() }
        Log.d(TAG, "Skip to next")
    }

    fun skipToPrevious() {
        runTrackChangeStartingPlaybackIfTrackChanged {
            if (it.currentPosition > 3000) {
                it.seekTo(0)
            } else {
                it.seekToPrevious()
            }
        }
        Log.d(TAG, "Skip to previous")
    }

    /** Replace a shuffled/sorted tail in one Media3 operation, retaining the playing item and buffer. */
    @OptIn(UnstableApi::class)
    fun replaceUpcoming(tracks: List<Track>, currentIndex: Int) {
        controller?.let { ctrl ->
            val currentId = ctrl.currentMediaItem?.mediaId ?: return
            if (tracks.getOrNull(currentIndex)?.uuid != currentId) return
            if (ctrl.currentMediaItemIndex > currentIndex) ctrl.removeMediaItems(0, ctrl.currentMediaItemIndex - currentIndex)
            val first = ctrl.currentMediaItemIndex + 1
            ctrl.replaceMediaItems(first, ctrl.mediaItemCount, tracks.drop(currentIndex + 1).mapNotNull(::createValidatedMediaItem))
            scope.launch { saveQueueStructure() }
        }
    }

    fun setShuffleEnabled(enabled: Boolean) { _isShuffleEnabled.value = enabled }

    fun toggleShuffle() {
        val isNowEnabled = !_isShuffleEnabled.value
        _isShuffleEnabled.value = isNowEnabled

        if (!isNowEnabled) {
            Log.d(TAG, "Shuffle disabled")
            return
        }

        controller?.let { ctrl ->
            val first = ctrl.currentMediaItemIndex + 1
            if (first < ctrl.mediaItemCount) {
                val tail = (first until ctrl.mediaItemCount).map(ctrl::getMediaItemAt).shuffled()
                ctrl.replaceMediaItems(first, ctrl.mediaItemCount, tail)
                scope.launch { saveQueueStructure() }
            }
        }
    }

    fun toggleRepeatMode() {
        controller?.let {
            val nextMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
                Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_OFF
                else -> Player.REPEAT_MODE_OFF
            }
            it.repeatMode = nextMode
            _repeatMode.value = nextMode
            Log.d(TAG, "Repeat mode toggled to: $nextMode")
        }
    }

    fun seekTo(positionMs: Long) {
        val ctrl = controller ?: return
        val target = positionMs.coerceAtLeast(0)
        ctrl.seekTo(target)
        Log.d(TAG, "Seeked to $target ms")
    }

    fun seekToIndex(index: Int) {
        runTrackChangePreservingPlayState { it.seekTo(index, 0L) }
        Log.d(TAG, "Seeked to index $index")
    }

    fun getCurrentPosition(): Long {
        return controller?.currentPosition ?: 0L
    }

    fun getBufferedPosition(expectedId: String?): Long {
        val current = controller ?: return 0
        if (current.currentMediaItem?.mediaId != expectedId || current.playerError != null) return 0
        return current.bufferedPosition.coerceAtLeast(0)
    }

    fun getDuration(): Long {
        return controller?.duration?.coerceAtLeast(0) ?: 0L
    }

    /**
     * Shrinks the queue to only the currently playing item without stopping playback.
     *
     * Stream cache entries for removed items are explicitly evicted so a manual queue clear
     * (for example when switching modes) does not leave stale media on disk.
     */
    @OptIn(UnstableApi::class)
    fun keepOnlyCurrentTrack() {
        controller?.let { ctrl ->
            val currentIndex = ctrl.currentMediaItemIndex
            val totalItems = ctrl.mediaItemCount

            if (totalItems <= 1 || currentIndex < 0) return

            // Remove items after current track
            for (i in totalItems - 1 downTo currentIndex + 1) {
                // Clear disk cache for explicit queue clear (e.g. Radio mode)
                PlaybackService.StreamCacheManager.removeTrackCache(ctrl.getMediaItemAt(i).localConfiguration?.uri?.toString())
                ctrl.removeMediaItem(i)
            }

            // Remove items before current track
            for (i in currentIndex - 1 downTo 0) {
                // Clear disk cache for explicit queue clear
                PlaybackService.StreamCacheManager.removeTrackCache(ctrl.getMediaItemAt(i).localConfiguration?.uri?.toString())
                ctrl.removeMediaItem(i)
            }

            Log.d(TAG, "Kept only current track at original index $currentIndex")

            // Update local state flows to reflect the new state immediately
            if (_currentQueueIndex.value != 0) {
                _currentQueueIndex.value = 0
            }

            scope.launch {
                saveQueueStructure()
            }
        }
    }

    /**
     * Correctly calculates remaining tracks in the current playback functionality.
     * Returns how many tracks remain after the current one in the queue.
     * ExoPlayer's native shuffle is always disabled (pre-shuffled lists are used instead),
     * so remaining tracks = total − current − 1.
     */
    fun getRemainingTracksCount(): Int {
        val ctrl = controller ?: return 0
        val current = ctrl.currentMediaItemIndex
        val total = ctrl.mediaItemCount
        return if (current in 0 until total) total - current - 1 else 0
    }

    /**
     * Remove a track from the queue by its media ID.
     *
     * @param mediaId The media ID of the track to remove
     * @return true if the track was removed, false otherwise
     */
    @OptIn(UnstableApi::class)
    fun removeFromQueue(mediaId: String): Boolean {
        controller?.let { ctrl ->
            val index = (0 until ctrl.mediaItemCount).firstOrNull { i ->
                ctrl.getMediaItemAt(i).mediaId == mediaId
            } ?: return false

            // Clear cache when song is explicitly swiped/removed from queue
            PlaybackService.StreamCacheManager.removeTrackCache(ctrl.getMediaItemAt(index).localConfiguration?.uri?.toString())

            // Use playlist API to avoid full re-prepare and reduce playback hiccup
            ctrl.removeMediaItem(index)
            Log.d(TAG, "Removed track $mediaId from queue at index $index")

            // Save updated queue structure
            scope.launch {
                saveQueueStructure()
            }
            return true
        }
        return false
    }

    /**
     * Replace a track in the queue with a new track (e.g., replacing streaming with downloaded version).
     * Useful for seamlessly transitioning from streaming to offline playback.
     *
     * @param oldMediaId The media ID of the track to replace
     * @param newTrack The new track to replace it with
     * @return true if the track was replaced, false otherwise
     */
    fun replaceTrackInQueue(
        oldMediaId: String,
        newTrack: Track,
        seamlessIfPlaying: Boolean = false
    ): Boolean {
        controller?.let { ctrl ->
            val index = (0 until ctrl.mediaItemCount).firstOrNull { i ->
                ctrl.getMediaItemAt(i).mediaId == oldMediaId
            } ?: return false

            val newMediaItem = createValidatedMediaItem(newTrack)
            if (newMediaItem == null) {
                Log.w(TAG, "Cannot replace track without valid media item: ${newTrack.title}")
                return false
            }

            val isCurrentTrack = ctrl.currentMediaItemIndex == index
            val currentPosition = if (isCurrentTrack) ctrl.currentPosition else 0L

            if (isCurrentTrack && seamlessIfPlaying) {
                Log.d(TAG, "Kept the playing stream for $oldMediaId")
            } else {
                // Atomic replacement prevents the timeline "blip" that causes queue duplication
                ctrl.replaceMediaItem(index, newMediaItem)
                if (isCurrentTrack) {
                    ctrl.seekTo(index, currentPosition)
                    _currentTrackId.value = newTrack.uuid
                    _currentQueueIndex.value = index
                }
            }

            Log.d(TAG, "Replaced track $oldMediaId with ${newTrack.uuid} at index $index")

            // Save updated queue structure
            scope.launch {
                saveQueueStructure()
            }
            return true
        }
        return false
    }

    /**
     * Move a track to a new position in the queue.
     *
     * @param fromIndex Current index of the track
     * @param toIndex New index for the track
     * @return true if the move was successful, false otherwise
     */
    /**
     * Remove a deleted track from the queue to prevent playback errors.
     * Should be called when a track is deleted from the library.
     *
     * @param trackUuid UUID of the deleted track
     */
    @OptIn(UnstableApi::class)
    fun removeDeletedTrackFromQueue(trackUuid: String) {
        controller?.let { ctrl ->
            // Find all instances of this track in the queue
            val indicesToRemove = mutableListOf<Int>()
            for (i in 0 until ctrl.mediaItemCount) {
                if (ctrl.getMediaItemAt(i).mediaId == trackUuid) {
                    indicesToRemove.add(i)
                }
            }

            if (indicesToRemove.isEmpty()) {
                return
            }

            val currentIndex = ctrl.currentMediaItemIndex
            val isCurrentTrack = indicesToRemove.contains(currentIndex)

            // Remove from queue (remove in reverse order to maintain indices)
            indicesToRemove.sortedDescending().forEach { index ->
                PlaybackService.StreamCacheManager.removeTrackCache(ctrl.getMediaItemAt(index).localConfiguration?.uri?.toString())
                ctrl.removeMediaItem(index)
                Log.d(TAG, "Removed deleted track from queue at index $index")
            }

            // If the deleted track was playing, skip to next
            if (isCurrentTrack) {
                Log.w(TAG, "Deleted track was currently playing, skipping to next")
                if (ctrl.hasNextMediaItem()) {
                    ctrl.prepare()
                    ctrl.play()
                } else {
                    ctrl.stop()
                    _currentTrackId.value = null
                    Log.d(TAG, "No next track available after deletion, stopping playback")
                }
            }

            // Save updated queue
            scope.launch {
                saveQueueStructure()
            }
        }
    }

    fun moveInQueue(fromIndex: Int, toIndex: Int): Boolean {
        controller?.let { ctrl ->
            if (fromIndex < 0 || fromIndex >= ctrl.mediaItemCount ||
                toIndex < 0 || toIndex >= ctrl.mediaItemCount
            ) {
                return false
            }

            // Use playlist move to minimize playback interruption
            ctrl.moveMediaItem(fromIndex, toIndex)
            Log.d(TAG, "Moved track from index $fromIndex to $toIndex via playlist API")

            // Save updated queue structure
            scope.launch {
                saveQueueStructure()
            }
            return true
        }
        return false
    }

    /**
     * Start sleep timer that will pause playback after specified minutes.
     * @param minutes Duration in minutes
     */
    fun startSleepTimer(minutes: Int) {
        cancelSleepTimer()

        val durationMs = minutes * 60 * 1000L
        sleepTimerJob = scope.launch {
            var remaining = durationMs
            while (remaining > 0) {
                _sleepTimerRemaining.value = remaining
                kotlinx.coroutines.delay(1000)
                remaining -= 1000
            }

            // Timer finished - pause playback
            _sleepTimerRemaining.value = null
            controller?.pause()
            Log.d(TAG, "Sleep timer finished - paused playback")
        }

        Log.d(TAG, "Sleep timer started for $minutes minutes")
    }

    /**
     * Cancel active sleep timer.
     */
    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimerRemaining.value = null
        Log.d(TAG, "Sleep timer cancelled")
    }

    fun release() {
        connectionRetry?.cancel()
        connectionRetry = null
        cancelSleepTimer()
        savePlaybackState() // Save state before releasing
        val connection = controllerFuture
        val connected = controller
        controllerFuture = null
        controller = null
        pendingQueueAction = null
        controllerPoll?.cancel()
        controllerPoll = null
        // Detach before Media3 schedules asynchronous controller cleanup.
        // remove player listener if attached
        try {
            playerListener?.let { connected?.removeListener(it) }
        } catch (_: Exception) {
        }
        playerListener = null
        connection?.let { MediaController.releaseFuture(it) }
        Log.d(TAG, "PlaybackManager released")
    }

    private fun savePlaybackState() {
        try {
            val position = controller?.currentPosition ?: 0L
            val currentIndex = controller?.currentMediaItemIndex ?: -1

            prefs.edit().apply {
                putLong("playback_position", position)
                putInt("queue_start_index", currentIndex)
                apply()
            }
            Log.d(TAG, "Saved playback state: position=$position, index=$currentIndex")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save playback state: ${e.message}")
        }
    }

    private suspend fun saveQueueStructure() {
        try {
            val trackIds = withContext(Dispatchers.Main) {
                val ctrl = controller ?: return@withContext emptyList<String>()
                val count = ctrl.mediaItemCount
                (0 until count).map { i ->
                    ctrl.getMediaItemAt(i).mediaId
                }
            }

            if (trackIds.isEmpty()) return

            val idsString = trackIds.joinToString(",")
            val currentIndex =
                withContext(Dispatchers.Main) { controller?.currentMediaItemIndex ?: 0 }
            val currentPosition =
                withContext(Dispatchers.Main) { controller?.currentPosition ?: 0L }

            prefs.edit().apply {
                putString("queue_track_ids", idsString)
                putInt("queue_start_index", currentIndex)
                putLong("playback_position", currentPosition)
                putBoolean("has_saved_state", true)
                apply()
            }
            Log.d(TAG, "Saved queue structure: ${trackIds.size} tracks")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save queue structure: ${e.message}")
        }
    }

    private suspend fun restorePlaybackState() {
        try {
            if (!prefs.getBoolean("has_saved_state", false)) {
                Log.d(TAG, "No saved playback state found")
                return
            }

            val trackIds = prefs.getString("queue_track_ids", "") ?: ""
            if (trackIds.isEmpty()) {
                Log.d(TAG, "No saved queue found")
                return
            }

            val ids = trackIds.split(",")
            val savedIndex = prefs.getInt("queue_start_index", 0)
            val savedPosition = prefs.getLong("playback_position", 0L)

            // Load tracks from database, re-resolving stream files if needed
            val tracks = withContext(Dispatchers.IO) {
                ids.mapNotNull { id ->
                    try {
                        val entity =
                            database.trackDao().getTrackByUuid(id) ?: return@mapNotNull null
                        var track = entity.toTrack()

                        // Rebuild remote URIs on upgrades; old Echo proxy URLs can interrupt phone audio.
                        if (!track.ytVideoId.isNullOrBlank()) {
                            DownloadRepository.get(context).awaitReady()
                            track = DownloadRepository.get(context).localTrack(track) ?: track.copy(
                                localUri = AlexaBackendApi.audioUrl(track.ytVideoId!!), isStream = true)
                        }

                        track
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load track $id: ${e.message}")
                        null
                    }
                }
            }

            if (tracks.isEmpty()) {
                Log.d(TAG, "No tracks found in database for saved queue")
                return
            }

            Log.d(
                TAG,
                "Restoring playback state: ${tracks.size} tracks, index=$savedIndex, position=$savedPosition"
            )

            // Restore queue — use createValidatedMediaItem but fall back to URI-less items
            // for stream tracks that couldn't be re-resolved (they'll trigger error recovery)
            val mediaItems = tracks.mapNotNull { track -> createValidatedMediaItem(track) }

            if (mediaItems.isEmpty()) {
                Log.d(TAG, "No playable media items could be created from saved queue")
                return
            }

            // MediaController methods must be called on main thread
            withContext(Dispatchers.Main) {
                if (userQueueRequested) return@withContext
                controller?.apply {
                    setMediaItems(
                        mediaItems,
                        savedIndex.coerceIn(0, mediaItems.size - 1),
                        savedPosition
                    )
                    prepare()
                    // Don't auto-play, just prepare to paused state
                }

                tracks.getOrNull(savedIndex)?.let { track ->
                    _currentTrackId.value = track.uuid
                }

                // Set the restored index
                _currentQueueIndex.value = savedIndex

                // Update QueueManager with remaining tracks from current position
                // QueueManager treats index 0 as "current track", so we pass only tracks from savedIndex onwards
                // This prevents state desync between ExoPlayer's position and QueueManager's internal state
                val remainingTracks = tracks.drop(savedIndex)
                if (remainingTracks.isNotEmpty()) {
                    queueManager.initializeQueue(remainingTracks)
                }

                _hasRestoredState.value = true
                Log.d(TAG, "Playback state restored successfully")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore playback state: ${e.message}", e)
        }
    }
}
