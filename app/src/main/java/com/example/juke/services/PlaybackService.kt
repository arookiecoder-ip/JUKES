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

        /** A silent default must not publish an old local player's queue over the shared queue. */
        internal suspend fun discardIdlePhoneQueue() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
            pausePhoneForHandoff()
            activeService.get()?.player?.clearMediaItems()
        }

        /** Pause the actual service player before acknowledging an app-initiated handoff. */
        internal suspend fun pausePhoneForHandoff(fallbackPlaying: Boolean = false, fallbackPosition: Long = 0) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
            // Android may have destroyed an idle service. No live service means no phone audio to pause.
            val service = activeService.get() ?: return@withContext (fallbackPlaying to fallbackPosition.coerceAtLeast(0))
            val snapshot = (service.player.playWhenReady || service.leaseInterruption.matches(
                service.player.currentMediaItem?.mediaId, PhonePlaybackOwnership.token) || service.recoveryShouldResume) to service.player.currentPosition
            service.leaseInterruption.clear()
            service.networkInterruption.clear()
            service.recoveryShouldResume = false
            service.streamRecoveryJob?.cancel()
            service.streamRecoveryJob = null
            service.player.pause()
            check(!service.player.playWhenReady && !service.player.isPlaying) { "Phone playback has not paused." }
            snapshot
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
        internal fun applyArtwork(metadataBuilder: MediaMetadata.Builder, thumbnailUri: String?, videoId: String? = null) {
            (thumbnailUri?.takeIf { it.isNotEmpty() } ?: com.example.juke.network.artworkCandidates("", videoId, true).firstOrNull())?.takeIf { it.isNotEmpty() }?.let { uriString ->
                try {
                    val artworkUri = when {
                        uriString.startsWith("http", ignoreCase = true) -> com.example.juke.network.largeArtworkUrl(uriString).toUri()
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

                    artworkUri?.let { com.example.juke.network.ArtworkRepository.register(it.toString(), videoId); metadataBuilder.setArtworkUri(it) }
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
                    val cacheMb = context.getSharedPreferences("music_settings_prefs", Context.MODE_PRIVATE).getInt("stream_cache_mb", 256).coerceIn(128, 1024)
                    val evictor = LeastRecentlyUsedCacheEvictor(cacheMb.toLong() * 1024 * 1024)
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
    private lateinit var silentWatchdog: SilentPlaybackWatchdog

    /**
     * A flat Visualizer waveform is diagnostic only: quiet intros and offloaded
     * audio can both return silence while playback is healthy. Restarting here
     * caused an audible interruption and cursor jump. Real player errors and
     * network/lease interruptions retain their separate recovery paths.
     */
    private fun onSilentPlayback(trackId: String, firstStrike: Boolean) {
        if (!::player.isInitialized || player.currentMediaItem?.mediaId != trackId || !player.isPlaying) return
        Log.d(TAG, "Flat waveform for $trackId (first=$firstStrike); retaining uninterrupted playback")
    }
    private val leaseInterruption = PlaybackInterruption()
    private val networkInterruption = PlaybackInterruption()
    private var pausingForLease = false
    private val streamRetry = BackgroundRetry()
    private var backgroundMaintenance: Job? = null

    private fun pauseForExpiredLease() {
        leaseInterruption.remember(player.currentMediaItem?.mediaId, PhonePlaybackOwnership.token,
            player.playWhenReady || recoveryShouldResume)
        pausingForLease = true
        try { player.pause() } finally { pausingForLease = false }
    }

    private fun recoverAfterLeaseRenewal() {
        if (!leaseInterruption.canResume(player.currentMediaItem?.mediaId, PhonePlaybackOwnership.token,
                PhonePlaybackOwnership.permitsPlayback(), PhonePlaybackOwnership.localHandoff, inCall()) ||
            outputPrefs.getString("playback_output", "PHONE") != "PHONE") return
        leaseInterruption.clear()
        recoveryShouldResume = true
        if (player.playerError != null || player.playbackState == Player.STATE_IDLE) recoverCurrentStream(manual = true)
        else player.play()
        recoverAfterNetworkReconnect()
    }

    private fun recoverAfterNetworkReconnect() {
        if (!com.example.juke.network.NetworkFeedback.online.value ||
            !networkInterruption.canResume(player.currentMediaItem?.mediaId, PhonePlaybackOwnership.token,
                PhonePlaybackOwnership.permitsPlayback(), PhonePlaybackOwnership.localHandoff, inCall()) ||
            outputPrefs.getString("playback_output", "PHONE") != "PHONE") return
        // Reopen the broken socket now, rather than waiting for the 180-second audio read timeout.
        // Cached spans and the current cursor survive the source rebuild.
        networkInterruption.clear()
        recoverCurrentStream(manual = true)
    }
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
        val allowed = prefs.getBoolean("battery_saver_playback", true) &&
            !prefs.getBoolean("skip_silence_enabled", false) &&
            !prefs.getBoolean("booster_enabled", false) &&
            !prefs.getBoolean("normalization_enabled", false)
        val offload = TrackSelectionParameters.AudioOffloadPreferences.Builder()
            .setAudioOffloadMode(
                if (allowed) TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
                else TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
            )
            .setIsGaplessSupportRequired(false)
            .setIsSpeedChangeSupportRequired(false)
            .build()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setAudioOffloadPreferences(offload)
            .build()
        Log.d(TAG, "Audio offload ${if (allowed) "enabled" else "disabled"}")
    }

    private var upcomingPreloader: UpcomingAudioPreloader? = null
    private var preloadConnectivityJob: Job? = null
    private val preloadNetworkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: android.net.Network, capabilities: android.net.NetworkCapabilities) {
            serviceScope.launch { updatePreloader() }
        }
        override fun onLost(network: android.net.Network) { serviceScope.launch { updatePreloader() } }
    }
    private fun updatePreloader() {
        if (!::player.isInitialized) return
        val connected = getSystemService(android.net.ConnectivityManager::class.java).activeNetwork != null &&
            com.example.juke.network.NetworkFeedback.online.value
        upcomingPreloader?.update(if (connected && player.playbackState == Player.STATE_READY && player.playWhenReady && !PhonePlaybackOwnership.localHandoff)
            upcomingAudioUrls(player) else emptyList(), player.playbackState == Player.STATE_BUFFERING)
    }

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
        if (key == "prefetch_mobile_data") updatePreloader()
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

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main +
        kotlinx.coroutines.CoroutineExceptionHandler { _, error ->
            Log.e(TAG, "Background playback task failed", error)
            com.example.juke.network.NetworkFeedback.notify("Playback task interrupted. Retry playback when connected.")
        })

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "com.example.juke.CANCEL_RECOVERY") {
            leaseInterruption.clear()
            networkInterruption.clear()
            recoveryShouldResume = false
            streamRecoveryJob?.cancel()
            player.pause()
            mediaSession?.let { onUpdateNotification(it, false) }
            return START_NOT_STICKY
        }
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

        applyArtwork(metadataBuilder, track.thumbnailUri, track.ytVideoId)

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
        val attempt = if (manual) 1 else ((recoveryAttempts[trackId] ?: 0) + 1).coerceAtMost(5)
        if (!manual && !streamRetry.ready(android.os.SystemClock.elapsedRealtime())) return
        recoveryAttempts[trackId] = attempt
        val claim = PhonePlaybackOwnership.token
        val httpFailure = generateSequence<Throwable>(player.playerError) { it.cause }
            .filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>().firstOrNull()
        if (httpFailure?.responseCode == 401) com.example.juke.network.AudioCredentials.clear()
        com.example.juke.services.PlaybackDiagnostics.record(com.example.juke.services.PlaybackDiagnostics.Stage.RECOVERY, 0, true)
        val retryAfter = httpFailure?.headerFields?.entries?.firstOrNull { it.key.equals("Retry-After", true) }?.value?.firstOrNull()
        val retryDelay = com.example.juke.network.audioRetryDelay(httpFailure?.responseCode, retryAfter, attempt)
        streamRetry.failed(android.os.SystemClock.elapsedRealtime(), retryDelay)
        val position = player.currentPosition.coerceAtLeast(0)
        recoveryShouldResume = manual || player.playWhenReady || recoveryShouldResume
        streamRecoveryJob = serviceScope.launch {
            try {
                val track = withContext(Dispatchers.IO) { database.trackDao().getTrackByUuid(trackId)?.toTrack() }
                    ?: return@launch
                DownloadRepository.get(applicationContext).awaitReady()
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
                    // Keep valid cached spans; a network interruption does not invalidate audio bytes.
                }
                if (player.currentMediaItem?.mediaId != trackId || !PhonePlaybackOwnership.permitsPlayback(claim) ||
                    outputPrefs.getString("playback_output", "PHONE") != "PHONE") return@launch
                val index = player.currentMediaItemIndex
                val resume = recoveryShouldResume
                // Media3 treats replacing an item with the same URI as a metadata update.
                // Stop first so prepare actually discards the broken loader/socket.
                player.stop()
                player.replaceMediaItem(index, original.buildUpon().setUri(refreshed.localUri!!).build())
                player.seekTo(index, position)
                player.prepare()
                player.playWhenReady = resume && !inCall()
                if (resume && inCall()) mainHandler.postDelayed(resumeAfterCall, 500)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                streamRetry.failed(android.os.SystemClock.elapsedRealtime(), if (httpFailure?.responseCode == 429) retryDelay else 0)
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
                pauseForExpiredLease()
                return
            }
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST && !pausingForLease) {
                leaseInterruption.clear()
                networkInterruption.clear()
                recoveryShouldResume = false
                streamRecoveryJob?.cancel()
                streamRecoveryJob = null
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Prevent infinite loops from metadata updates
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED &&
                mediaItem?.mediaId == currentPlayingTrackId) {
                // Metadata/queue backfills preserve the current recovery intent. Real replacements
                // and queue removal must pass through the cancellation/reset below.
                // Clear the set of counted tracks when queue changes to ensure fresh counting
                tracksPlayCountedThisSession.clear()
                return
            }

            streamRecoveryJob?.cancel()
            streamRecoveryJob = null
            recoveryAttempts.clear()
            if (::silentWatchdog.isInitialized && player.isPlaying) silentWatchdog.notePlaying()
            streamRetry.reset()
            leaseInterruption.clear()
            networkInterruption.clear()
            recoveryShouldResume = false
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
                recoveryShouldResume = false
                streamRetry.reset()
                recoveryAttempts.clear()
                reportDeviceListen()
                progressHandler.post(progressRunnable)
                if (::silentWatchdog.isInitialized) silentWatchdog.notePlaying()
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
        setListener(object : androidx.media3.session.MediaSessionService.Listener {
            override fun onForegroundServiceStartNotAllowedException() { onPhoneForegroundDenied() }
        })

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
        setMediaNotificationProvider(GuardedMediaNotificationProvider(notificationProvider,
            { activeService.get() === this && outputPrefs.getString("playback_output", "PHONE") == "PHONE" },
            ::onPhoneForegroundDenied, ::holdRecoveryNotification))

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
            .setMediaCodecSelector { mime, secure, tunneling ->
                androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT
                    .getDecoderInfos(mime, secure, tunneling).sortedBy { !it.hardwareAccelerated }
            }

        val transferListener = object : androidx.media3.datasource.TransferListener {
            private val starts = java.util.concurrent.ConcurrentHashMap<androidx.media3.datasource.DataSource, Long>()
            override fun onTransferInitializing(source: androidx.media3.datasource.DataSource, spec: androidx.media3.datasource.DataSpec, network: Boolean) {
                starts[source] = android.os.SystemClock.elapsedRealtime()
            }
            override fun onTransferStart(source: androidx.media3.datasource.DataSource, spec: androidx.media3.datasource.DataSpec, network: Boolean) {
                val start = starts[source] ?: return
                PlaybackDiagnostics.record(PlaybackDiagnostics.Stage.AUDIO_CONNECT, android.os.SystemClock.elapsedRealtime() - start)
                Log.d(TAG, "Stream HTTP connected in ${android.os.SystemClock.elapsedRealtime() - start}ms (${spec.uri.host})")
            }
            override fun onBytesTransferred(source: androidx.media3.datasource.DataSource, spec: androidx.media3.datasource.DataSpec, network: Boolean, bytes: Int) {
                val start = starts.remove(source) ?: return
                PlaybackDiagnostics.record(PlaybackDiagnostics.Stage.FIRST_BYTE, android.os.SystemClock.elapsedRealtime() - start)
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
        PhonePlaybackSynchronizer(applicationContext, serviceScope, player,
            trackById = { database.trackDao().getTrackByUuid(it)?.toTrack() },
            extendQueue = { maybeExtendPhoneQueue() }, pauseForLease = ::pauseForExpiredLease,
            leaseRenewed = ::recoverAfterLeaseRenewal,
            recoveryPending = { leaseInterruption.pending || networkInterruption.pending || recoveryShouldResume },
            pauseForHandoff = { pausePhoneForHandoff() }).start()

        silentWatchdog = SilentPlaybackWatchdog(
            scope = serviceScope,
            audioManager = audioManager,
            player = player,
            eligible = {
                outputPrefs.getString("playback_output", "PHONE") == "PHONE" &&
                    !inCall() &&
                    audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) > 0
            },
            onSilent = { trackId, firstStrike -> onSilentPlayback(trackId, firstStrike) }
        )

        upcomingPreloader = UpcomingAudioPreloader(playbackDataSources(applicationContext,
            StreamCacheManager.getCache(applicationContext), prefetch = true), serviceScope, applicationContext)
        getSystemService(android.net.ConnectivityManager::class.java).registerDefaultNetworkCallback(preloadNetworkCallback)
        preloadConnectivityJob = serviceScope.launch {
            com.example.juke.network.NetworkFeedback.online.collect { online ->
                try {
                    if (!online && player.currentMediaItem?.localConfiguration?.uri?.scheme in setOf("http", "https")) {
                        networkInterruption.remember(player.currentMediaItem?.mediaId, PhonePlaybackOwnership.token,
                            player.playWhenReady || recoveryShouldResume || leaseInterruption.pending || PhonePlaybackOwnership.remoteControlled)
                    }
                    if (online) { streamRetry.reset(); recoverAfterNetworkReconnect() }
                    updatePreloader()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { Log.e(TAG, "Connectivity recovery failed", e) }
            }
        }
        backgroundMaintenance = serviceScope.launch {
            while (isActive) {
                delay(5_000)
                try {
                    updatePreloader() // Retry failed warming even without a new UI/player event.
                    recoverAfterLeaseRenewal()
                    recoverAfterNetworkReconnect()
                    if (recoveryShouldResume && player.playerError != null &&
                        com.example.juke.network.NetworkFeedback.online.value &&
                        PhonePlaybackOwnership.permitsPlayback() && !PhonePlaybackOwnership.localHandoff && !inCall()) {
                        recoverCurrentStream()
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { Log.w(TAG, "Background playback maintenance failed: ${e.javaClass.simpleName}") }
            }
        }
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION,
                        Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED, Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_PLAY_WHEN_READY_CHANGED)) {
                    updatePreloader()
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

        val bitmapLoader = SharedArtworkBitmapLoader(this, serviceScope)

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
                try {
                    val currentId = player.currentMediaItem?.mediaId ?: return@collect
                    val videoId = database.trackDao().getTrackByUuid(currentId)?.ytVideoId
                    updateCustomLayout(videoId != null && videoId in liked)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { Log.w(TAG, "Notification like refresh failed: ${e.javaClass.simpleName}") }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        MobileDeviceConnection.stop()
        PhonePlaybackOwnership.forget()
        // Closing the task stops this phone, never the Echo.
        player.pause()
        player.stop()
        RemotePlaybackService.dismiss(applicationContext)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    @OptIn(UnstableApi::class)
    private fun holdRecoveryNotification(): Boolean {
        if (!::player.isInitialized || !retainRecoveryNotification(
                outputPrefs.getString("playback_output", "PHONE") == "PHONE", isPlaybackOngoing,
                player.isPlaying, leaseInterruption.pending, networkInterruption.pending,
                recoveryShouldResume, PhonePlaybackOwnership.localHandoff)) return false
        val session = mediaSession ?: return false
        val open = PendingIntent.getActivity(this, 0, Intent(this, com.example.juke.MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel = PendingIntent.getService(this, 91, Intent(this, PlaybackService::class.java)
            .setAction("com.example.juke.CANCEL_RECOVERY"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = androidx.core.app.NotificationCompat.Builder(this, "media_playback")
            .setSmallIcon(R.drawable.media3_notification_small_icon)
            .setContentTitle(player.mediaMetadata.title ?: "Music Box")
            .setContentText("Reconnecting… Playback will resume when available.")
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Cancel recovery", cancel)
            .setStyle(androidx.media3.session.MediaStyleNotificationHelper.MediaStyle(session)
                .setShowActionsInCompactView(0)).build()
        // Retain a service already promoted during real playback. Never use an
        // outage to create a new, unauthorized background playback service.
        startForeground(1, notification)
        return true
    }

    @OptIn(UnstableApi::class)
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        if (outputPrefs.getString("playback_output", "PHONE") != "PHONE") {
            stopForeground(STOP_FOREGROUND_REMOVE)
            getSystemService(NotificationManager::class.java).cancel(1)
            return
        }
        // Media3 owns foreground promotion, including starts from media controls.
        // Keep the session foreground while playback continues with the app hidden.
        try {
            if (holdRecoveryNotification()) return
            super.onUpdateNotification(session, startInForegroundRequired ||
                recoveryShouldResume || leaseInterruption.pending)
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e is android.app.ForegroundServiceStartNotAllowedException
            ) {
                onPhoneForegroundDenied()
            } else {
                Log.w(TAG, "Failed to update notification: ${e.message}")
            }
        }
    }

    private fun onPhoneForegroundDenied() {
        // Android did not grant a background playback start. Do not keep audio running
        // without its required foreground service or transfer it to another phone.
        recoveryShouldResume = false
        leaseInterruption.clear()
        networkInterruption.clear()
        streamRecoveryJob?.cancel()
        streamRecoveryJob = null
        if (::player.isInitialized) player.pause()
        com.example.juke.network.NetworkFeedback.notify("Open Music Box on the playback device, then tap play to resume.")
        Log.w(TAG, "Android denied foreground playback promotion")
    }

    override fun onDestroy() {
        if (activeService.get() === this) activeService.clear()
        outputPrefs.unregisterOnSharedPreferenceChangeListener(outputListener)
        runCatching { getSystemService(android.net.ConnectivityManager::class.java).unregisterNetworkCallback(preloadNetworkCallback) }
        upcomingPreloader?.clear()
        serviceScope.coroutineContext[Job]?.cancel()
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
        // The process owns the stream cache; rebuilt services reuse its valid spans.

        streamRecoveryJob?.cancel()
        backgroundMaintenance?.cancel()
        getSharedPreferences("audio_effects_prefs", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(audioSettingsListener)
        progressHandler.removeCallbacksAndMessages(null)
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
            if (playerCommand == Player.COMMAND_PLAY_PAUSE) {
                leaseInterruption.clear()
                networkInterruption.clear()
            }
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

            applyArtwork(metadataBuilder, track.thumbnailUri, track.ytVideoId)

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
