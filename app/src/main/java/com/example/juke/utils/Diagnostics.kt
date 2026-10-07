package com.example.juke.utils

import android.content.Context
import android.os.Build
import com.example.juke.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private val exportedPrefs = listOf("music_settings_prefs", "ui_prefs", "power_prefs")

object Diagnostics {
    private const val CRASH_FILE = "last-playback-crash.txt"

    /** Preserve the fatal stack across process death; retain Android's normal crash handling. */
    fun installCrashRecorder(context: Context) {
        val file = java.io.File(context.filesDir, CRASH_FILE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val key = runCatching { com.example.juke.network.Backend.apiKey }.getOrDefault("")
                file.writeText(redactSecrets("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                    "Time: ${java.util.Date()}\nThread: ${thread.name}\n" + error.stackTraceToString(), key).take(64_000))
            }
            if (previous != null) previous.uncaughtException(thread, error)
            else { android.os.Process.killProcess(android.os.Process.myPid()); kotlin.system.exitProcess(10) }
        }
    }


    /** Plain-text report for bug reports: versions, settings and recent app log lines. */
    suspend fun report(context: Context): String = withContext(Dispatchers.IO) {
        buildString {
            appendLine("Music Box diagnostics")
            appendLine("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine()
            appendLine("Last saved crash:")
            appendLine(runCatching { java.io.File(context.filesDir, CRASH_FILE).takeIf { it.exists() }?.readText() }.getOrNull() ?: "(none recorded)")
            if (Build.VERSION.SDK_INT >= 30) {
                appendLine("Previous process exits:")
                runCatching { context.getSystemService(android.app.ActivityManager::class.java)
                    .getHistoricalProcessExitReasons(context.packageName, 0, 3).forEach {
                        appendLine("  reason=${it.reason}, status=${it.status}, time=${it.timestamp}, description=${it.description}")
                    } }
            }
            appendLine("Playback timings:")
            com.example.juke.services.PlaybackDiagnostics.snapshot().forEach { (stage, value) ->
                appendLine("  $stage: $value")
            }
            appendLine()
            appendLine("Settings:")
            exportedPrefs.forEach { name ->
                context.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (k, v) -> appendLine("  $name.$k = $v") }
            }
            appendLine()
            appendLine("Recent log lines:")
            appendLine(recentLog())
        }.let { redactSecrets(it, com.example.juke.network.Backend.apiKey) }.take(200_000)
    }

    /** App settings as JSON. Contains no credentials; the library lives in the account. */
    suspend fun backupJson(context: Context): String = withContext(Dispatchers.IO) {
        val root = JSONObject()
        root.put("app", "Music Box")
        root.put("version", BuildConfig.VERSION_NAME)
        root.put("exportedAt", System.currentTimeMillis())

        val settings = JSONObject()
        exportedPrefs.forEach { name ->
            val o = JSONObject()
            context.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (k, v) -> o.put(k, v) }
            settings.put(name, o)
        }
        root.put("settings", settings)
        root.toString(2)
    }

    private fun recentLog(): String = try {
        val process = Runtime.getRuntime().exec(
            arrayOf("logcat", "-d", "-b", "main", "-b", "crash", "-t", "300", "--pid=${android.os.Process.myPid()}", "*:W")
        )
        process.inputStream.bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        "(log unavailable: ${e.message})"
    }
}
