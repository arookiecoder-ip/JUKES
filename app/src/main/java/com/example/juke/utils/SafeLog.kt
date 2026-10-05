package com.example.juke.utils

import com.example.juke.network.Backend

/** Throwable traces are redacted as text too: nested HTTP errors often contain audio URLs. */
object SafeLog {
    private fun safe(message: String?, error: Throwable?) = redactSecrets(
        message.orEmpty() + (error?.let { "\n" + android.util.Log.getStackTraceString(it) } ?: ""), Backend.apiKey)
    fun d(tag: String, message: String?, error: Throwable? = null) = android.util.Log.d(tag, safe(message, error))
    fun i(tag: String, message: String?, error: Throwable? = null) = android.util.Log.i(tag, safe(message, error))
    fun w(tag: String, message: String?, error: Throwable? = null) = android.util.Log.w(tag, safe(message, error))
    fun e(tag: String, message: String?, error: Throwable? = null) = android.util.Log.e(tag, safe(message, error))
}
