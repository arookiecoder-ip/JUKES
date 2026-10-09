package `in`.synthora.musicbox.services

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
import `in`.synthora.musicbox.utils.SafeLog as Log
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
import `in`.synthora.musicbox.R
import `in`.synthora.musicbox.analytics.AnalyticsManager
import `in`.synthora.musicbox.database.MusicDatabase
import `in`.synthora.musicbox.network.toAppTrack
import `in`.synthora.musicbox.database.toEntity
import `in`.synthora.musicbox.database.toTrack
import `in`.synthora.musicbox.models.Track
import `in`.synthora.musicbox.network.AlexaBackendApi
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.SettableFuture
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
        internal fun canResumeAvailablePhoneAudio(): Boolean {
            val service = activeService.get() ?: return false
            return service.outputPrefs.getString("playback_output", "PHONE") == "PHONE" &&
                service.player.currentMediaItem != null && service.canContinueCurrentAudioOffline()
        }
        val recoveryLoading = kotlinx.coroutines.flow.MutableStateFlow(false)
        internal fun resetExplicitStreamRetry() {
            val service = activeService.get() ?: return
            service.malformedAudioRetried.clear()
            service.recoveryAttempts.clear()
            service.streamRetry.reset()
        }
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
            service.cancelSessionResume()
            service.leaseInterruption.clear()
            service.networkInterruption.clear()
            service.recoveryShouldResume = false
            service.streamRecoveryJob?.cancel()
            service.streamRecoveryJob = null
            service.player.pause()
            check(!service.player.playWhenReady && !service.player.isPlaying) { "Phone playback has not paused." }
            snapshot
        }

        /** Share the existing remote foreground notification while the app is eligible.
         * This preserves the playback service across a later background phone handoff. */
        internal fun joinRemoteForeground(notification: android.app.Notification) {
            val service = activeService.get() ?: return
            val visible = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState
                .isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
            if (!canJoinRemoteForeground(visible, service.isPlaybackOngoing, service.remoteForegroundBridge,
                    service.outputPrefs.getString("playback_output", "PHONE") == "PHONE")) return
            try {
                service.startForeground(1002, notification)
                service.remoteForegroundBridge = true
            } catch (e: Exception) {
                Log.w(service.TAG, "Remote foreground bridge unavailable: ${e.javaClass.simpleName}")
            }
        }

        internal fun leaveRemoteForeground(force: Boolean = false) {
            val service = activeService.get() ?: return
            if (!service.remoteForegroundBridge) return
            if (!force && (service.outputPrefs.getString("playback_output", "PHONE") == "PHONE" ||
                    destinationHandoffWaiting(MobileDeviceConnection.output.value, PhonePlaybackOwnership.ownerId,
                        PhonePlaybackOwnership.token, PhonePlaybackOwnership.localHandoff))) return
            service.remoteForegroundBridge = false
            // The remote service owns this shared notification; do not remove its controls.
            service.stopForeground(android.app.Service.STOP_FOREGROUND_DETACH)
        }

        internal fun finishSongPreparation() {
            val service = activeService.get() ?: return
            if (PhonePlaybackOwnership.localHandoff) return
            service.mediaSession?.let { service.onUpdateNotification(it, service.player.isPlaying) }
        }

        internal fun finishPhoneNotificationHandoff() {
            val service = activeService.get() ?: return
            if (!service.remoteForegroundBridge) return
            service.remoteForegroundBridge = false
            service.mediaSession?.let { service.onUpdateNotification(it, service.player.isPlaying) }
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
            (thumbnailUri?.takeIf { it.isNotEmpty() } ?: `in`.synthora.musicbox.network.artworkCandidates("", videoId, true).firstOrNull())?.takeIf { it.isNotEmpty() }?.let { uriString ->
                try {
                    val artworkUri = when {
                        uriString.startsWith("http", ignoreCase = true) -> `in`.synthora.musicbox.network.largeArtworkUrl(uriString).toUri()
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

                    artworkUri?.let { `in`.synthora.musicbox.network.ArtworkRepository.register(it.toString(), videoId); metadataBuilder.setArtworkUri(it) }
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
        private var playingEvictor: PlayingAudioCacheEvictor? = null
        fun protectPlaying(key: String?) { playingEvictor?.playingKey = key }

        fun existingCache(): SimpleCache? = cache

        fun getCache(context: Context): SimpleCache {
            return cache ?: synchronized(this) {
                cache ?: run {
                    val cacheDir = java.io.File(context.cacheDir, "stream_cache")
                    val cacheMb = context.getSharedPreferences("music_settings_prefs", Context.MODE_PRIVATE).getInt("stream_cache_mb", 256).coerceIn(128, 1024)
                    val evictor = PlayingAudioCacheEvictor(cacheMb.toLong() * 1024 * 1024).also { playingEvictor = it }
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
                cache?.let { saved ->
                    saved.removeResource(uri)
                    val metadata = androidx.media3.datasource.cache.ContentMetadataMutations()
                    androidx.media3.datasource.cache.ContentMetadataMutations.setContentLength(metadata, -1)
                    androidx.media3.datasource.cache.ContentMetadataMutations.setRedirectedUri(metadata, null)
                    saved.applyContentMetadataMutations(uri, metadata)
                }
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
    private var remoteForegroundBridge = false
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
    private val offlineReconciliation = OfflineReconciliationWindow()
    private var pausingForLease = false
    private val streamRetry = BackgroundRetry()
    private val bufferingStall = BufferingStall()
    private var backgroundMaintenance: Job? = null

    private fun currentStreamFullyCached(): Boolean {
        val config = player.currentMediaItem?.localConfiguration
        return config?.takeIf { it.uri.scheme in setOf("http", "https") }?.let {
            val cache = StreamCacheManager.existingCache() ?: return@let false
            val key = it.customCacheKey ?: it.uri.toString()
            runCatching {
                val length = androidx.media3.datasource.cache.ContentMetadata.getContentLength(cache.getContentMetadata(key))
                length > 0 && cache.isCached(key, 0, length)
            }.getOrDefault(false)
        } ?: false
    }

    private fun canContinueCurrentAudioOffline(): Boolean {
        if (!PhonePlaybackOwnership.permitsLocalContinuation()) return false
        val config = player.currentMediaItem?.localConfiguration
        return canContinueCachedOffline(!(PhonePlaybackOwnership.serverUnavailable || PhonePlaybackOwnership.checkingServer || offlineReconciliation.active(
            `in`.synthora.musicbox.network.NetworkFeedback.online.value, android.os.SystemClock.elapsedRealtime())),
            PhonePlaybackOwnership.remoteControlled, config?.uri?.scheme in setOf("file", "content"),
            currentStreamFullyCached(), PhonePlaybackOwnership.localHandoff,
            player.playerError == null && player.bufferedPosition > player.currentPosition)
    }

    private fun pauseForExpiredLease() {
        if (canContinueCurrentAudioOffline()) return
        leaseInterruption.remember(player.currentMediaItem?.mediaId, PhonePlaybackOwnership.token,
            player.playWhenReady || recoveryShouldResume)
        Log.w(TAG, "Playback held: ownership lease expired; networkOnline=${`in`.synthora.musicbox.network.NetworkFeedback.online.value}; serverUnavailable=${PhonePlaybackOwnership.serverUnavailable}; position=${player.currentPosition}; buffered=${player.bufferedPosition}; cached=${currentStreamFullyCached()}")
        pausingForLease = true
        try { player.pause() } finally { pausingForLease = false }
    }

    private fun recoverAfterLeaseRenewal() {
        if (!leaseInterruption.canResume(player.currentMediaItem?.mediaId, PhonePlaybackOwnership.token,
                PhonePlaybackOwnership.permitsPlayback() || canContinueCurrentAudioOffline(), PhonePlaybackOwnership.localHandoff, inCall()) ||
            outputPrefs.getString("playback_output", "PHONE") != "PHONE") return
        leaseInterruption.clear()
        recoveryShouldResume = true
        if (player.playerError != null || player.playbackState == Player.STATE_IDLE) recoverCurrentStream(manual = true)
        else player.play()
        recoverAfterNetworkReconnect()
    }

    private fun recoverAfterNetworkReconnect() {
        if (!`in`.synthora.musicbox.network.NetworkFeedback.online.value ||
            !networkInterruption.canResume(player.currentMediaItem?.mediaId, PhonePlaybackOwnership.token,
                PhonePlaybackOwnership.permitsPlayback(), PhonePlaybackOwnership.localHandoff, inCall()) ||
            outputPrefs.getString("playback_output", "PHONE") != "PHONE") return
        // Reopen the broken socket now, rather than waiting for the 180-second audio read timeout.
        // Cached spans and the current cursor survive the source rebuild.
        networkInterruption.clear()
        if (needsNetworkSourceRecovery(player.playerError != null, player.playbackState == Player.STATE_IDLE,
                player.playbackState == Player.STATE_BUFFERING, player.currentPosition, player.bufferedPosition)) {
            recoverCurrentStream(manual = true)
        }
    }
    private val malformedAudioRetried = mutableSetOf<String>()
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
        StreamCacheManager.protectPlaying(player.currentMediaItem?.localConfiguration?.let { it.customCacheKey ?: it.uri.toString() })
        val connected = getSystemService(android.net.ConnectivityManager::class.java).activeNetwork != null &&
            `in`.synthora.musicbox.network.NetworkFeedback.online.value
        val warming = connected && player.playbackState in setOf(Player.STATE_READY, Player.STATE_BUFFERING) && player.playWhenReady && !PhonePlaybackOwnership.localHandoff
        upcomingPreloader?.update(if (warming) upcomingAudioUrls(player) else emptyList(),
            player.playbackState == Player.STATE_BUFFERING,
            if (warming) player.currentMediaItem?.localConfiguration?.uri?.toString() else null)
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
            else if (outputPrefs.getString("playback_output", "PHONE") == "PHONE" &&
                (PhonePlaybackOwnership.permitsPlayback() || canContinueCurrentAudioOffline())) {
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
        if (key == "playback_output") {
            cancelSessionResume()
            resumeQueuePublicationPending = false
            resumeQueuePublishJob?.cancel()
        }
        if (key == "playback_output" && ::player.isInitialized) {
            if (outputPrefs.getString(key, "PHONE") == "ALEXA") {
                mainHandler.removeCallbacks(resumeAfterCall)
                player.pause()
                if (!remoteForegroundBridge) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    getSystemService(NotificationManager::class.java).cancel(1)
                }
            } else {
                if (player.isPlaying) reportDeviceListen()
                mediaSession?.let { onUpdateNotification(it, player.isPlaying) }
            }
        }
    }

    private val resumePrefs by lazy { getSharedPreferences("playback_state_prefs", MODE_PRIVATE) }
    private val resumeCommands = ResumeCommandFence()
    private var resumeFuture: SettableFuture<MediaSession.MediaItemsWithStartPosition>? = null
    private var restoreSessionJob: Job? = null
    private var resumeSessionJob: Job? = null
    private var cancelledMedia3Resume = false
    private var resumptionForegroundStarted = false
    private var lastSavedResumeQueue = ""
    private var resumeQueuePublicationPending = false
    private var resumeQueuePublishJob: Job? = null
    private var resumeQueueRetryAt = 0L
    private val noisyDuringResume = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY &&
                (resumeCommands.wantsPlay && !player.isPlaying)) {
                cancelSessionResume()
                player.pause()
            }
        }
    }

    /** Persist on player events and every five seconds, even with no Activity/controller. */
    private fun saveResumptionState(queueChanged: Boolean = false) {
        if (!::player.isInitialized || player.mediaItemCount == 0 ||
            outputPrefs.getString("playback_output", "PHONE") != "PHONE") return
        val item = player.currentMediaItem ?: return
        val ids = if (queueChanged || lastSavedResumeQueue.isEmpty())
            (0 until player.mediaItemCount).joinToString(",") { player.getMediaItemAt(it).mediaId }
            else lastSavedResumeQueue
        val edit = resumePrefs.edit().putBoolean("has_saved_state", true)
            .putInt("queue_start_index", player.currentMediaItemIndex)
            .putLong("playback_position", player.currentPosition.coerceAtLeast(0))
            .putInt("resume_repeat_mode", player.repeatMode)
            .putString("resume_title", item.mediaMetadata.title?.toString())
            .putString("resume_artist", item.mediaMetadata.artist?.toString())
            .putString("resume_uri", item.localConfiguration?.uri?.toString())
            .putString("resume_cache_key", item.localConfiguration?.customCacheKey)
            .putString("resume_current_id", item.mediaId)
        if (ids != lastSavedResumeQueue) { edit.putString("queue_track_ids", ids); lastSavedResumeQueue = ids }
        edit.apply()
    }

    private fun cancelSessionResume() {
        resumeCommands.pause()
        mainHandler.removeCallbacks(resumeAfterCall)
        resumeSessionJob?.cancel()
        resumeSessionJob = null
        val pending = resumeFuture
        if (pending != null && !pending.isDone) {
            // Media3 1.5 calls play even when this future fails. The forwarding
            // player below must reject that obsolete automatic Play as well.
            cancelledMedia3Resume = true
            pending.setException(CancellationException("Playback resumption superseded"))
        }
        restoreSessionJob?.cancel()
        restoreSessionJob = null
        recoveryLoading.value = false
    }

    private fun fullyCachedSource(uri: String, key: String? = null): Boolean = runCatching {
        val cache = StreamCacheManager.getCache(applicationContext) ?: return@runCatching false
        val cacheKey = key ?: uri
        val length = androidx.media3.datasource.cache.ContentMetadata.getContentLength(cache.getContentMetadata(cacheKey))
        length > 0 && cache.isCached(cacheKey, 0, length)
    }.getOrDefault(false)

    /** Metadata and sources are restored independently of the screen-owned controller. */
    private fun restoreSessionPlaylist(): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        resumeFuture?.takeIf { !it.isDone }?.let { return it }
        cancelledMedia3Resume = false
        val request = resumeCommands.play()
        val expectedOutput = outputPrefs.getString("playback_output", "PHONE")
        val result = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        resumeFuture = result
        recoveryLoading.value = true
        restoreSessionJob = serviceScope.launch {
            try {
                check(expectedOutput == "PHONE") { "Playback is selected on another device. Open Music Box to select this phone." }
                check(resumePrefs.getBoolean("has_saved_state", false)) { "Choose a song in Music Box to resume playback." }
                val ids = resumePrefs.getString("queue_track_ids", "").orEmpty().split(",").filter(String::isNotBlank)
                check(ids.isNotEmpty()) { "Choose a song in Music Box to resume playback." }
                val savedIndex = resumePrefs.getInt("queue_start_index", 0)
                val position = resumePrefs.getLong("playback_position", 0)
                val savedUri = resumePrefs.getString("resume_uri", null)
                val savedKey = resumePrefs.getString("resume_cache_key", null)
                val savedId = resumePrefs.getString("resume_current_id", null)
                val downloads = DownloadRepository.get(applicationContext)
                downloads.awaitReady()
                val entries = withContext(Dispatchers.IO) {
                    val stored = ids.distinct().chunked(500).flatMap { database.trackDao().getTracksByUuids(it) }
                        .associateBy { it.uuid }
                    ids.mapIndexedNotNull { index, id ->
                        val track = stored[id]?.toTrack() ?: return@mapIndexedNotNull null
                        val local = downloads.localTrack(track)
                        val cached = index == savedIndex && id == savedId && savedUri != null && fullyCachedSource(savedUri, savedKey)
                        val source = local ?: if (cached) track.copy(localUri = savedUri, isStream = true)
                            else if (!track.ytVideoId.isNullOrBlank() && AlexaBackendApi.isConfigured())
                                track.copy(localUri = AlexaBackendApi.audioUrl(track.ytVideoId!!), isStream = true)
                            else track
                        createValidatedMediaItem(source)?.let { item ->
                            Triple(index, track, if (cached && local == null) item.buildUpon().setCustomCacheKey(savedKey).build() else item)
                        }
                    }
                }
                val selected = entries.firstOrNull { it.first == savedIndex } ?: entries.firstOrNull()
                val repeat = resumePrefs.getInt("resume_repeat_mode", Player.REPEAT_MODE_OFF)
                val cursor = resumeCursor(savedIndex, position, entries.map { it.first },
                    (selected?.second?.durationSec ?: 0) * 1000L,
                    repeat == Player.REPEAT_MODE_ONE, repeat == Player.REPEAT_MODE_ALL)
                check(resumeCommands.current(request) && outputPrefs.getString("playback_output", "PHONE") == expectedOutput &&
                    player.mediaItemCount == 0) { "Playback changed while restoring the previous song." }
                player.repeatMode = repeat.takeIf { it in Player.REPEAT_MODE_OFF..Player.REPEAT_MODE_ALL } ?: Player.REPEAT_MODE_OFF
                Log.d(TAG, "Restored notification playlist: items=${entries.size}; index=${cursor.index}; position=${cursor.positionMs}")
                resumeQueuePublicationPending = true
                result.set(MediaSession.MediaItemsWithStartPosition(entries.map { it.third }, cursor.index, cursor.positionMs))
            } catch (e: CancellationException) {
                if (resumeFuture === result) cancelledMedia3Resume = true
                result.setException(e)
                throw e
            } catch (e: Exception) {
                if (resumeFuture === result) {
                    cancelledMedia3Resume = true
                    resumeCommands.pause()
                    recoveryLoading.value = false
                    `in`.synthora.musicbox.network.NetworkFeedback.notify(e.message ?: "Couldn't restore playback. Open Music Box and choose a song.")
                }
                result.setException(e)
            } finally { if (restoreSessionJob === coroutineContext[Job]) restoreSessionJob = null }
        }
        result.addListener({ if (resumeFuture === result && result.isCancelled) cancelSessionResume() }, androidx.core.content.ContextCompat.getMainExecutor(this))
        return result
    }

    /** All session Play calls pass here; the app's already-authorized renderer calls stay unchanged. */
    private fun resumeFromSession() {
        if (cancelledMedia3Resume) return
        if (outputPrefs.getString("playback_output", "PHONE") != "PHONE") {
            `in`.synthora.musicbox.network.NetworkFeedback.notify("Playback is selected on another device. Open Music Box to select this phone.")
            return
        }
        val original = player.currentMediaItem ?: return
        if (resumeSessionJob?.isActive == true) return
        val request = resumeCommands.play()
        val claimAtStart = PhonePlaybackOwnership.token
        val id = original.mediaId
        recoveryLoading.value = true
        resumeSessionJob = serviceScope.launch {
            try {
                val config = original.localConfiguration
                val downloaded = withContext(Dispatchers.IO) {
                    runCatching {
                        when (config?.uri?.scheme) {
                            "file" -> java.io.File(config.uri.path.orEmpty()).let { it.isFile && it.canRead() && it.length() > 0 }
                            "content" -> contentResolver.openAssetFileDescriptor(config.uri, "r")?.use { true } ?: false
                            else -> false
                        }
                    }.getOrDefault(false)
                }
                val cached = config != null && fullyCachedSource(config.uri.toString(), config.customCacheKey)
                val online = `in`.synthora.musicbox.network.NetworkFeedback.online.value
                val authorized = (PhonePlaybackOwnership.permitsPlayback(claimAtStart) &&
                    (claimAtStart.isNotBlank() || PhonePlaybackOwnership.preparingSong)) || canContinueCurrentAudioOffline()
                if (!authorized && !online) {
                    check(downloaded || cached) { "This song isn't fully saved. Connect to the internet to resume it." }
                    check(!PhonePlaybackOwnership.remoteControlled && !PhonePlaybackOwnership.localHandoff) { "Playback is controlled by another device." }
                    PhonePlaybackOwnership.forget(allowOffline = true)
                } else if (!authorized) {
                    try {
                        val output = kotlinx.coroutines.withTimeout(4_000) { AlexaBackendApi.phoneOutputStatus() }
                        check(!blocksLocalResumption(output, PhonePlaybackOwnership.ownerId)) { "Playback is active on another device. Open Music Box to switch devices." }
                        check(resumeCommands.current(request) && PhonePlaybackOwnership.token == claimAtStart &&
                            outputPrefs.getString("playback_output", "PHONE") == "PHONE") { "Playback was superseded." }
                        if (!PhonePlaybackOwnership.restoreIfCurrent(output)) {
                            val claimed = kotlinx.coroutines.withTimeout(4_000) {
                                AlexaBackendApi.phoneOutputRequest("claim", PhonePlaybackOwnership.ownerId,
                                    serial = outputPrefs.getString("echo_serial", "").orEmpty())
                            }
                            check(resumeCommands.current(request) && PhonePlaybackOwnership.token == claimAtStart &&
                                outputPrefs.getString("playback_output", "PHONE") == "PHONE") { "Playback was superseded." }
                            PhonePlaybackOwnership.accept(claimed)
                            resumeQueuePublicationPending = true
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                        check(ownershipServerUnavailable(e) && (downloaded || cached) &&
                            !PhonePlaybackOwnership.remoteControlled && !PhonePlaybackOwnership.localHandoff &&
                            !blocksLocalResumption(MobileDeviceConnection.output.value, PhonePlaybackOwnership.ownerId)) { throw e }
                        PhonePlaybackOwnership.forget(allowOffline = true)
                    }
                }
                check(resumeCommands.current(request) && player.currentMediaItem?.mediaId == id &&
                    outputPrefs.getString("playback_output", "PHONE") == "PHONE" &&
                    (PhonePlaybackOwnership.permitsPlayback() || canContinueCurrentAudioOffline())) { "Playback was superseded." }
                publishResumedQueue()
                if (player.playerError != null) recoverCurrentStream(manual = true)
                else {
                    if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
                    if (player.playbackState == Player.STATE_IDLE) player.prepare()
                    if (inCall()) mainHandler.postDelayed(resumeAfterCall, 500) else player.play()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (resumeCommands.current(request)) {
                    resumeCommands.pause()
                    player.pause()
                    `in`.synthora.musicbox.network.NetworkFeedback.notify(
                        `in`.synthora.musicbox.network.networkErrorMessage(e) ?: e.message ?: "Couldn't resume playback. Tap Play to retry.")
                }
            } finally {
                if (resumeSessionJob === coroutineContext[Job]) {
                    resumeSessionJob = null
                    recoveryLoading.value = false
                    mediaSession?.let { onUpdateNotification(it, false) }
                }
            }
        }
    }

    private fun publishResumedQueue() {
        val claim = PhonePlaybackOwnership.token
        if (!resumeQueuePublicationPending || resumeQueuePublishJob?.isActive == true || claim.isBlank() ||
            !PhonePlaybackOwnership.permitsPlayback(claim) || PhonePlaybackOwnership.localHandoff ||
            !`in`.synthora.musicbox.network.NetworkFeedback.online.value ||
            android.os.SystemClock.elapsedRealtime() < resumeQueueRetryAt) return
        val ids = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        val index = player.currentMediaItemIndex
        resumeQueuePublishJob = serviceScope.launch {
            try {
                kotlinx.coroutines.withTimeout(4_000) {
                    val stored = withContext(Dispatchers.IO) {
                        ids.distinct().chunked(500).flatMap { database.trackDao().getTracksByUuids(it) }
                            .associateBy { it.uuid }
                    }
                    val entries = ids.mapIndexedNotNull { slot, id -> stored[id]?.toTrack()?.takeIf { !it.ytVideoId.isNullOrBlank() }?.let { slot to it } }
                    val selected = entries.firstOrNull { it.first == index } ?: return@withTimeout
                    if (PhonePlaybackOwnership.token != claim || !PhonePlaybackOwnership.permitsPlayback(claim) ||
                        ids != (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId } ||
                        player.currentMediaItem?.mediaId != selected.second.uuid ||
                        outputPrefs.getString("playback_output", "PHONE") != "PHONE") return@withTimeout
                    AlexaBackendApi.updateQueue("start", selected.second.ytVideoId!!, entries.map { AlexaBackendApi.backendTrack(it.second) },
                        player.isPlaying, player.currentPosition.coerceAtLeast(0), entries.indexOf(selected),
                        player.playbackState == Player.STATE_BUFFERING, expectedToken = claim)
                    if (PhonePlaybackOwnership.token == claim) resumeQueuePublicationPending = false
                }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                resumeQueueRetryAt = android.os.SystemClock.elapsedRealtime() + 15_000
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                resumeQueueRetryAt = android.os.SystemClock.elapsedRealtime() + 15_000
                Log.w(TAG, "Resumed queue publication deferred: ${e.javaClass.simpleName}")
            } finally { if (resumeQueuePublishJob === coroutineContext[Job]) resumeQueuePublishJob = null }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main +
        kotlinx.coroutines.CoroutineExceptionHandler { _, error ->
            Log.e(TAG, "Background playback task failed", error)
            `in`.synthora.musicbox.network.NetworkFeedback.notify("Playback task interrupted. Retry playback when connected.")
        })

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "in.synthora.musicbox.CANCEL_RECOVERY") {
            cancelSessionResume()
            leaseInterruption.clear()
            networkInterruption.clear()
            recoveryShouldResume = false
            streamRecoveryJob?.cancel()
            player.pause()
            mediaSession?.let { onUpdateNotification(it, false) }
            return START_NOT_STICKY
        }
        // A media-button receiver starts this with startForegroundService. Its
        // deadline applies even when artwork/network/audio preparation is slow.
        if (intent?.action == Intent.ACTION_MEDIA_BUTTON) {
            try {
                val notification = androidx.core.app.NotificationCompat.Builder(this, "media_playback")
                    .setSmallIcon(R.drawable.media3_notification_small_icon)
                    .setContentTitle(resumePrefs.getString("resume_title", null)?.takeIf { it.isNotBlank() } ?: "Music Box")
                    .setContentText("Preparing playback…")
                    .setOnlyAlertOnce(true).setOngoing(true).build()
                startForeground(1, notification)
                resumptionForegroundStarted = true
                serviceScope.launch {
                    delay(4_000)
                    mediaSession?.let { onUpdateNotification(it, false) }
                }
            } catch (error: RuntimeException) {
                Log.w(TAG, "Media-button foreground startup denied", error)
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }

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
        val malformed = shouldInvalidateAudio(player.playerError?.errorCode ?: 0, hasTruncatedAudio(player.playerError),
            hasInvalidAudioResponse(player.playerError), original.localConfiguration?.uri?.scheme in setOf("http", "https"))
        if (manual) malformedAudioRetried.remove(trackId)
        if (malformed && !malformedAudioRetried.add(trackId)) {
            recoveryShouldResume = false
            player.pause()
            `in`.synthora.musicbox.network.NetworkFeedback.notify("This audio could not be read after retrying. Try another song.")
            return
        }
        val httpFailure = generateSequence<Throwable>(player.playerError) { it.cause }
            .filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>().firstOrNull()
        if (httpFailure?.responseCode == 401) `in`.synthora.musicbox.network.AudioCredentials.clear()
        val retryAfter = httpFailure?.headerFields?.entries?.firstOrNull { it.key.equals("Retry-After", true) }?.value?.firstOrNull()
        val retryDelay = `in`.synthora.musicbox.network.audioRetryDelay(httpFailure?.responseCode, retryAfter, attempt)
        streamRetry.failed(android.os.SystemClock.elapsedRealtime(), retryDelay)
        val position = player.currentPosition.coerceAtLeast(0)
        recoveryShouldResume = manual || player.playWhenReady || recoveryShouldResume
        streamRecoveryJob = serviceScope.launch {
            val recoveryStarted = android.os.SystemClock.elapsedRealtime()
            try {
                val track = withContext(Dispatchers.IO) { database.trackDao().getTrackByUuid(trackId)?.toTrack() }
                    ?: return@launch
                DownloadRepository.get(applicationContext).awaitReady()
                if (malformed) {
                    upcomingPreloader?.clearAndAwait()
                    DownloadRepository.get(applicationContext).invalidateDamagedDownload(track, original.localConfiguration?.uri?.toString())
                    withContext(Dispatchers.IO) {
                        val config = original.localConfiguration
                        StreamCacheManager.removeTrackCache(config?.customCacheKey ?: config?.uri?.toString())
                    }
                }
                val local = DownloadRepository.get(applicationContext).localTrack(track).takeUnless {
                    malformed && sameAudioSource(it?.localUri, original.localConfiguration?.uri?.toString())
                }
                if (malformed && local == null && !`in`.synthora.musicbox.network.NetworkFeedback.online.value) {
                    malformedAudioRetried.remove(trackId)
                    recoveryShouldResume = false
                    player.pause()
                    `in`.synthora.musicbox.network.NetworkFeedback.notify("This audio is damaged. Connect to the internet to download it again.")
                    return@launch
                }
                val cachedSource = !malformed && player.currentMediaItem?.mediaId == trackId && currentStreamFullyCached()
                val refreshed = if (local != null) local else if (cachedSource) {
                    track.copy(localUri = original.localConfiguration!!.uri.toString(), isStream = true)
                } else {
                    val video = track.ytVideoId ?: return@launch
                    if (!manual || httpFailure?.responseCode == 429) delay(retryDelay)
                    val url = withContext(Dispatchers.IO) { AlexaBackendApi.getStreamUrl(video) }
                    track.copy(localUri = url, isStream = true)
                }
                if (player.currentMediaItem?.mediaId != trackId || !(PhonePlaybackOwnership.permitsPlayback(claim) ||
                        (PhonePlaybackOwnership.token == claim && canContinueCurrentAudioOffline())) ||
                    outputPrefs.getString("playback_output", "PHONE") == "ALEXA") return@launch
                withContext(Dispatchers.IO) {
                    database.trackDao().insertTrack(refreshed.toEntity())
                    // Keep valid cached spans; a network interruption does not invalidate audio bytes.
                }
                if (player.currentMediaItem?.mediaId != trackId || !(PhonePlaybackOwnership.permitsPlayback(claim) ||
                        (PhonePlaybackOwnership.token == claim && canContinueCurrentAudioOffline())) ||
                    outputPrefs.getString("playback_output", "PHONE") != "PHONE") return@launch
                val index = player.currentMediaItemIndex
                val resume = recoveryShouldResume
                // Media3 treats replacing an item with the same URI as a metadata update.
                // Stop first so prepare actually discards the broken loader/socket.
                player.stop()
                player.replaceMediaItem(index, original.buildUpon().setUri(refreshed.localUri!!).setMimeType(null)
                    .setCustomCacheKey(if (cachedSource) original.localConfiguration?.customCacheKey else null).build())
                player.seekTo(index, position)
                player.prepare()
                player.playWhenReady = resume && !inCall()
                if (resume && inCall()) mainHandler.postDelayed(resumeAfterCall, 500)
                PlaybackDiagnostics.record(PlaybackDiagnostics.Stage.RECOVERY, android.os.SystemClock.elapsedRealtime() - recoveryStarted, false)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                PlaybackDiagnostics.record(PlaybackDiagnostics.Stage.RECOVERY, android.os.SystemClock.elapsedRealtime() - recoveryStarted, true)
                streamRetry.failed(android.os.SystemClock.elapsedRealtime(), if (httpFailure?.responseCode == 429) retryDelay else 0)
                if (player.currentMediaItem?.mediaId == trackId) {
                    `in`.synthora.musicbox.network.NetworkFeedback.notify(
                        `in`.synthora.musicbox.network.networkErrorMessage(e) ?: "Playback interrupted. Tap Play to try again.")
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
                `in`.synthora.musicbox.network.Backend.post("/api/track/$videoId/listen", kotlinx.serialization.json.buildJsonObject {
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
        if (!player.playWhenReady || !`in`.synthora.musicbox.network.NetworkFeedback.online.value ||
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
                val radio = kotlinx.coroutines.withTimeout(12_000) { `in`.synthora.musicbox.network.AlexaBackendApi.getRadio(video) }
                val oldTracks = withContext(Dispatchers.IO) { before.mapNotNull { database.trackDao().getTrackByUuid(it)?.toTrack() } }
                val ids = unheardQueueIds(oldTracks.mapNotNull { it.ytVideoId }, radio.map { it.videoId })
                val tracks = radio.distinctBy { it.videoId }.filter { it.videoId in ids }.map {
                    val online = it.toAppTrack(`in`.synthora.musicbox.network.AlexaBackendApi.audioUrl(it.videoId))
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
                `in`.synthora.musicbox.network.AlexaBackendApi.updateQueue("start", requireNotNull(selected.ytVideoId),
                    all.map(`in`.synthora.musicbox.network.AlexaBackendApi::backendTrack), player.isPlaying,
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
            val causes = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
            val status = causes.filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
            PlaybackDiagnostics.recordAudioFailure(error.errorCode, causes.map { it.javaClass.simpleName }, status)
            Log.e(TAG, "Player error code=${error.errorCode}; causes=${causes.map { it.javaClass.simpleName }}; HTTP=$status")
            recoverCurrentStream()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            Log.d(TAG, "Playback intent changed: play=$playWhenReady; reason=$reason; state=${player.playbackState}; position=${player.currentPosition}; buffered=${player.bufferedPosition}; serverUnavailable=${PhonePlaybackOwnership.serverUnavailable}")
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
            malformedAudioRetried.clear()
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
            Log.d(TAG, "Playback position changed: reason=$reason; old=${oldPosition.positionMs}; new=${newPosition.positionMs}")
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
        val notificationProvider = MusicNotificationProvider(applicationContext, 1, "media_playback")
        setMediaNotificationProvider(GuardedMediaNotificationProvider(notificationProvider,
            { activeService.get() === this && outputPrefs.getString("playback_output", "PHONE") == "PHONE" },
            ::onPhoneForegroundDenied, { holdSongPreparationNotification() || holdNotificationHandoff() || holdRecoveryNotification() }))

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
            applicationContext, StreamCacheManager.getCache(applicationContext), transferListener = transferListener, readOnlyCache = true))

        // Keep decoded/sample buffering modest; the independent disk warmer saves
        // the whole song without requiring the player to buffer it all in RAM.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                30_000,  // minBufferMs
                120_000, // maxBufferMs
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
            leaseRenewed = { offlineReconciliation.reconciled(); recoverAfterLeaseRenewal() },
            offlineAudioAvailable = ::canContinueCurrentAudioOffline,
            ownershipOutage = ::recoverAfterLeaseRenewal,
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
            StreamCacheManager.getCache(applicationContext), prefetch = true, cacheWarm = true), serviceScope, applicationContext,
            demandFactory = playbackDataSources(applicationContext, StreamCacheManager.getCache(applicationContext), prefetch = false, cacheWarm = true))
        getSystemService(android.net.ConnectivityManager::class.java).registerDefaultNetworkCallback(preloadNetworkCallback)
        preloadConnectivityJob = serviceScope.launch {
            `in`.synthora.musicbox.network.NetworkFeedback.online.collect { online ->
                try {
                    offlineReconciliation.update(online, android.os.SystemClock.elapsedRealtime())
                    if (!online && player.currentMediaItem?.localConfiguration?.uri?.scheme in setOf("http", "https")) {
                        networkInterruption.remember(player.currentMediaItem?.mediaId, PhonePlaybackOwnership.token,
                            player.playWhenReady || recoveryShouldResume || leaseInterruption.pending || PhonePlaybackOwnership.remoteControlled)
                    }
                    if (online) { streamRetry.reset(); upcomingPreloader?.networkRestored(); recoverAfterNetworkReconnect() }
                    updatePreloader()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { Log.e(TAG, "Connectivity recovery failed", e) }
            }
        }
        backgroundMaintenance = serviceScope.launch {
            while (isActive) {
                delay(5_000)
                try {
                    if (player.isPlaying) saveResumptionState()
                    if (resumeCommands.wantsPlay && player.playWhenReady && PhonePlaybackOwnership.token.isBlank() &&
                        `in`.synthora.musicbox.network.NetworkFeedback.online.value) resumeFromSession()
                    publishResumedQueue()
                    updatePreloader() // Retry failed warming even without a new UI/player event.
                    recoverAfterLeaseRenewal()
                    recoverAfterNetworkReconnect()
                    if (bufferingStall.shouldRecover(player.currentMediaItem?.mediaId,
                            player.playbackState == Player.STATE_BUFFERING, player.playWhenReady,
                            outputPrefs.getString("playback_output", "PHONE") == "PHONE" &&
                                PhonePlaybackOwnership.permitsPlayback() && !PhonePlaybackOwnership.localHandoff &&
                                `in`.synthora.musicbox.network.NetworkFeedback.online.value && !inCall(),
                            android.os.SystemClock.elapsedRealtime(), player.currentPosition, player.bufferedPosition)) {
                        Log.w(TAG, "Buffering stalled without progress; reopening the current stream")
                        recoverCurrentStream(manual = true)
                    }
                    if (recoveryShouldResume && player.playerError != null &&
                        `in`.synthora.musicbox.network.NetworkFeedback.online.value &&
                        PhonePlaybackOwnership.permitsPlayback() && !PhonePlaybackOwnership.localHandoff && !inCall()) {
                        recoverCurrentStream()
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { Log.w(TAG, "Background playback maintenance failed: ${e.javaClass.simpleName}") }
            }
        }
        player.addListener(object : Player.Listener {
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED && resumeQueuePublishJob?.isActive == true) {
                    resumeQueuePublishJob?.cancel()
                    resumeQueuePublicationPending = false
                }
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED &&
                    (resumeSessionJob?.isActive == true || (restoreSessionJob?.isActive == true && resumeFuture?.isDone == false))) {
                    cancelSessionResume()
                    resumeQueuePublicationPending = false
                }
            }
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION,
                        Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED, Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_PLAY_WHEN_READY_CHANGED)) {
                    if (restoreSessionJob?.isActive == true && player.mediaItemCount > 0 && resumeFuture?.isDone == false) cancelSessionResume()
                    saveResumptionState(events.contains(Player.EVENT_TIMELINE_CHANGED))
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

        val sessionPlayer = ResumableSessionPlayer(player, ::resumeFromSession,
            onPause = { cancelSessionResume(); player.pause() },
            onStop = { saveResumptionState(); cancelSessionResume(); player.stop() })
        mediaSession = MediaLibrarySession.Builder(this, sessionPlayer, MediaLibrarySessionCallback())
            .apply {
                sessionActivityPendingIntent?.let { setSessionActivity(it) }
            }
            .setBitmapLoader(bitmapLoader)
            .setShowPlayButtonIfPlaybackIsSuppressed(true)
            .build()

        androidx.core.content.ContextCompat.registerReceiver(this, noisyDuringResume,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
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
        saveResumptionState()
        cancelSessionResume()
        remoteForegroundBridge = false
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

    private fun holdSongPreparationNotification(): Boolean = retainSongPreparationNotification(
        outputPrefs.getString("playback_output", "PHONE") == "PHONE", isPlaybackOngoing,
        PhonePlaybackOwnership.preparingSong)

    private fun holdNotificationHandoff(): Boolean = remoteForegroundBridge &&
        (outputPrefs.getString("playback_output", "PHONE") != "PHONE" ||
            (!player.isPlaying && PhonePlaybackOwnership.localHandoff))

    @OptIn(UnstableApi::class)
    private fun holdRecoveryNotification(): Boolean {
        if (!::player.isInitialized || !retainRecoveryNotification(
                outputPrefs.getString("playback_output", "PHONE") == "PHONE", isPlaybackOngoing,
                player.isPlaying, leaseInterruption.pending, networkInterruption.pending,
                recoveryShouldResume || (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING), PhonePlaybackOwnership.localHandoff)) return false
        val session = mediaSession ?: return false
        val open = PendingIntent.getActivity(this, 0, Intent(this, `in`.synthora.musicbox.MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel = PendingIntent.getService(this, 91, Intent(this, PlaybackService::class.java)
            .setAction("in.synthora.musicbox.CANCEL_RECOVERY"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
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
        recoveryLoading.value = outputPrefs.getString("playback_output", "PHONE") == "PHONE" &&
            !player.isPlaying && !PhonePlaybackOwnership.localHandoff &&
            (restoreSessionJob?.isActive == true || resumeSessionJob?.isActive == true || recoveryShouldResume || leaseInterruption.pending || networkInterruption.pending ||
                (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING))
        if (resumptionForegroundStarted && !player.isPlaying &&
            (restoreSessionJob?.isActive == true || resumeSessionJob?.isActive == true)) return
        resumptionForegroundStarted = false
        // The already-authorized foreground service must survive preparation. It
        // adopts the normal local media notification as soon as audio actually starts.
        if (holdSongPreparationNotification()) return
        if (remoteForegroundBridge) {
            if (holdNotificationHandoff()) return
            remoteForegroundBridge = false
        }
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
        cancelSessionResume()
        cancelledMedia3Resume = true
        // Android did not grant a background playback start. Do not keep audio running
        // without its required foreground service or transfer it to another phone.
        recoveryShouldResume = false
        leaseInterruption.clear()
        networkInterruption.clear()
        streamRecoveryJob?.cancel()
        streamRecoveryJob = null
        if (::player.isInitialized) player.pause()
        `in`.synthora.musicbox.network.NetworkFeedback.notify("Open Music Box on the playback device, then tap play to resume.")
        Log.w(TAG, "Android denied foreground playback promotion")
    }

    override fun onDestroy() {
        saveResumptionState()
        cancelSessionResume()
        runCatching { unregisterReceiver(noisyDuringResume) }
        recoveryLoading.value = false
        if (activeService.get() === this) activeService.clear()
        outputPrefs.unregisterOnSharedPreferenceChangeListener(outputListener)
        runCatching { getSystemService(android.net.ConnectivityManager::class.java).unregisterNetworkCallback(preloadNetworkCallback) }
        upcomingPreloader?.clear()
        StreamCacheManager.protectPlaying(null)
        serviceScope.coroutineContext[Job]?.cancel()
        // Clean up pending resume operations
        mainHandler.removeCallbacks(resumeAfterCall)

        mediaSession?.run {
            this@PlaybackService.player.release()
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
        mediaSession?.setCustomLayout(listOf(button, NotificationRadio.button()))
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

        override fun onPlaybackResumption(session: MediaSession, controller: MediaSession.ControllerInfo):
            ListenableFuture<MediaSession.MediaItemsWithStartPosition> = restoreSessionPlaylist()

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
                    .add(SessionCommand(NotificationRadio.ACTION, Bundle.EMPTY))
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
            if (customCommand.customAction == NotificationRadio.ACTION) return Futures.immediateFuture(
                SessionResult(if (NotificationRadio.start(this@PlaybackService)) SessionResult.RESULT_SUCCESS else SessionResult.RESULT_ERROR_INVALID_STATE))
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
                cancelledMedia3Resume = false
                leaseInterruption.clear()
                networkInterruption.clear()
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

            if (keyEvent?.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0) {
                when (keyEvent.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> cancelSessionResume()
                    KeyEvent.KEYCODE_MEDIA_PLAY -> cancelledMedia3Resume = false
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                        if (resumeCommands.wantsPlay && !player.isPlaying) {
                            cancelSessionResume()
                            player.pause()
                            return true
                        }
                        cancelledMedia3Resume = false
                    }
                }
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
                        .setMediaId(if (params?.isRecent == true) "resume" else "root")
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
                "resume" -> serviceScope.async {
                    val ids = resumePrefs.getString("queue_track_ids", "").orEmpty().split(",")
                    val id = ids.getOrNull(resumePrefs.getInt("queue_start_index", 0))
                    val track = id?.let { database.trackDao().getTrackByUuid(it)?.toTrack() }
                    if (track == null) LibraryResult.ofError<ImmutableList<MediaItem>>(SessionError.ERROR_INVALID_STATE)
                    else LibraryResult.ofItemList(ImmutableList.of(buildPlayableMediaItem(track)), params)
                }.asListenableFuture()
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
