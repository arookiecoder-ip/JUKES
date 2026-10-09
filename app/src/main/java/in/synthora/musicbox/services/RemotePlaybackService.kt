package `in`.synthora.musicbox.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import `in`.synthora.musicbox.MainActivity
import `in`.synthora.musicbox.R
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** A remote media session: no audio renderer or audio focus on this phone. */
@UnstableApi
class RemotePlaybackService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate +
        CoroutineExceptionHandler { _, error ->
            `in`.synthora.musicbox.utils.SafeLog.e("RemotePlaybackService", "Remote notification task failed", error)
            `in`.synthora.musicbox.network.NetworkFeedback.notify("Couldn't update Alexa controls. Retry when connected.")
        })
    private lateinit var echo: EchoController
    private lateinit var remote: EchoPlayer
    private var session: MediaSession? = null
    private var restoringNotification = false
    private var skipSequence = 0L
    private var boundaryJob: Job? = null
    private var boundaryConfirmationJob: Job? = null
    private var boundaryPreview: RemoteTrackEndPreview? = null
    private var attemptedBoundary: String? = null
    private var authoritativeSnapshot: EchoState? = null
    private var boundaryArtworkJob: Job? = null
    private var boundaryArtworkKey: String? = null
    private var localServiceAnchor: ListenableFuture<androidx.media3.session.MediaController>? = null

    override fun onCreate() {
        super.onCreate()
        // Keep the local renderer service bound while remote controls are alive.
        // A foreground-screen controller may release/reconnect; its lifecycle
        // must not silently remove the destination of a later background handoff.
        val anchor = androidx.media3.session.MediaController.Builder(ControllerBindingContext(this),
            androidx.media3.session.SessionToken(this,
                android.content.ComponentName(this, PlaybackService::class.java))).buildAsync()
        localServiceAnchor = anchor
        anchor.addListener({
            if (localServiceAnchor !== anchor) return@addListener
            try { anchor.get(); if (::echo.isInitialized) updateForegroundBridge() }
            catch (error: Exception) {
                `in`.synthora.musicbox.utils.SafeLog.w("RemotePlaybackService", "Local service binding unavailable: ${error.javaClass.simpleName}")
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(this))
        val prefs = getSharedPreferences("music_settings_prefs", Context.MODE_PRIVATE)
        echo = PlaybackCoordinator.echo(this)
        scope.launch { PlaybackCoordinator.errors.collect {
            // Service outlives the Activity: route through the foreground-guarded channel
            // instead of toasting directly while minimized.
            `in`.synthora.musicbox.network.NetworkFeedback.notify(it)
        } }
        scope.launch { PlaybackCoordinator.signedOut.collect { stopSelf() } }
        remote = EchoPlayer().apply { snapshot = if (prefs.getBoolean("remote_controls_dismissed", false)) EchoState() else echo.state.value.takeIf { it.track != null } ?: initialSnapshot ?: EchoState() }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel("alexa_playback", "Alexa playback", NotificationManager.IMPORTANCE_LOW))
        setMediaNotificationProvider(GuardedMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this)
            .setNotificationId(1002).setChannelId("alexa_playback").build().apply { setSmallIcon(R.drawable.media3_notification_small_icon) },
            { session != null && prefs.getString("playback_output", "PHONE") in setOf("ALEXA", "REMOTE_PHONE") && !prefs.getBoolean("remote_controls_dismissed", false) },
            { PlaybackService.leaveRemoteForeground(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() },
            onRecoveryUpdate = ::holdDestinationHandoff, onUpdated = ::updateForegroundBridge))
        session = MediaSession.Builder(this, remote).setId("alexa")
            .setBitmapLoader(SharedArtworkBitmapLoader(this, scope))
            .setSessionActivity(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
                    MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                            .add(SessionCommand(LIKE, Bundle.EMPTY)).build()).build()
                override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                    if (customCommand.customAction != LIKE) return super.onCustomCommand(session, controller, customCommand, args)
                    val result = SettableFuture.create<SessionResult>()
                    scope.launch {
                        try {
                            remote.snapshot.track?.ytVideoId?.let { AccountRepository.setLiked(it, !AccountRepository.isLiked(it)) }
                            result.set(SessionResult(SessionResult.RESULT_SUCCESS))
                        } catch (e: Exception) { result.setException(e) }
                    }
                    return result
                }
            }).build()
        addSession(requireNotNull(session))
        scope.launch {
            var first = true
            echo.state.collect {
                try {
                    if (!first || it.track != null || remote.snapshot.track == null) { applyRemoteSnapshot(it) }
                    first = false
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { `in`.synthora.musicbox.utils.SafeLog.e("RemotePlaybackService", "Remote state refresh failed", e) }
            }
        }
        scope.launch { AccountRepository.liked.collect {
            try { updateLike() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { `in`.synthora.musicbox.utils.SafeLog.e("RemotePlaybackService", "Remote like refresh failed", e) }
        } }
        if (!prefs.getBoolean("remote_controls_dismissed", false) && !isRemotePhone()) PlaybackCoordinator.observe(this, "notification", true)
        scope.launch { MobileDeviceConnection.devices.collect { remote.refresh() } }
        scope.launch {
            var observedSerial = echo.serial.value
            echo.serial.collect { serial ->
                if (serial != observedSerial) {
                    observedSerial = serial
                    cancelBoundaryPreview()
                    authoritativeSnapshot = null
                    attemptedBoundary = null
                    remote.snapshot = EchoState()
                    remote.refresh(); updateLike()
                }
            }
        }
        applyRemoteSnapshot(remote.snapshot)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("music_settings_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("remote_controls_dismissed", false) || prefs.getString("playback_output", "PHONE") !in setOf("ALEXA", "REMOTE_PHONE")) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent == null) {
            // Android may restart a formerly foreground service before a server
            // snapshot is available. Meet its foreground deadline without waiting
            // for artwork, presence or Alexa to respond.
            restoringNotification = true
            try {
                startForeground(1002, androidx.core.app.NotificationCompat.Builder(this, "alexa_playback")
                    .setSmallIcon(R.drawable.media3_notification_small_icon)
                    .setContentTitle("Music Box").setContentText("Reconnecting playback controls…")
                    .setOnlyAlertOnce(true).setOngoing(true).build())
            } catch (error: RuntimeException) {
                restoringNotification = false
                stopSelf(startId)
                return START_NOT_STICKY
            }
            scope.launch {
                delay(30_000)
                restoringNotification = false
                session?.let { onUpdateNotification(it, false) }
            }
        }
        val serial = prefs.getString("echo_serial", "").orEmpty()
        if (!isRemotePhone() && serial.isNotBlank() && serial != echo.serial.value) echo.select(serial)
        // Existing sessions retain the latest polled state, not the Activity seed.
        PlaybackCoordinator.observe(this, "notification", !isRemotePhone())
        super.onStartCommand(intent, flags, startId)
        // Restore active remote controls after process reclamation. Dismissal, sign-out
        // and output changes above still prevent a restart.
        return START_STICKY
    }

    private fun cancelBoundaryPreview() {
        boundaryJob?.cancel(); boundaryJob = null
        boundaryConfirmationJob?.cancel(); boundaryConfirmationJob = null
        boundaryPreview = null
    }

    private fun applyRemoteSnapshot(state: EchoState) {
        if (authoritativeSnapshot?.let { olderRemoteNotificationSnapshot(state, it) } == true) return
        authoritativeSnapshot = state
        val now = android.os.SystemClock.elapsedRealtime()
        val preview = boundaryPreview
        if (preview != null && !preview.retainFor(state, now)) {
            boundaryPreview = null
            boundaryConfirmationJob?.cancel(); boundaryConfirmationJob = null
        }
        remote.snapshot = boundaryPreview?.present(state) ?: state
        remote.refresh(); updateLike()
        boundaryJob?.cancel(); boundaryJob = null
        if (boundaryPreview != null || PlaybackCoordinator.notificationLoading.value) return
        if (state.durationMs > 0 && state.livePosition(now) < state.durationMs - 2_000) attemptedBoundary = null
        val key = state.remoteBoundaryKey()
        if (attemptedBoundary == key) return
        val remaining = remoteTrackEndDelay(state, now) ?: return
        warmBoundaryArtwork(state.queue[state.index + 1])
        val serial = echo.serial.value
        val output = getSharedPreferences("music_settings_prefs", MODE_PRIVATE).getString("playback_output", "PHONE")
        if (output !in setOf("ALEXA", "REMOTE_PHONE")) return
        boundaryJob = scope.launch {
            delay(remaining)
            val prefs = getSharedPreferences("music_settings_prefs", MODE_PRIVATE)
            val current = echo.state.value
            if (echo.serial.value != serial || prefs.getString("playback_output", "PHONE") != output ||
                prefs.getBoolean("remote_controls_dismissed", false) || PlaybackCoordinator.notificationLoading.value ||
                current.remoteBoundaryKey() != key ||
                authoritativeSnapshot?.let { olderRemoteNotificationSnapshot(current, it) } == true) return@launch
            val predicted = remoteTrackEndPreview(current, android.os.SystemClock.elapsedRealtime()) ?: return@launch
            attemptedBoundary = key
            boundaryPreview = predicted
            remote.snapshot = predicted.present(current)
            remote.refresh(); updateLike()
            boundaryConfirmationJob = scope.launch {
                delay(30_000)
                if (boundaryPreview === predicted && predicted.expired(android.os.SystemClock.elapsedRealtime())) {
                    boundaryPreview = null
                    applyRemoteSnapshot(authoritativeSnapshot ?: echo.state.value)
                    `in`.synthora.musicbox.network.NetworkFeedback.notify("The remote device hasn't confirmed the next song. Check its connection.")
                }
            }
            // One boundary refresh, reusing the existing request path; no fast polling loop.
            try {
                if (isRemotePhone()) echo.refreshSharedPhone() else echo.refresh(force = true, stateOnly = true)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { /* Existing observation retries; preview has a bounded deadline. */ }
        }
    }

    private fun warmBoundaryArtwork(track: `in`.synthora.musicbox.models.Track) {
        val source = track.thumbnailUri.orEmpty()
        val key = track.ytVideoId ?: source
        if (key.isBlank() || key == boundaryArtworkKey) return
        boundaryArtworkJob?.cancel()
        boundaryArtworkKey = key
        val prefs = getSharedPreferences("music_settings_prefs", MODE_PRIVATE)
        val metered = getSystemService(android.net.ConnectivityManager::class.java).isActiveNetworkMetered
        if (!`in`.synthora.musicbox.network.NetworkFeedback.online.value ||
            (metered && !prefs.getBoolean("prefetch_mobile_data", false))) return
        boundaryArtworkJob = scope.launch {
            try {
                withTimeoutOrNull(8_000) {
                    `in`.synthora.musicbox.network.ArtworkRepository.load(applicationContext, source, track.ytVideoId)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Artwork never blocks the boundary timer or playback controls. */ }
        }
    }

    private fun isRemotePhone() = getSharedPreferences("music_settings_prefs", MODE_PRIVATE)
        .getString("playback_output", "PHONE") == "REMOTE_PHONE"

    private fun phoneControlsSelected() = getSharedPreferences("music_settings_prefs", MODE_PRIVATE)
        .getString("playback_output", "PHONE") in setOf("PHONE", "REMOTE_PHONE")

    private fun localPhoneSelected() = getSharedPreferences("music_settings_prefs", MODE_PRIVATE)
        .getString("playback_output", "PHONE") == "PHONE"

    private fun localVolume(): Int {
        val audio = getSystemService(android.media.AudioManager::class.java)
        return audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) * 100 /
            audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }

    private fun setLocalVolume(value: Int, flags: Int) {
        val audio = getSystemService(android.media.AudioManager::class.java)
        audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC,
            volumePercentToStreamIndex(value, audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)), flags)
    }

    private suspend fun command(action: String) {
        cancelBoundaryPreview()
        remote.snapshot = echo.state.value
        val skipping = action in setOf("next", "previous")
        val sequence = if (skipping || action == "pause") ++skipSequence else skipSequence
        val previous = echo.state.value.track?.ytVideoId
        if (skipping) {
            PlaybackCoordinator.notificationLoading.value = true
            remote.refresh()
        } else if (action == "pause") PlaybackCoordinator.notificationLoading.value = false
        try {
            if (phoneControlsSelected()) MobileDeviceConnection.control(MobileDeviceConnection.output.value, action)
            else echo.command(action)
            if (skipping) withTimeout(30_000) {
                echo.state.first { it.track?.ytVideoId != previous && it.playing && it.confirmed && !it.processing }
            }
        } finally {
            if (skipping && sequence == skipSequence) {
                PlaybackCoordinator.notificationLoading.value = false
                remote.refresh()
            }
        }
    }

    private fun updateLike() {
        session?.setCustomLayout(listOf(CommandButton.Builder()
            .setDisplayName("Like").setIconResId(if (AccountRepository.isLiked(remote.snapshot.track?.ytVideoId))
                R.drawable.thumb_up_filled else R.drawable.thumb_up_outline)
            .setSessionCommand(SessionCommand(LIKE, Bundle.EMPTY)).build()))
    }

    private fun holdDestinationHandoff(): Boolean {
        val presence = MobileDeviceConnection.output.value
        val observed = echo.state.value.sharedOutput
        val current = if (observed.mode.isNotBlank() && !observed.olderThan(presence)) observed else presence
        return retainDestinationForeground(current, PhonePlaybackOwnership.ownerId,
            PhonePlaybackOwnership.token, PhonePlaybackOwnership.localHandoff, isPlaybackOngoing)
    }

    private fun updateForegroundBridge() {
        if (restoringNotification && remote.snapshot.track == null) return
        if (holdDestinationHandoff()) return
        if (isPlaybackOngoing) {
            val notification = getSystemService(NotificationManager::class.java).activeNotifications
                .firstOrNull { it.id == 1002 }?.notification ?: return
            PlaybackService.joinRemoteForeground(notification)
        } else PlaybackService.leaveRemoteForeground()
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        if (restoringNotification && remote.snapshot.track == null) return
        restoringNotification = false
        // A source pause is not permission to tear down the destination's already
        // authorized foreground services before its local renderer is ready.
        if (holdDestinationHandoff()) return
        try { super.onUpdateNotification(session, startInForegroundRequired); updateForegroundBridge() }
        catch (e: Exception) {
            if (android.os.Build.VERSION.SDK_INT >= 31 && e is android.app.ForegroundServiceStartNotAllowedException) {
                // A remote Echo can resume while Android no longer permits a background service start.
                // Drop only this notification service; the foreground Activity recreates it legally.
                `in`.synthora.musicbox.utils.SafeLog.e("RemotePlaybackService", "Android deferred remote notification restart", e)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else throw e
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onTaskRemoved(rootIntent: Intent?) {
        MobileDeviceConnection.stop()
        PlaybackCoordinator.dismiss(this)
        // Only remove the remote controls; never send a pause to Alexa.
        getSharedPreferences("music_settings_prefs", MODE_PRIVATE).edit().putBoolean("remote_controls_dismissed", true).apply()
        PlaybackService.leaveRemoteForeground(force = true)
        PlaybackCoordinator.observe(this, "notification", false)
        remote.snapshot = EchoState()
        remote.refresh()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(1002)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        PlaybackCoordinator.notificationLoading.value = false
        PlaybackService.leaveRemoteForeground(force = true)
        PlaybackCoordinator.observe(this, "notification", false)
        remote.snapshot = EchoState()
        remote.refresh()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(1002)

        val anchor = localServiceAnchor
        localServiceAnchor = null
        anchor?.let { androidx.media3.session.MediaController.releaseFuture(it) }
        session?.let { removeSession(it); it.release() }; remote.release(); scope.cancel()
        super.onDestroy()
    }

    private inner class EchoPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        var snapshot = EchoState()
        private var cachedTrack: `in`.synthora.musicbox.models.Track? = null
        private var cachedQueue: List<`in`.synthora.musicbox.models.Track>? = null
        private var cachedIndex = C.INDEX_UNSET
        private var cachedDuration = C.TIME_UNSET
        private var cachedPlaylist = emptyList<MediaItemData>()
        fun refresh() = invalidateState()
        override fun getState(): State {
            val track = snapshot.track
            // Android controls need the current item and its immediate neighbors, not
            // thousands of artwork/metadata objects rebuilt on the main thread.
            val window = notificationQueueWindow(snapshot)
            val queue = window.first
            val index = window.second
            val playlist = if (cachedTrack == track && cachedQueue == queue && cachedIndex == index && cachedDuration == snapshot.durationMs) cachedPlaylist
                else queue.mapIndexed { i, song ->
                val duration = if (i == index) snapshot.durationMs else song.durationSec * 1000L
                MediaItemData.Builder("$i:${song.ytVideoId ?: song.uuid}")
                    .setMediaItem(MediaItem.Builder().setMediaId(song.ytVideoId ?: song.uuid)
                        .setMediaMetadata(MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist)
                            .apply { `in`.synthora.musicbox.network.artworkCandidates(song.thumbnailUri.orEmpty(), song.ytVideoId, true).firstOrNull()?.takeIf(String::isNotBlank)?.let { `in`.synthora.musicbox.network.ArtworkRepository.register(it, song.ytVideoId); setArtworkUri(it.toUri()) } }.build()).build())
                    .setDurationUs(if (duration > 0) duration * 1000 else C.TIME_UNSET).build()
            }
            cachedTrack = track
            cachedQueue = queue
            cachedIndex = index
            cachedDuration = snapshot.durationMs
            cachedPlaylist = playlist
            return State.Builder().setAvailableCommands(Player.Commands.Builder().addAll(
                Player.COMMAND_PLAY_PAUSE, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_DEVICE_VOLUME, Player.COMMAND_SET_DEVICE_VOLUME, Player.COMMAND_ADJUST_DEVICE_VOLUME,
                Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
                Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS).build())
                .setPlaylist(playlist).setCurrentMediaItemIndex(index)
                .setPlaybackState(if (track == null) Player.STATE_IDLE else if (snapshot.loading || PlaybackCoordinator.notificationLoading.value) Player.STATE_BUFFERING else Player.STATE_READY)
                .setPlayWhenReady(snapshot.playing || snapshot.loading || PlaybackCoordinator.notificationLoading.value, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setContentPositionMs { snapshot.livePosition() }
                .setDeviceVolume(if (localPhoneSelected()) localVolume() else RemoteVolumeControl.current(this@RemotePlaybackService) ?: 0)
                .setDeviceInfo(DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMinVolume(0).setMaxVolume(100).build()).build()
        }
        private fun send(action: suspend () -> Unit): ListenableFuture<*> {
            val result = SettableFuture.create<Void>()
            scope.launch {
                try { action(); refresh(); result.set(null) }
                catch (e: CancellationException) { result.cancel(false); throw e }
                catch (e: Exception) {
                    `in`.synthora.musicbox.network.NetworkFeedback.notify("Couldn't update Alexa playback")
                    result.setException(e)
                }
            }
            return result
        }
        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> =
            send { command(if (playWhenReady) "play" else "pause") }
        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> = send {
            cancelBoundaryPreview()
            remote.snapshot = echo.state.value
            when (seekCommand) {
                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> command("next")
                Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> command("previous")
                else -> if (phoneControlsSelected()) MobileDeviceConnection.control(MobileDeviceConnection.output.value, "seek",
                    kotlinx.serialization.json.buildJsonObject { put("position_ms", kotlinx.serialization.json.JsonPrimitive(positionMs.coerceAtLeast(0))) })
                    else echo.seek(positionMs.coerceAtLeast(0))
            }
        }
        override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> {
            if (localPhoneSelected()) setLocalVolume(deviceVolume, flags)
            else RemoteVolumeControl.set(this@RemotePlaybackService, deviceVolume)
            refresh()
            return Futures.immediateVoidFuture()
        }
        override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> {
            if (localPhoneSelected()) getSystemService(android.media.AudioManager::class.java)
                .adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_RAISE, flags)
            else RemoteVolumeControl.adjust(this@RemotePlaybackService, 1)
            refresh()
            return Futures.immediateVoidFuture()
        }
        override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> {
            if (localPhoneSelected()) getSystemService(android.media.AudioManager::class.java)
                .adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_LOWER, flags)
            else RemoteVolumeControl.adjust(this@RemotePlaybackService, -1)
            refresh()
            return Futures.immediateVoidFuture()
        }
        override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()
    }

    companion object {
        private const val LIKE = "REMOTE_LIKE"
        fun dismiss(context: Context) {
            PlaybackCoordinator.dismiss(context)
            context.getSharedPreferences("music_settings_prefs", Context.MODE_PRIVATE).edit().putBoolean("remote_controls_dismissed", true).apply()
            stop(context)
        }
        fun stop(context: Context) { context.stopService(Intent(context, RemotePlaybackService::class.java)) }
        @Volatile internal var initialSnapshot: EchoState? = null
        fun start(context: Context, snapshot: EchoState = EchoState(), foreground: Boolean = false) {
            if (context.getSharedPreferences("music_settings_prefs", Context.MODE_PRIVATE).getBoolean("remote_controls_dismissed", false)) return
            initialSnapshot = snapshot
            try { context.startService(Intent(context, RemotePlaybackService::class.java).putExtra("foreground", foreground)) }
            catch (_: IllegalStateException) { /* The next foreground entry reconnects if Android stopped an idle service. */ }
        }
    }
}
