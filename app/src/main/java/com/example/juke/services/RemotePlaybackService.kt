package com.example.juke.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.widget.Toast
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
        echo = EchoController(scope, prefs,
            onError = { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }, onSignedOut = { stopSelf() })
        remote = EchoPlayer()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel("alexa_playback", "Alexa playback", NotificationManager.IMPORTANCE_LOW))
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this)
            .setNotificationId(1002).setChannelId("alexa_playback").build().apply { setSmallIcon(R.drawable.music_box_pwa) })
        session = MediaSession.Builder(this, remote).setId("alexa")
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
        scope.launch {
            echo.state.collect { remote.snapshot = it; remote.refresh(); updateLike() }
        }
        scope.launch { AccountRepository.liked.collect { updateLike() } }
        echo.startPolling(foreground = false)
    }

    private fun updateLike() {
        session?.setCustomLayout(listOf(CommandButton.Builder()
            .setDisplayName("Like").setIconResId(if (AccountRepository.isLiked(echo.state.value.track?.ytVideoId))
                R.drawable.thumb_up_filled else R.drawable.thumb_up_outline)
            .setSessionCommand(SessionCommand(LIKE, Bundle.EMPTY)).build()))
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onDestroy() {
        echo.clear()
        session?.release(); remote.release(); scope.cancel()
        super.onDestroy()
    }

    private inner class EchoPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        var snapshot = EchoState()
        fun refresh() = invalidateState()
        override fun getState(): State {
            val track = snapshot.track
            val playlist = if (track == null) emptyList() else listOf(MediaItemData.Builder(track.ytVideoId ?: track.uuid)
                .setMediaItem(MediaItem.Builder().setMediaId(track.ytVideoId ?: track.uuid)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist)
                        .apply { track.thumbnailUri?.takeIf(String::isNotBlank)?.let { setArtworkUri(it.toUri()) } }.build()).build())
                .setDurationUs(if (snapshot.durationMs > 0) snapshot.durationMs * 1000 else C.TIME_UNSET).build())
            return State.Builder().setAvailableCommands(Player.Commands.Builder().addAll(
                Player.COMMAND_PLAY_PAUSE, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA).build())
                .setPlaylist(playlist).setCurrentMediaItemIndex(if (track == null) C.INDEX_UNSET else 0)
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
                    Toast.makeText(this@RemotePlaybackService, "Couldn't update Alexa playback", Toast.LENGTH_SHORT).show()
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
        fun stop(context: Context) { context.stopService(Intent(context, RemotePlaybackService::class.java)) }
        fun start(context: Context) { context.startService(Intent(context, RemotePlaybackService::class.java)) }
    }
}
