package com.example.juke.utils

import android.content.Context
import android.os.Build
import com.example.juke.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private val exportedPrefs = listOf("music_settings_prefs", "ui_prefs", "power_prefs")

object Diagnostics {

    /** Plain-text report for bug reports: versions, settings and recent app log lines. */
    suspend fun report(context: Context): String = withContext(Dispatchers.IO) {
        buildString {
            appendLine("JUKE diagnostics")
            appendLine("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine()
            appendLine("Settings:")
            exportedPrefs.forEach { name ->
                context.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (k, v) -> appendLine("  $name.$k = $v") }
            }
            appendLine()
            appendLine("Recent log lines:")
            appendLine(recentLog())
        }.take(200_000)
    }

    /** App settings as JSON. Contains no credentials; the library lives in the account. */
    suspend fun backupJson(context: Context): String = withContext(Dispatchers.IO) {
        val root = JSONObject()
        root.put("app", "JUKE")
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
            arrayOf("logcat", "-d", "-t", "300", "--pid=${android.os.Process.myPid()}")
        )
        process.inputStream.bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        "(log unavailable: ${e.message})"
    }
}
