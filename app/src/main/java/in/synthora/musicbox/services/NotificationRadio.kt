package `in`.synthora.musicbox.services

import android.content.Context
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import com.google.common.collect.ImmutableList

/** Both notification sessions use the application-owned model and its existing output routing. */
@UnstableApi
internal object NotificationRadio {
    const val ACTION = "MUSIC_BOX_RADIO"
    fun button(): CommandButton = CommandButton.Builder(CommandButton.ICON_RADIO)
        .setDisplayName("Radio").setCustomIconResId(`in`.synthora.musicbox.R.drawable.ic_notification_radio)
        .setSessionCommand(SessionCommand(ACTION, Bundle.EMPTY))
        .setSlots(CommandButton.SLOT_FORWARD_SECONDARY).build()

    fun start(context: Context): Boolean {
        if (!`in`.synthora.musicbox.network.NetworkFeedback.online.value) {
            `in`.synthora.musicbox.network.NetworkFeedback.notify("Connect to the internet to start radio.")
            return false
        }
        val model = (context.applicationContext as `in`.synthora.musicbox.JukeApplication).playbackModel()
        if (model.uiState.value.currentTrack?.ytVideoId.isNullOrBlank()) return false
        model.startRadio()
        return true
    }
}

/** Keep Radio immediately after Next in the expanded notification on older Android versions. */
@UnstableApi
internal class MusicNotificationProvider(context: Context, id: Int, channel: String) :
    DefaultMediaNotificationProvider(context, { id }, channel, DefaultMediaNotificationProvider.DEFAULT_CHANNEL_NAME_RESOURCE_ID) {
    override fun getMediaButtons(session: MediaSession, commands: Player.Commands,
        customLayout: ImmutableList<CommandButton>, showPauseButton: Boolean): ImmutableList<CommandButton> {
        return ImmutableList.copyOf(notificationRadioButtons(super.getMediaButtons(session, commands, customLayout, showPauseButton)))
    }
}

@UnstableApi
internal fun notificationRadioButtons(original: List<CommandButton>): List<CommandButton> {
    val buttons = original.toMutableList()
    val radio = buttons.firstOrNull { it.sessionCommand?.customAction == NotificationRadio.ACTION } ?: return buttons
    buttons.remove(radio)
    val next = buttons.indexOfLast { it.playerCommand == Player.COMMAND_SEEK_TO_NEXT || it.playerCommand == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM }
    buttons.add(if (next >= 0) next + 1 else buttons.size, radio)
    return buttons
}
