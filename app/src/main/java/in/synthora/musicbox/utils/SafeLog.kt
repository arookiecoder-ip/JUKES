package `in`.synthora.musicbox.utils

import `in`.synthora.musicbox.network.Backend
import `in`.synthora.musicbox.BuildConfig

/** Throwable traces are redacted as text too: nested HTTP errors often contain audio URLs. */
object SafeLog {
    private fun safe(message: String?, error: Throwable?) = redactSecrets(
        message.orEmpty() + (error?.let { "\n" + android.util.Log.getStackTraceString(it) } ?: ""), Backend.apiKey)
    fun d(tag: String, message: String?, error: Throwable? = null) = if (BuildConfig.DEBUG) android.util.Log.d(tag, safe(message, error)) else 0
    fun i(tag: String, message: String?, error: Throwable? = null) = if (BuildConfig.DEBUG) android.util.Log.i(tag, safe(message, error)) else 0
    fun w(tag: String, message: String?, error: Throwable? = null) = if (BuildConfig.DEBUG) android.util.Log.w(tag, safe(message, error)) else 0
    fun e(tag: String, message: String?, error: Throwable? = null) = android.util.Log.e(tag, safe(message, error))
}
