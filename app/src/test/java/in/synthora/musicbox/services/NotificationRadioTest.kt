package `in`.synthora.musicbox.services

import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class NotificationRadioTest {
    @Test fun radioHasARealSessionActionAndFollowsNextWithoutMovingTransportButtons() {
        val previous = CommandButton.Builder().setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS).build()
        val next = CommandButton.Builder().setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT).build()
        val play = CommandButton.Builder().setPlayerCommand(Player.COMMAND_PLAY_PAUSE).build()
        val radio = NotificationRadio.button()
        assertEquals(NotificationRadio.ACTION, radio.sessionCommand?.customAction)
        assertEquals("Radio", radio.displayName.toString())
        assertEquals(listOf(previous, play, next, radio), notificationRadioButtons(listOf(radio, previous, play, next)))
    }
    @Test fun absentOrUnavailableNextNeverRemovesOtherControlsOrDuplicatesRadio() {
        val play = CommandButton.Builder().setPlayerCommand(Player.COMMAND_PLAY_PAUSE).build()
        val radio = NotificationRadio.button()
        assertEquals(listOf(play), notificationRadioButtons(listOf(play)))
        assertEquals(listOf(play, radio), notificationRadioButtons(listOf(radio, play)))
    }
}
