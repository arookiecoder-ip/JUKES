package `in`.synthora.musicbox.services

import android.app.Application
import android.app.Notification
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class SessionPlaybackResumptionNotificationTest {
    @Test fun failedBackgroundPlayHasTitleExplanationAndAnActionToOpenTheApp() {
        val notification = resumptionFailureNotification(RuntimeEnvironment.getApplication(), "Saved song", "Connect to the internet to resume it.")
        assertEquals("Saved song", notification.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertEquals("Connect to the internet to resume it.", notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertNotNull(notification.contentIntent)
        assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertFalse(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    @Test fun emptySavedMetadataDoesNotCreateABlankNotification() {
        for (title in listOf(null, "", " ")) {
            val notification = resumptionFailureNotification(RuntimeEnvironment.getApplication(), title, "Choose a song in Music Box.")
            assertEquals("Music Box", notification.extras.getCharSequence(Notification.EXTRA_TITLE))
        }
    }
}
