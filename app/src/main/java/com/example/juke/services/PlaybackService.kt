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
 * Media Playback Service using Media3 (ExoPlayer) with Android Auto support.
 */
@UnstableApi
class PlaybackService : MediaLibraryService() {

    private val TAG = "PlaybackService"

    companion object {
        private var activeService = java.lang.ref.WeakReference<PlaybackService>(null)

        /** Pause the actual service player before acknowledging an app-initiated handoff. */
        internal suspend fun pausePhoneForHandoff() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
            val service = checkNotNull(activeService.get()) { "Phone playback service is unavailable." }
            service.player.pause()
            check(!service.player.playWhenReady && !service.player.isPlaying) { "Phone playback has not paused." }
        }

        /** Audio prefs that decide whether offloaded (battery saver) playback can engage. */
        private val OFFLOAD_KEYS = setOf(
            "battery_saver_playback", "skip_silence_enabled", "booster_enabled", "normalization_enabled"
        )
        private const val CUSTOM_COMMAND_TOGGLE_FAVORITE_ACTION_ID =
            "CUSTOM_COMMAND_TOGGLE_FAVORITE"

        /**
         * Resolve artwork for a track into MediaMetadata.
         *
         * Use artwork URIs for both remote and local images so queue operations do not clone large
         * embedded byte arrays for every MediaItem. The session bitmap loader can resolve the URI
         * lazily when artwork is actually needed for the active item/notification.
         */
        internal fun applyArtwork(metadataBuilder: MediaMetadata.Builder, thumbnailUri: String?) {
            thumbnailUri?.takeIf { it.isNotEmpty() }?.let { uriString ->
                try {
                    val artworkUri = when {
                        uriString.startsWith("http", ignoreCase = true) -> uriString.toUri()
                        uriString.startsWith("file://", ignoreCase = true) -> uriString.toUri()
                        uriString.startsWith("content://", ignoreCase = true) -> uriString.toUri()
                        else -> {
                            val file = java.io.File(uriString)
                            if (file.exists() && file.canRead() && file.length() > 0) {
                                file.toUri()
                            } else {
                                null
                            }
                        }
                    }

                    artworkUri?.let(metadataBuilder::setArtworkUri)
                } catch (e: Exception) {
                    // Silently ignore artwork errors — notification will just show no art
                }
            }
        }
    }

    /**
     * Singleton that owns the ExoPlayer stream cache for the process lifetime.
     * Removed 'private' so PlaybackManager can trigger explicit cleanup rules.
     */
    @UnstableApi
    object StreamCacheManager { // <-- Removed 'private' modifier
        private const val MAX_CACHE_BYTES = 256L * 1024 * 1024 // 256 MB

        @Volatile
        private var cache: SimpleCache? = null

        fun getCache(context: Context): SimpleCache {
            return cache ?: synchronized(this) {
                cache ?: run {
                    val cacheDir = java.io.File(context.cacheDir, "stream_cache")
                    val evictor = LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES)
                    val databaseProvider =
                        androidx.media3.database.StandaloneDatabaseProvider(context)
                    SimpleCache(cacheDir, evictor, databaseProvider).also { cache = it }
                }
            }
        }

        // Add this to clear everything (e.g. queue replacement)
        fun clearAllCache() {
            synchronized(this) {
                cache?.let { c ->
                    c.keys.toList().forEach { key ->
                        c.removeResource(key)
                    }
                }
            }
        }

        // Add this to clear specific tracks
        fun removeTrackCache(uri: String?) {
            if (uri == null) return
            synchronized(this) {
                cache?.removeResource(uri)
            }
        }

        fun release() {
            synchronized(this) {
                cache?.release()
                cache = null
            }
        }
    }

    private var mediaSession: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer
    private lateinit var database: MusicDatabase
    private val queueManager by lazy { QueueManager.getInstance(applicationContext) }
    val audioEffectController: AudioEffectController by lazy { AudioEffectController.get(this) }

    // For Stream Mode cleanup and progress tracking
    private var previousTrackId: String? = null

    // Track which songs have reached 50% during this playback session
    private val tracksPlayCountedThisSession = mutableSetOf<String>()
    private var currentPlayingTrackId: String? = null
    private var lastReportedListen: String? = null
    private var streamRecoveryJob: Job? = null
    private var recoveryShouldResume = false
    private val recoveryAttempts = mutableMapOf<String, Int>()
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            if (::player.isInitialized && player.isPlaying) {
                // Check if current track has reached 50% of total duration
                this@PlaybackService.checkPlayCountThreshold()
                progressHandler.postDelayed(this, nextProgressCheckDelay())
            }
        }
    }

    /**
     * Sleep until the next position that matters (50% play-count mark, 15 s pre-fetch window)
     * instead of waking every second; capped so a missed event is never more than 10 s late.
     */
    private fun nextProgressCheckDelay(): Long {
        val duration = player.duration
        val position = player.currentPosition
        if (duration <= 0) return 1_000L
        val events = buildList {
            if (currentPlayingTrackId !in tracksPlayCountedThisSession) add(duration / 2 - position)
            add(duration - 15_000L - position)
        }.filter { it > 0 }
        val speed = player.playbackParameters.speed.coerceAtLeast(0.25f)
        val untilNext = ((events.minOrNull() ?: 10_000L) / speed).toLong()
        return untilNext.coerceIn(1_000L, 10_000L)
    }

    // Preference listener for skip silence 
    private val audioSettingsListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == "skip_silence_enabled") {
                val isEnabled = prefs.getBoolean("skip_silence_enabled", false)
                // Edge-only skipping (own processor); ExoPlayer's skipSilenceEnabled would cut mid-song gaps.
                audioEffectController.edgeSilence.enabled = isEnabled
                Log.d(TAG, "Skip silence (edges) enabled: $isEnabled")
            }
            if (key in OFFLOAD_KEYS) applyOffloadPreference(prefs)
        }

    /**
     * Battery saver: hand compressed audio to the phone's audio DSP (offload) so the CPU can sleep
     * between large buffers. Offloaded audio bypasses our PCM processors, so it only engages while
     * skip-silence, boost and stable volume are all off; Media3 falls back to normal playback
     * whenever the device or format can't offload.
     */
    @OptIn(UnstableApi::class)
    private fun applyOffloadPreference(prefs: android.content.SharedPreferences) {
        if (!::player.isInitialized) return
        val allowed = prefs.getBoolean("battery_saver_playback", false) &&
            !prefs.getBoolean("skip_silence_enabled", false) &&
            !prefs.getBoolean("booster_enabled", false) &&
            !prefs.getBoolean("normalization_enabled", false)
        val offload = TrackSelectionParameters.AudioOffloadPreferences.Builder()
            .setAudioOffloadMode(
                if (allowed) TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
                else TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
            )
            .setIsGaplessSupportRequired(false)
            .setIsSpeedChangeSupportRequired(true)
            .build()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setAudioOffloadPreferences(offload)
            .build()
        Log.d(TAG, "Audio offload ${if (allowed) "enabled" else "disabled"}")
    }

    private var upcomingPreloader: UpcomingAudioPreloader? = null
    private var preloadConnectivityJob: Job? = null
    private lateinit var audioManager: AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingPlayAfterManualTrackChangeFromIndex: Int? = null

    private fun rememberManualTrackChangeRequest(@Player.Command playerCommand: Int) {
        pendingPlayAfterManualTrackChangeFromIndex =
            if (willTrackChangeForCommand(playerCommand)) {
                player.currentMediaItemIndex.takeIf { it != C.INDEX_UNSET }
            } else {
                null
            }
    }

    private fun willTrackChangeForCommand(@Player.Command playerCommand: Int): Boolean {
        return when (playerCommand) {
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> player.hasNextMediaItem()

            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> player.hasPreviousMediaItem()

            Player.COMMAND_SEEK_TO_PREVIOUS -> {
                player.hasPreviousMediaItem() && (
                    (player.isCurrentMediaItemLive && !player.isCurrentMediaItemSeekable) ||
                        player.currentPosition <= player.maxSeekToPreviousPosition
                    )
            }

            else -> false
        }
    }

    private fun maybeStartPlaybackAfterManualTrackChange() {
        val previousIndex = pendingPlayAfterManualTrackChangeFromIndex ?: return
        pendingPlayAfterManualTrackChangeFromIndex = null

        if (player.currentMediaItemIndex != previousIndex) {
            player.play()
            Log.d(TAG, "Started playback after manual next/previous track change")
        }
    }

    // Calls: ExoPlayer owns audio focus (handleAudioFocus = true). A call takes transient focus, so
    // playback pauses and resumes by itself - no READ_PHONE_STATE needed. Focus can come back
    // briefly while the call is connecting; AudioManager.mode (no permission) tells us a call is
    // still live, so we hold the resume until the mode returns to normal.
    private fun inCall() = audioManager.mode == AudioManager.MODE_IN_CALL ||
        audioManager.mode == AudioManager.MODE_IN_COMMUNICATION ||
        audioManager.mode == AudioManager.MODE_RINGTONE

    private val resumeAfterCall = object : Runnable {
        override fun run() {
            if (inCall()) mainHandler.postDelayed(this, 1_500)
            else if (getSharedPreferences("music_settings_prefs", MODE_PRIVATE).getString("playback_output", "PHONE") != "ALEXA") {
                player.play()
                Log.d(TAG, "Call ended - resumed")
            }
        }
    }

    private val callGuard = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS && inCall()) {
                player.pause()
                mainHandler.removeCallbacks(resumeAfterCall)
                mainHandler.postDelayed(resumeAfterCall, 500)
                Log.d(TAG, "Focus returned during a call - held")
            }
        }
    }

    private val outputPrefs by lazy { getSharedPreferences("music_settings_prefs", MODE_PRIVATE) }
    private val outputListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "playback_output" && ::player.isInitialized) {
            if (outputPrefs.getString(key, "PHONE") == "ALEXA") {
                mainHandler.removeCallbacks(resumeAfterCall)
                player.pause()
                stopForeground(STOP_FOREGROUND_REMOVE)
                getSystemService(NotificationManager::class.java).cancel(1)
            } else {
                if (player.isPlaying) reportDeviceListen()
                mediaSession?.let { onUpdateNotification(it, player.isPlaying) }
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Fix: Check if app is in background before attempting anything that might require foreground
        // This prevents ForegroundServiceStartNotAllowedException on Android 12+
        var isAppInForeground = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val currentState =
                    androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState
                isAppInForeground =
                    currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
                if (!isAppInForeground) {
                    Log.w(
                        TAG,
                        "App is in background, suppressing initial startForeground to avoid crash"
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to check app lifecycle state: ${e.message}")
            }
        }

        // We DO NOT startForeground here with a placeholder anymore.
        // We let MediaLibraryService (Media3) handle notification and foreground promotion 
        // when playback actually starts or a notification is explicitly requested by the session.

        return try {
            super.onStartCommand(intent, flags, startId)
        } catch (e: Exception) {
            Log.e(TAG, "Error in super.onStartCommand: ${e.message}", e)
            START_STICKY
        }
    }

    /**
     * Helper function to create validated MediaItem with artwork checking
     */
    private fun createValidatedMediaItem(track: Track): MediaItem? {
        if (track.localUri == null) return null

        // Check if it's a remote URL (http/https)
        val isRemote = track.localUri.startsWith("http", ignoreCase = true)

        // Only validate file existence if it's a local path
        if (!isRemote) {
            try {
                val uri = track.localUri.toUri()
                val file = java.io.File(uri.path ?: "")
                if (!file.exists() || !file.canRead() || file.length() <= 0) {
                    // Start of workaround for content:// URIs
                    if (!track.localUri.startsWith("content://")) {
                        return null
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error validating local file: ${e.message}")
            }
        }

        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)

        applyArtwork(metadataBuilder, track.thumbnailUri)

        return MediaItem.Builder()
            .setMediaId(track.uuid)
            .setUri(track.localUri)
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }

    /** Recover in the service so notification controls and background playback work too. */
    private fun recoverCurrentStream(manual: Boolean = false) {
        if (outputPrefs.getString("playback_output", "PHONE") == "ALEXA") return
        if (streamRecoveryJob?.isActive == true) {
            if (manual) recoveryShouldResume = true
            return
        }
        val original = player.currentMediaItem ?: return
        val trackId = original.mediaId
        val attempt = if (manual) 1 else (recoveryAttempts[trackId] ?: 0) + 1
        if (attempt > 2) {
            com.example.juke.network.NetworkFeedback.notify("Playback interrupted. Tap Play to try again.")
            return
        }
        recoveryAttempts[trackId] = attempt
        val claim = PhonePlaybackOwnership.token
        val httpFailure = generateSequence<Throwable>(player.playerError) { it.cause }
            .filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>().firstOrNull()
        val retryAfter = httpFailure?.headerFields?.entries?.firstOrNull { it.key.equals("Retry-After", true) }?.value?.firstOrNull()
        val retryDelay = com.example.juke.network.audioRetryDelay(httpFailure?.responseCode, retryAfter, attempt)
        val position = player.currentPosition.coerceAtLeast(0)
        recoveryShouldResume = manual || player.playWhenReady
        streamRecoveryJob = serviceScope.launch {
            try {
                val track = withContext(Dispatchers.IO) { database.trackDao().getTrackByUuid(trackId)?.toTrack() }
                    ?: return@launch
                val local = DownloadRepository.get(applicationContext).localTrack(track)
                val refreshed = if (local != null) local else {
                    val video = track.ytVideoId ?: return@launch
                    if (!manual || httpFailure?.responseCode == 429) delay(retryDelay)
                    val url = withContext(Dispatchers.IO) { AlexaBackendApi.getStreamUrl(video) }
                    track.copy(localUri = url, isStream = true)
                }
                if (player.currentMediaItem?.mediaId != trackId || !PhonePlaybackOwnership.permitsPlayback(claim) ||
                    outputPrefs.getString("playback_output", "PHONE") == "ALEXA") return@launch
                withContext(Dispatchers.IO) {
                    database.trackDao().insertTrack(refreshed.toEntity())
                    StreamCacheManager.removeTrackCache(original.localConfiguration?.uri?.toString())
                }
                if (player.currentMediaItem?.mediaId != trackId) return@launch
                val index = player.currentMediaItemIndex
                player.replaceMediaItem(index, original.buildUpon().setUri(refreshed.localUri!!).build())
                player.seekTo(index, position)
                player.prepare()
                player.playWhenReady = recoveryShouldResume && !inCall()
                if (recoveryShouldResume && inCall()) mainHandler.postDelayed(resumeAfterCall, 500)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (player.currentMediaItem?.mediaId == trackId) {
                    com.example.juke.network.NetworkFeedback.notify(
                        com.example.juke.network.networkErrorMessage(e) ?: "Playback interrupted. Tap Play to try again.")
                }
            } finally { if (streamRecoveryJob === coroutineContext[Job]) streamRecoveryJob = null }
        }
    }

    private val audioAttributes = AudioAttributes.Builder()
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .setUsage(C.USAGE_MEDIA)
        .build()

    /** Report an actual device play, including automatic queue advances, to the YouTube account. */
    private fun reportDeviceListen() {
        val mediaId = player.currentMediaItem?.mediaId ?: return
        if (lastReportedListen == mediaId) return
        if (getSharedPreferences("music_settings_prefs", MODE_PRIVATE).getString("playback_output", "PHONE") == "ALEXA") return
        lastReportedListen = mediaId
        serviceScope.launch {
            try {
                val track = database.trackDao().getTrackByUuid(mediaId) ?: return@launch
                val videoId = track.ytVideoId ?: return@launch
                com.example.juke.network.Backend.post("/api/track/$videoId/listen", kotlinx.serialization.json.buildJsonObject {
                    put("title", track.title)
                    put("artist", track.artist)
                    put("thumbnail", track.thumbnailUri.orEmpty())
                })
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (lastReportedListen == mediaId) lastReportedListen = null
                Log.w(TAG, "Couldn't report device listening history")
            }
        }
    }

    private var queueContinuationJob: Job? = null
    private var lastContinuationSeed = ""
    private var continuationRetryAt = 0L
    private fun maybeExtendPhoneQueue() {
        if (!player.playWhenReady || !com.example.juke.network.NetworkFeedback.online.value ||
            !PhonePlaybackOwnership.permitsPlayback() || PhonePlaybackOwnership.token.isBlank() ||
            outputPrefs.getString("playback_output", "PHONE") == "ALEXA" || queueContinuationJob?.isActive == true ||
            android.os.SystemClock.elapsedRealtime() < continuationRetryAt || player.mediaItemCount >= 5000 ||
            player.mediaItemCount - player.currentMediaItemIndex - 1 > 2) return
        val current = player.currentMediaItem ?: return
        val claim = PhonePlaybackOwnership.token
        val seedKey = "$claim:${current.mediaId}"
        if (lastContinuationSeed == seedKey) return
        val before = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        queueContinuationJob = serviceScope.launch {
            try {
                val seed = withContext(Dispatchers.IO) { database.trackDao().getTrackByUuid(current.mediaId)?.toTrack() } ?: return@launch
                val video = seed.ytVideoId ?: return@launch
                val radio = kotlinx.coroutines.withTimeout(12_000) { com.example.juke.network.AlexaBackendApi.getRadio(video) }
                val oldTracks = withContext(Dispatchers.IO) { before.mapNotNull { database.trackDao().getTrackByUuid(it)?.toTrack() } }
                val ids = unheardQueueIds(oldTracks.mapNotNull { it.ytVideoId }, radio.map { it.videoId })
                val tracks = radio.distinctBy { it.videoId }.filter { it.videoId in ids }.map {
                    val online = it.toAppTrack(com.example.juke.network.AlexaBackendApi.audioUrl(it.videoId))
                    DownloadRepository.get(applicationContext).localTrack(online) ?: online
                }
                val now = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
                if (!canApplyQueueContinuation(before, now, claim, PhonePlaybackOwnership.token,
                        PhonePlaybackOwnership.permitsPlayback(claim))) return@launch
                if (tracks.isEmpty()) { lastContinuationSeed = seedKey; return@launch }
                if (oldTracks.size != before.size) return@launch
                withContext(Dispatchers.IO) { database.trackDao().insertTracks(tracks.map { it.toEntity() }) }
                if (!canApplyQueueContinuation(before, (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId },
                        claim, PhonePlaybackOwnership.token, PhonePlaybackOwnership.permitsPlayback(claim))) return@launch
                val ended = player.playbackState == Player.STATE_ENDED
                val previousIndex = player.currentMediaItemIndex
                lastContinuationSeed = seedKey
                player.addMediaItems(tracks.mapNotNull(::createValidatedMediaItem))
                if (shouldAdvanceExhaustedQueue(ended, previousIndex, player.currentMediaItemIndex, player.playWhenReady, player.hasNextMediaItem())) { player.seekToNextMediaItem(); player.prepare(); player.play() }
                val index = player.currentMediaItemIndex
                val all = oldTracks + tracks
                val selected = all.getOrNull(index) ?: return@launch
                com.example.juke.network.AlexaBackendApi.updateQueue("start", requireNotNull(selected.ytVideoId),
                    all.map(com.example.juke.network.AlexaBackendApi::backendTrack), player.isPlaying,
                    player.currentPosition.coerceAtLeast(0), index, player.playbackState == Player.STATE_BUFFERING,
                    expectedToken = claim)
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                continuationRetryAt = android.os.SystemClock.elapsedRealtime() + 30_000
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                continuationRetryAt = android.os.SystemClock.elapsedRealtime() + 30_000
                PhonePlaybackOwnership.queueNeedsSync.tryEmit(Unit)
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_ENDED -> { Log.d(TAG, "Playback ended"); maybeExtendPhoneQueue() }
                Player.STATE_READY -> Log.d(TAG, "Player ready")
                Player.STATE_BUFFERING -> Log.d(TAG, "Buffering...")
                Player.STATE_IDLE -> Log.d(TAG, "Player idle")
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            Log.e(TAG, "Player error: ${error.message}", error)
            recoverCurrentStream()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady && !PhonePlaybackOwnership.permitsPlayback()) {
                player.pause()
                return
            }
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
                recoveryShouldResume = false
                streamRecoveryJob?.cancel()
                streamRecoveryJob = null
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Prevent infinite loops from metadata updates
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                // Clear the set of counted tracks when queue changes to ensure fresh counting
                tracksPlayCountedThisSession.clear()
                return
            }

            streamRecoveryJob?.cancel()
            streamRecoveryJob = null
            recoveryAttempts.clear()
            mediaItem?.let {
                val trackId = it.mediaId
                // Stable Volume memory: store where the AGC settled for the track that just ended and
                // start the next one at its own learned gain (no first-seconds loudness jump).
                // ponytail: one float per played track in prefs; prune if it ever matters.
                if (audioEffectController.isNormalizationEnabled.value) {
                    val gains = getSharedPreferences("agc_gains", MODE_PRIVATE)
                    currentPlayingTrackId?.let { prev ->
                        gains.edit().putFloat(prev, audioEffectController.boostProcessor.currentAgcGain).apply()
                    }
                    if (gains.contains(trackId)) {
                        audioEffectController.boostProcessor.seedAgc(gains.getFloat(trackId, 1f))
                    }
                }
                currentPlayingTrackId = trackId
                lastReportedListen = null
                maybeExtendPhoneQueue()
                if (player.isPlaying) reportDeviceListen()
                Log.d(TAG, "Media item transition: $trackId, reason: $reason")
                // Note: Play count is now incremented only when track reaches 50% via checkPlayCountThreshold()

                // Update custom layout (Notification Button)
                serviceScope.launch {
                    try {
                        val track = database.trackDao().getTrackByUuid(trackId)?.toTrack()
                        withContext(Dispatchers.Main) {
                            updateCustomLayout(AccountRepository.isLiked(track?.ytVideoId))
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error updating custom layout: ${e.message}")
                    }
                }

                // Delayed metadata re-push to fix notification artwork lag.
                // Media3's DefaultMediaNotificationProvider fetches artwork asynchronously
                // from the MediaItem's artworkUri at the moment of transition. If the
                // thumbnail file isn't ready yet (e.g., stream track with HTTP thumb,
                // or thumb was still downloading), the notification shows blank/stale art.
                // Re-pushing the same metadata 3 seconds later forces a redraw with the
                // correct thumbnail once it's available.
                serviceScope.launch {
                    try {
                        kotlinx.coroutines.delay(3_000L)
                        // Only refresh if this track is still playing (user hasn't skipped)
                        if (currentPlayingTrackId != trackId) return@launch
                        val track = database.trackDao().getTrackByUuid(trackId)?.toTrack()
                            ?: return@launch
                        val index = (0 until player.mediaItemCount).firstOrNull { i ->
                            player.getMediaItemAt(i).mediaId == trackId
                        } ?: return@launch
                        val refreshedItem = createValidatedMediaItem(track) ?: return@launch
                        // replaceMediaItem triggers onTimelineChanged → notification redraw
                        player.replaceMediaItem(index, refreshedItem)
                        Log.d(TAG, "Refreshed notification metadata for: ${track.title}")
                    } catch (e: Exception) {
                        Log.w(TAG, "Delayed metadata refresh failed: ${e.message}")
                    }
                }

                // Cleanup previous track if it was a stream
                val oldTrackId = previousTrackId
                previousTrackId = trackId

                if (oldTrackId != null && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    serviceScope.launch {
                        try {
                            // Try QueueManager's in-memory queue first (works for streams not in DB)
                            val oldTrack =
                                queueManager.currentQueue.value.find { it.uuid == oldTrackId }
                                    ?: database.trackDao().getTrackByUuid(oldTrackId)?.toTrack()

                            if (oldTrack != null && oldTrack.isStream) {
                                // Wait 3 seconds before cleaning up the finished stream file.
                                // ExoPlayer's CacheDataSource may still be draining its read
                                // handle on the old file (closing buffers) at transition time.
                                kotlinx.coroutines.delay(3_000L)

                                // Retain streamed bytes for replay, shuffle and output handoffs.
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error cleaning up stream track: ${e.message}")
                        }
                    }
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            Log.d(TAG, "Is playing: $isPlaying")
            if (isPlaying) {
                reportDeviceListen()
                progressHandler.post(progressRunnable)
            } else {
                progressHandler.removeCallbacks(progressRunnable)
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            // Seek or track change: the sleeping progress check was timed for the old position.
            if (player.isPlaying) {
                progressHandler.removeCallbacks(progressRunnable)
                progressHandler.post(progressRunnable)
            }
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        activeService = java.lang.ref.WeakReference(this)

        // Create notification channel for Android 8+
        // IMPORTANT: Must be created before Media3 initializes to avoid notification conflicts
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "media_playback",
                "Media Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Media playback controls"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        // Configure Media3 to use the same Notification ID and Channel
        // This prevents notification conflicts on Samsung and other devices
        val notificationProvider = DefaultMediaNotificationProvider.Builder(applicationContext)
            .setNotificationId(1) // IMPORTANT: Must match the ID in onStartCommand
            .setChannelId("media_playback")
            .build()
        setMediaNotificationProvider(notificationProvider)

        database = MusicDatabase.getDatabase(applicationContext)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ) = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(false) // boost processor works on 16-bit PCM
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessorChain(
                    JukeAudioChain(audioEffectController.edgeSilence, audioEffectController.boostProcessor)
                )
                .build()
        }
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            .setEnableDecoderFallback(true)

        val transferListener = object : androidx.media3.datasource.TransferListener {
            private val starts = java.util.concurrent.ConcurrentHashMap<androidx.media3.datasource.DataSource, Long>()
            override fun onTransferInitializing(source: androidx.media3.datasource.DataSource, spec: androidx.media3.datasource.DataSpec, network: Boolean) {
                starts[source] = android.os.SystemClock.elapsedRealtime()
            }
            override fun onTransferStart(source: androidx.media3.datasource.DataSource, spec: androidx.media3.datasource.DataSpec, network: Boolean) {
                val start = starts[source] ?: return
                Log.d(TAG, "Stream HTTP connected in ${android.os.SystemClock.elapsedRealtime() - start}ms (${spec.uri.host})")
            }
            override fun onBytesTransferred(source: androidx.media3.datasource.DataSource, spec: androidx.media3.datasource.DataSpec, network: Boolean, bytes: Int) {
                val start = starts.remove(source) ?: return
                Log.d(TAG, "Stream first bytes in ${android.os.SystemClock.elapsedRealtime() - start}ms")
            }
            override fun onTransferEnd(source: androidx.media3.datasource.DataSource, spec: androidx.media3.datasource.DataSpec, network: Boolean) {
                starts.remove(source)
            }
        }
        val mediaSourceFactory = DefaultMediaSourceFactory(playbackDataSources(
            applicationContext, StreamCacheManager.getCache(applicationContext), transferListener = transferListener))

        // Balanced LoadControl: 30s min buffer / 120s max buffer.
        // The previous 600s max was causing ExoPlayer to stall — it attempted to buffer
        // 10 minutes ahead but couldn't fill it from a local file fast enough, causing
        // the player to enter STATE_BUFFERING and appear to "pause" with no content.
        // For local file playback 30–120s is more than sufficient and stays responsive.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                30_000,  // minBufferMs
                120_000, // maxBufferMs (2 minutes ahead — enough without stalling)
                1_500,   // bufferForPlaybackMs
                3_000    // bufferForPlaybackAfterRebufferMs
            )
            .setBackBuffer(
                30_000, // backBufferDurationMs: 30s back-buffer for smooth seeking
                true    // retainBackBufferFromKeyframe
            )
            .build()

        player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, true) // ExoPlayer handles focus: pauses for calls, resumes after
            .setHandleAudioBecomingNoisy(true)
            // Hold CPU + Wi-Fi locks only while playing, so streams keep loading with the screen off.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setLoadControl(loadControl) // <-- Apply the LoadControl here
            .build()

        // Keep the safety deadline independent of suspended network requests.
        serviceScope.launch {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                delay(250)
                if (canContinueDownloadedOffline(com.example.juke.network.NetworkFeedback.online.value, player.currentMediaItem?.localConfiguration?.uri?.scheme) && PhonePlaybackOwnership.token.isNotBlank()) {
                    // An inaccessible server cannot renew a lease; completed downloads still play offline.
                    PhonePlaybackOwnership.forget(allowOffline = true)
                }
                if (PhonePlaybackOwnership.token.isNotBlank() && player.playWhenReady &&
                    android.os.SystemClock.elapsedRealtime() >= PhonePlaybackOwnership.leaseUntilMs) player.pause()
            }
        }
        // Ownership remains enforced by the foreground service when the Activity is closed.
        serviceScope.launch {
            var lastReport = 0L
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                delay(1_000)
                val claim = PhonePlaybackOwnership.token
                if (claim.isBlank() || PhonePlaybackOwnership.localHandoff) continue
                maybeExtendPhoneQueue()
                try {
                    val status = kotlinx.coroutines.withTimeoutOrNull(1_500) {
                        com.example.juke.network.AlexaBackendApi.phoneOutputStatus()
                    }
                    if (!canApplyPhoneOwnershipPoll(claim, PhonePlaybackOwnership.token, PhonePlaybackOwnership.localHandoff)) continue
                    if (status != null && !status.belongsToPhone(PhonePlaybackOwnership.ownerId, claim)) {
                        player.pause()
                        if (!PhonePlaybackOwnership.localHandoff && status.mode == "alexa") {
                            outputPrefs.edit().putString("playback_output", "ALEXA")
                                .apply { if (status.serial.isNotBlank()) putString("echo_serial", status.serial) }.apply()
                        }
                        PhonePlaybackOwnership.releaseTo(status)
                        if (!PhonePlaybackOwnership.localHandoff && status.mode == "alexa") {
                            val snapshot = com.example.juke.network.AlexaBackendApi.phoneQueueSnapshot()
                            RemotePlaybackService.start(applicationContext, parseEchoSnapshot(snapshot,
                                android.os.SystemClock.elapsedRealtime(), null, false), false)
                        }
                        continue
                    }
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (status != null && now - lastReport >= 3_000) {
                        val renewed = kotlinx.coroutines.withTimeoutOrNull(1_500) {
                            com.example.juke.network.AlexaBackendApi.phoneOutputRequest("heartbeat",
                                PhonePlaybackOwnership.ownerId, claim)
                        }
                        if (renewed != null) {
                            if (!canApplyPhoneOwnershipPoll(claim, PhonePlaybackOwnership.token, PhonePlaybackOwnership.localHandoff)) continue
                            PhonePlaybackOwnership.accept(renewed)
                            lastReport = now
                            val mediaId = player.currentMediaItem?.mediaId
                            val current = mediaId?.let { database.trackDao().getTrackByUuid(it) }
                            if (player.currentMediaItem?.mediaId != mediaId || !canApplyPhoneOwnershipPoll(claim, PhonePlaybackOwnership.token, PhonePlaybackOwnership.localHandoff)) continue
                            current?.ytVideoId?.takeIf { it.isNotBlank() }?.let { video ->
                                kotlinx.coroutines.withTimeoutOrNull(1_500) {
                                    com.example.juke.network.AlexaBackendApi.updateQueue("current", video, emptyList(),
                                        player.isPlaying, player.currentPosition.coerceAtLeast(0), player.currentMediaItemIndex,
                                        buffering = player.playbackState == Player.STATE_BUFFERING, expectedToken = claim)
                                }
                            }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) {
                    if (e is com.example.juke.network.BackendHttpException && e.statusCode == 409 && PhonePlaybackOwnership.token == claim)
                        PhonePlaybackOwnership.queueNeedsSync.tryEmit(Unit)
                    Log.w(TAG, "Playback ownership refresh failed: ${e.javaClass.simpleName}")
                }
                if (PhonePlaybackOwnership.token.isNotBlank() &&
                    android.os.SystemClock.elapsedRealtime() >= PhonePlaybackOwnership.leaseUntilMs) {
                    // During a partition, never keep streaming beyond our exclusive lease.
                    player.pause()
                }
            }
        }

        upcomingPreloader = UpcomingAudioPreloader(playbackDataSources(applicationContext,
            StreamCacheManager.getCache(applicationContext)), serviceScope)
        preloadConnectivityJob = serviceScope.launch {
            com.example.juke.network.NetworkFeedback.online.collect { online ->
                upcomingPreloader?.update(if (online) upcomingAudioUrls(player) else emptyList())
            }
        }
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION,
                        Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED, Player.EVENT_POSITION_DISCONTINUITY)) {
                    upcomingPreloader?.update(if (com.example.juke.network.NetworkFeedback.online.value) upcomingAudioUrls(player) else emptyList())
                }
            }
        })

        // Initialize Skip Silence from Preferences
        val prefs = getSharedPreferences("audio_effects_prefs", MODE_PRIVATE)
        audioEffectController.edgeSilence.enabled = prefs.getBoolean("skip_silence_enabled", false)
        prefs.registerOnSharedPreferenceChangeListener(audioSettingsListener)
        applyOffloadPreference(prefs)

        player.addListener(callGuard)

        // Listen for audio session ID changes to attach audio effects
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioSessionIdChanged(
                eventTime: AnalyticsListener.EventTime,
                audioSessionId: Int
            ) {
                Log.d(TAG, "Audio session ID changed: $audioSessionId")
                audioEffectController.attachToAudioSession(audioSessionId)
            }
        })
        // The session id event can fire before this listener exists (or never again), which left
        // every effect unattached; attach to the current session right away.
        if (player.audioSessionId != 0) {
            audioEffectController.attachToAudioSession(player.audioSessionId)
        }

        player.addListener(playerListener)

        val sessionActivityPendingIntent = packageManager
            ?.getLaunchIntentForPackage(packageName)
            ?.let { sessionIntent ->
                // FIX: Add these flags to prevent the app from restarting
                sessionIntent.flags =
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                sessionIntent.putExtra("open_player", true)
                PendingIntent.getActivity(
                    this,
                    0,
                    sessionIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            }

        val bitmapLoader = DataSourceBitmapLoader(this)

        mediaSession = MediaLibrarySession.Builder(this, player, MediaLibrarySessionCallback())
            .apply {
                sessionActivityPendingIntent?.let { setSessionActivity(it) }
            }
            .setBitmapLoader(bitmapLoader)
            .setShowPlayButtonIfPlaybackIsSuppressed(true)
            .build()

        outputPrefs.registerOnSharedPreferenceChangeListener(outputListener)
        Log.d(TAG, "PlaybackService created")

        // Keep the notification heart in step with likes made anywhere (player, library, web).
        serviceScope.launch {
            AccountRepository.liked.collect { liked ->
                val currentId = withContext(Dispatchers.Main) { player.currentMediaItem?.mediaId } ?: return@collect
                val videoId = database.trackDao().getTrackByUuid(currentId)?.ytVideoId
                withContext(Dispatchers.Main) { updateCustomLayout(videoId != null && videoId in liked) }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Closing the task stops this phone, never the Echo.
        player.pause()
        player.stop()
        RemotePlaybackService.stop(applicationContext)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    @OptIn(UnstableApi::class)
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        if (outputPrefs.getString("playback_output", "PHONE") == "ALEXA") {
            stopForeground(STOP_FOREGROUND_REMOVE)
            getSystemService(NotificationManager::class.java).cancel(1)
            return
        }
        // Media3 owns foreground promotion, including starts from media controls.
        // Keep the session foreground while playback continues with the app hidden.
        try {
            super.onUpdateNotification(session, startInForegroundRequired)
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e is android.app.ForegroundServiceStartNotAllowedException
            ) {
                Log.w(
                    TAG,
                    "Caught ForegroundServiceStartNotAllowedException in onUpdateNotification — suppressing"
                )
            } else {
                Log.w(TAG, "Failed to update notification: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        if (activeService.get() === this) activeService.clear()
        outputPrefs.unregisterOnSharedPreferenceChangeListener(outputListener)
        // Clean up pending resume operations
        mainHandler.removeCallbacks(resumeAfterCall)

        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        audioEffectController.release()


        preloadConnectivityJob?.cancel()
        upcomingPreloader?.clear()
        // Release the stream cache
        StreamCacheManager.release()

        streamRecoveryJob?.cancel()
        super.onDestroy()
        Log.d(TAG, "PlaybackService destroyed")
    }

    override fun startForegroundService(service: Intent?): ComponentName? {
        return try {
            super.startForegroundService(service)
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e is android.app.ForegroundServiceStartNotAllowedException
            ) {
                Log.e(
                    TAG,
                    "Caught ForegroundServiceStartNotAllowedException in startForegroundService",
                    e
                )
                null
            } else {
                throw e
            }
        }
    }

    override fun startService(service: Intent?): ComponentName? {
        return try {
            super.startService(service)
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e is android.app.ForegroundServiceStartNotAllowedException
            ) {
                Log.e(TAG, "Caught ForegroundServiceStartNotAllowedException in startService", e)
                null
            } else if (e is IllegalStateException && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Log.e(TAG, "Caught IllegalStateException in startService (background app)", e)
                null
            } else {
                throw e
            }
        }
    }

    /** Notification like button for the current song. */
    private fun updateCustomLayout(isLiked: Boolean) {
        val iconResId =
            if (isLiked) R.drawable.thumb_up_filled else R.drawable.thumb_up_outline
        val button = CommandButton.Builder()
            .setDisplayName("Like")
            .setIconResId(iconResId)
            .setSessionCommand(SessionCommand(CUSTOM_COMMAND_TOGGLE_FAVORITE_ACTION_ID, Bundle.EMPTY))
            .build()
        mediaSession?.setCustomLayout(listOf(button))
    }

    /**
     * Check if the current track has reached 50% of its duration and increment play count if so.
     * Each track is only counted once per playback session.
     */
    private fun checkPlayCountThreshold() {
        val trackId = currentPlayingTrackId ?: return
        // Skip if already counted in this session
        if (trackId in tracksPlayCountedThisSession) return

        val duration = player.duration
        val position = player.currentPosition

        // Check if duration is known and position exceeds 50%
        if (duration > 0 && position > 0 && position >= duration / 2) {
            tracksPlayCountedThisSession.add(trackId)
            serviceScope.launch {
                try {
                    val now = SimpleDateFormat(
                        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                        Locale.US
                    ).format(Date())
                    database.trackDao().incrementPlayCount(trackId, now)
                    Log.d(TAG, "Play count incremented at 50% threshold for track: $trackId")
                } catch (e: Exception) {
                    Log.e(TAG, "Error incrementing play count at 50% threshold: ${e.message}", e)
                }
            }
        }
    }

    /**
     * MediaLibrarySession callback for Android Auto browsing support
     */
    private inner class MediaLibrarySessionCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands =
                MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                    .add(
                        SessionCommand(
                            CUSTOM_COMMAND_TOGGLE_FAVORITE_ACTION_ID,
                            Bundle.EMPTY
                        )
                    )
                    .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(
                    MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
                )
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == CUSTOM_COMMAND_TOGGLE_FAVORITE_ACTION_ID) {
                val currentTrackId = player.currentMediaItem?.mediaId
                if (currentTrackId != null) {
                    serviceScope.launch {
                        try {
                            val videoId = database.trackDao().getTrackByUuid(currentTrackId)?.ytVideoId
                            if (videoId != null) {
                                AccountRepository.setLiked(videoId, !AccountRepository.isLiked(videoId))
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing like command: ${e.message}")
                        }
                    }
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onPlayerCommandRequest(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            @Player.Command playerCommand: Int
        ): Int {
            rememberManualTrackChangeRequest(playerCommand)
            if (playerCommand == Player.COMMAND_PLAY_PAUSE && !player.isPlaying &&
                (player.playerError != null || player.playbackState == Player.STATE_IDLE)) {
                recoverCurrentStream(manual = true)
            }
            return super.onPlayerCommandRequest(session, controller, playerCommand)
        }

        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent
        ): Boolean {
            val keyEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
            }

            if (keyEvent?.action == KeyEvent.ACTION_DOWN) {
                when (keyEvent.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_NEXT -> {
                        rememberManualTrackChangeRequest(Player.COMMAND_SEEK_TO_NEXT)
                    }

                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                        rememberManualTrackChangeRequest(Player.COMMAND_SEEK_TO_PREVIOUS)
                    }
                }
            }

            return super.onMediaButtonEvent(session, controllerInfo, intent)
        }

        override fun onPlayerInteractionFinished(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            playerCommands: Player.Commands
        ) {
            maybeStartPlaybackAfterManualTrackChange()
            super.onPlayerInteractionFinished(session, controllerInfo, playerCommands)
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            return Futures.immediateFuture(
                LibraryResult.ofItem(
                    MediaItem.Builder()
                        .setMediaId("root")
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setIsBrowsable(true)
                                .setIsPlayable(false)
                                .setTitle("Music Box")
                                .build()
                        )
                        .build(),
                    params
                )
            )
        }

        @OptIn(UnstableApi::class)
        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            return when (parentId) {
                "root" -> {
                    // Root menu categories
                    val items = ImmutableList.of(
                        buildBrowsableItem("recent", "Recently Played")
                    )
                    Futures.immediateFuture(LibraryResult.ofItemList(items, params))
                }

                "recent" -> loadRecentTracks(params)
                else -> Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
            }
        }

        @OptIn(UnstableApi::class)
        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            return serviceScope.async {
                try {
                    val track = database.trackDao().getTrackByUuid(mediaId)?.toTrack()
                    if (track != null) {
                        LibraryResult.ofItem(buildPlayableMediaItem(track), null)
                    } else {
                        LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error getting item: ${e.message}")
                    LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
                }
            }.asListenableFuture()
        }

        private fun buildBrowsableItem(mediaId: String, title: String): MediaItem {
            return MediaItem.Builder()
                .setMediaId(mediaId)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setTitle(title)
                        .build()
                )
                .build()
        }

        private fun buildPlayableMediaItem(track: Track): MediaItem {
            val metadataBuilder = MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist)
                .setIsBrowsable(false)
                .setIsPlayable(true)

            applyArtwork(metadataBuilder, track.thumbnailUri)

            return MediaItem.Builder()
                .setMediaId(track.uuid)
                .setUri(track.localUri)
                .setMediaMetadata(metadataBuilder.build())
                .build()
        }

        @OptIn(UnstableApi::class)
        private fun loadRecentTracks(params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            return serviceScope.async {
                try {
                    val tracks = database.trackDao().getRecentlyPlayed(20)
                    val items = tracks.map { buildPlayableMediaItem(it.toTrack()) }
                    LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading recent tracks: ${e.message}")
                    LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
                }
            }.asListenableFuture()
        }

        /**
         * Bridges coroutine-based library queries to the Media3 callback API.
         *
         * MediaLibrarySession callbacks are expected to return `ListenableFuture`, while the
         * implementation uses coroutines internally.
         */
        @kotlin.OptIn(ExperimentalCoroutinesApi::class)
        private fun <T> kotlinx.coroutines.Deferred<T>.asListenableFuture(): ListenableFuture<T> {
            val deferred = this
            return com.google.common.util.concurrent.SettableFuture.create<T>().apply {
                deferred.invokeOnCompletion { exception ->
                    if (exception != null) {
                        setException(exception)
                    } else {
                        set(deferred.getCompleted())
                    }
                }
            }
        }
    }
}

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
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var playerListener: Player.Listener? = null
    private val _isPlaying = MutableStateFlow(false)
    val isPlayingFlow: StateFlow<Boolean> = _isPlaying.asStateFlow()
    private val _isBuffering = MutableStateFlow(false)
    val isBufferingFlow: StateFlow<Boolean> = _isBuffering.asStateFlow()
    private val database: MusicDatabase = MusicDatabase.getDatabase(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _snapshot = MutableStateFlow(PhonePlaybackSnapshot())
    val snapshot: StateFlow<PhonePlaybackSnapshot> = _snapshot.asStateFlow()
    private var pendingQueueAction: ((MediaController) -> Unit)? = null
    private var userQueueRequested = false
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

        PlaybackService.applyArtwork(metadataBuilder, track.thumbnailUri)

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
            controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
            controllerFuture?.addListener(
                {
                    controller = controllerFuture?.get()
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
                    scope.launch {
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
                MoreExecutors.directExecutor()
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
            setMediaItems(mediaItems, startIndex, startPositionMs)
            prepare()
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
    suspend fun awaitReady() {
        kotlinx.coroutines.withTimeout(30_000) {
            while (true) {
                val ready = controller
                ready?.playerError?.let { throw it }
                if (ready?.playbackState == Player.STATE_READY) return@withTimeout
                kotlinx.coroutines.delay(50)
            }
        }
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
        cancelSleepTimer()
        savePlaybackState() // Save state before releasing
        MediaController.releaseFuture(controllerFuture ?: return)
        // remove player listener if attached
        try {
            playerListener?.let { controller?.removeListener(it) }
        } catch (_: Exception) {
        }
        playerListener = null
        controller = null
        controllerFuture = null
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
