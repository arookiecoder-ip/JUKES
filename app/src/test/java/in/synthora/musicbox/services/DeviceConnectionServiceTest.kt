package `in`.synthora.musicbox.services

import android.app.Application
import android.app.Service
import android.content.Context
import android.content.Intent
import `in`.synthora.musicbox.network.Backend
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Real service lifecycle regression; no Activity, UI or emulator. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class DeviceConnectionServiceTest {
    @Test fun existingServicePromotesAgainAfterForegroundNotificationWasRemoved() {
        val app = RuntimeEnvironment.getApplication<Application>()
        app.getSharedPreferences("backend_session", Context.MODE_PRIVATE).edit()
            .putStringSet("cookies", setOf("https://alexa.synthora.in/\nsession=test; Path=/; Max-Age=3600"))
            .commit()
        Backend.init(app)
        assertTrue(Backend.hasSession())
        val controller = Robolectric.buildService(DeviceConnectionService::class.java).create()
        val service = controller.get()
        val intent = Intent(app, DeviceConnectionService::class.java)
        try {
            assertEquals(Service.START_STICKY, service.onStartCommand(intent, 0, 1))
            assertNotNull(shadowOf(service).lastForegroundNotification)
            service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
            assertNull(shadowOf(service).lastForegroundNotification)
            assertEquals(Service.START_STICKY, service.onStartCommand(intent, 0, 2))
            assertNotNull(shadowOf(service).lastForegroundNotification)
            Backend.clearSession()
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(intent, 0, 3))
            assertNull(shadowOf(service).lastForegroundNotification)
        } finally {
            Backend.clearSession()
            controller.destroy()
        }
    }
}
