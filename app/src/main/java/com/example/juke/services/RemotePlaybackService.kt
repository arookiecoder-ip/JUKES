package com.example.juke.services

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
import com.example.juke.MainActivity
import com.example.juke.R
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.*

/** A remote media session: no audio renderer or audio focus on this phone. */
@UnstableApi
class RemotePlaybackService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var echo: EchoController
    private lateinit var remote: EchoPlayer
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("music_settings_prefs", Context.MODE_PRIVATE)
        echo = PlaybackCoordinator.echo(this)
        scope.launch { PlaybackCoordinator.errors.collect {
            // Service outlives the Activity: route through the foreground-guarded channel
            // instead of toasting directly while minimized.
            com.example.juke.network.NetworkFeedback.notify(it)
        } }
        scope.launch { PlaybackCoordinator.signedOut.collect { stopSelf() } }
        remote = EchoPlayer().apply { snapshot = if (prefs.getBoolean("remote_controls_dismissed", false)) EchoState() else initialSnapshot ?: EchoState() }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel("alexa_playback", "Alexa playback", NotificationManager.IMPORTANCE_LOW))
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this)
            .setNotificationId(1002).setChannelId("alexa_playback").build().apply { setSmallIcon(R.drawable.media3_notification_small_icon) })
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
                            echo.state.value.track?.ytVideoId?.let { AccountRepository.setLiked(it, !AccountRepository.isLiked(it)) }
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
                if (!first || initialSnapshot?.track == null) { remote.snapshot = it; remote.refresh(); updateLike() }
                first = false
            }
        }
        scope.launch { AccountRepository.liked.collect { updateLike() } }
        if (!prefs.getBoolean("remote_controls_dismissed", false)) PlaybackCoordinator.observe(this, "notification", true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("music_settings_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("remote_controls_dismissed", false) || prefs.getString("playback_output", "PHONE") != "ALEXA") {
            stopSelf()
            return START_NOT_STICKY
        }
        val serial = prefs.getString("echo_serial", "").orEmpty()
        if (serial.isNotBlank() && serial != echo.serial.value) echo.select(serial)
        // Existing sessions retain the latest polled state, not the Activity seed.
        PlaybackCoordinator.observe(this, "notification", true)
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    private fun updateLike() {
        session?.setCustomLayout(listOf(CommandButton.Builder()
            .setDisplayName("Like").setIconResId(if (AccountRepository.isLiked(remote.snapshot.track?.ytVideoId))
                R.drawable.thumb_up_filled else R.drawable.thumb_up_outline)
            .setSessionCommand(SessionCommand(LIKE, Bundle.EMPTY)).build()))
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onTaskRemoved(rootIntent: Intent?) {
        MobileDeviceConnection.stop()
        PlaybackCoordinator.dismiss(this)
        // Only remove the remote controls; never send a pause to Alexa.
        getSharedPreferences("music_settings_prefs", MODE_PRIVATE).edit().putBoolean("remote_controls_dismissed", true).apply()
        PlaybackCoordinator.observe(this, "notification", false)
        remote.snapshot = EchoState()
        remote.refresh()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(1002)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        PlaybackCoordinator.observe(this, "notification", false)
        remote.snapshot = EchoState()
        remote.refresh()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(1002)

        session?.let { removeSession(it); it.release() }; remote.release(); scope.cancel()
        super.onDestroy()
    }

    private inner class EchoPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        var snapshot = EchoState()
        fun refresh() = invalidateState()
        override fun getState(): State {
            val track = snapshot.track
            val queue = if (track == null) emptyList() else snapshot.queue.takeIf {
                snapshot.index in it.indices && it[snapshot.index].ytVideoId == track.ytVideoId
            } ?: listOf(track)
            val index = if (queue.isEmpty()) C.INDEX_UNSET else snapshot.index.takeIf { it in queue.indices } ?: 0
            val playlist = queue.mapIndexed { i, song ->
                val duration = if (i == index) snapshot.durationMs else song.durationSec * 1000L
                MediaItemData.Builder("$i:${song.ytVideoId ?: song.uuid}")
                    .setMediaItem(MediaItem.Builder().setMediaId(song.ytVideoId ?: song.uuid)
                        .setMediaMetadata(MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist)
                            .apply { com.example.juke.network.artworkCandidates(song.thumbnailUri.orEmpty(), song.ytVideoId, true).firstOrNull()?.takeIf(String::isNotBlank)?.let { com.example.juke.network.ArtworkRepository.register(it, song.ytVideoId); setArtworkUri(it.toUri()) } }.build()).build())
                    .setDurationUs(if (duration > 0) duration * 1000 else C.TIME_UNSET).build()
            }
            return State.Builder().setAvailableCommands(Player.Commands.Builder().addAll(
                Player.COMMAND_PLAY_PAUSE, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA).build())
                .setPlaylist(playlist).setCurrentMediaItemIndex(index)
                .setPlaybackState(if (track == null) Player.STATE_IDLE else Player.STATE_READY)
                .setPlayWhenReady(snapshot.playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setContentPositionMs { snapshot.livePosition() }
                .setDeviceInfo(DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).build()).build()
        }
        private fun send(action: suspend () -> Unit): ListenableFuture<*> {
            val result = SettableFuture.create<Void>()
            scope.launch {
                try { action(); refresh(); result.set(null) }
                catch (e: CancellationException) { result.cancel(false); throw e }
                catch (e: Exception) {
                    com.example.juke.network.NetworkFeedback.notify("Couldn't update Alexa playback")
                    result.setException(e)
                }
            }
            return result
        }
        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> =
            send { echo.command(if (playWhenReady) "play" else "pause") }
        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> = send {
            when (seekCommand) {
                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> echo.command("next")
                Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> echo.command("previous")
                else -> echo.seek(positionMs.coerceAtLeast(0))
            }
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
