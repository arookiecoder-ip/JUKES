package `in`.synthora.musicbox.services

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class PlaybackErrorRecorderTest {
    @Test fun realErrorAutomaticallySavesParserAndPlaybackHistory(): Unit = kotlinx.coroutines.runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.filesDir, "last-audio-error.txt")
        file.delete()
        PlaybackErrorRecorder.init(context)
        PlaybackDiagnostics.recordPlaybackEvent("error", 3003, 1, true, 129710, 129710, true)
        PlaybackDiagnostics.recordTransferFailure("cache_fallback", 3460790, 2097275, 100)
        val parser = androidx.media3.common.ParserException.createForMalformedContainer("Invalid atom size 1234", null)
        PlaybackErrorRecorder.capture(androidx.media3.common.PlaybackException("parse", parser, 3003))
        val report = kotlinx.coroutines.withTimeout(5000) {
            var saved = PlaybackErrorRecorder.savedReport(context)
            while (!saved.contains("Invalid atom size 1234")) {
                kotlinx.coroutines.delay(20)
                saved = PlaybackErrorRecorder.savedReport(context)
            }
            saved
        }
        assertTrue(report.contains("positionMs=129710"))
        assertTrue(report.contains("requestPosition=100"))
        assertTrue(report.contains("Version:"))
        file.delete()
        Unit
    }
    @Test fun persistedReportSurvivesReaderRecreationAndIsBounded() {
        val file = File(RuntimeEnvironment.getApplication().filesDir, "recorder-test-${System.nanoTime()}")
        try {
            PlaybackErrorRecorder.writeReport(file, "a".repeat(30_000))
            assertEquals("a".repeat(24_000), PlaybackErrorRecorder.readReport(File(file.absolutePath)))
            PlaybackErrorRecorder.writeReport(file, "new error")
            assertEquals("new error", PlaybackErrorRecorder.readReport(File(file.absolutePath)))
        } finally { file.delete() }
    }
    @Test fun missingReportAndFailedWriteDoNotThrow() {
        val parent = File(RuntimeEnvironment.getApplication().filesDir, "blocked-${System.nanoTime()}")
        parent.writeText("not a directory")
        try {
            val file = File(parent, "report")
            PlaybackErrorRecorder.writeReport(file, "error")
            assertEquals("(none recorded)", PlaybackErrorRecorder.readReport(file))
        } finally { parent.delete() }
    }
    @Test fun parserMessageRetainsUsefulNumbersAndRemovesCredentialsAndLocations() {
        val result = PlaybackErrorRecorder.safeParserMessage(
            "Invalid atom size 1234 https://example.com/song?key=secret /data/user/0/private/file secret\nend", "secret")
        assertTrue(result.contains("Invalid atom size 1234"))
        assertFalse(result.contains("secret"))
        assertFalse(result.contains("example.com"))
        assertFalse(result.contains("/data/"))
        assertFalse(result.contains('\n'))
        assertTrue(PlaybackErrorRecorder.safeParserMessage("x".repeat(4000)).length <= 600)
    }
}
