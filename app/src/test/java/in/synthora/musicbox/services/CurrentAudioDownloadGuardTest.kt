package `in`.synthora.musicbox.services

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class CurrentAudioDownloadGuardTest {
    @Test fun currentCacheLockIsReleasedOnCompletionAndCloseIsIdempotent() {
        val guard = currentAudioDownloadGuard(RuntimeEnvironment.getApplication())
        val lock = ShadowPowerManager.getLatestWakeLock()
        assertTrue(lock.isHeld)
        guard.progress()
        guard.close()
        assertFalse(lock.isHeld)
        guard.close()
        assertFalse(lock.isHeld)
    }
}
