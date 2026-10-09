package `in`.synthora.musicbox.services

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

internal fun resumptionFailureNotification(context: android.content.Context, title: String?, message: String): android.app.Notification {
    val intent = android.content.Intent(context, `in`.synthora.musicbox.MainActivity::class.java)
        .putExtra("open_player", true)
    val action = android.app.PendingIntent.getActivity(context, 1004, intent,
        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
    return androidx.core.app.NotificationCompat.Builder(context, "media_playback")
        .setSmallIcon(`in`.synthora.musicbox.R.drawable.media3_notification_small_icon)
        .setContentTitle(title?.takeIf { it.isNotBlank() } ?: "Music Box")
        .setContentText(message).setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(message))
        .setContentIntent(action).setAutoCancel(true).setOnlyAlertOnce(true).build()
}

/** Route both forms of Play/Pause through the same preparation and cancellation path. */
@UnstableApi
internal class ResumableSessionPlayer(player: Player, private val onPlay: () -> Unit,
    private val onPause: () -> Unit, private val onStop: () -> Unit) : ForwardingPlayer(player) {
    override fun play() = onPlay()
    override fun setPlayWhenReady(playWhenReady: Boolean) { if (playWhenReady) play() else pause() }
    override fun pause() = onPause()
    override fun stop() = onStop()
}

/** Main-thread command fence shared by metadata restoration and lease preparation. */
internal class ResumeCommandFence {
    private var generation = 0L
    var wantsPlay = false
        private set

    fun play(): Long {
        if (!wantsPlay) generation++
        wantsPlay = true
        return generation
    }

    fun pause() { generation++; wantsPlay = false }
    fun current(request: Long): Boolean = wantsPlay && generation == request
}

internal data class ResumeCursor(val index: Int, val positionMs: Long)

/** Preserve occurrence indices even when a queue contains the same song twice. */
internal fun resumeCursor(savedIndex: Int, savedPosition: Long, availableIndices: List<Int>,
    durationMs: Long, repeatOne: Boolean, repeatAll: Boolean): ResumeCursor {
    require(availableIndices.isNotEmpty()) { "Choose a song in Music Box to resume playback." }
    val index = availableIndices.indexOf(savedIndex)
    require(index >= 0) { "The previous song is unavailable. Open Music Box and choose a song." }
    val position = savedPosition.coerceAtLeast(0)
    if (durationMs > 0 && position >= durationMs) {
        return ResumeCursor(when {
            repeatOne -> index
            index < availableIndices.lastIndex -> index + 1
            repeatAll -> 0
            else -> index // An explicit Play can restart the last, already-ended song.
        }, 0L)
    }
    return ResumeCursor(index, position)
}

/** Never silently steal another device's live lease, including an unfinished handoff. */
internal fun blocksLocalResumption(output: SharedPlaybackOutput, owner: String): Boolean =
    output.handoffPending || output.mode == "alexa" ||
        (output.mode == "phone" && output.owner != owner && output.leaseMs > 0)
