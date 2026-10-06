package com.example.juke.services

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.example.juke.network.AlexaBackendApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Shared with the foreground playback service, so handoffs work after the Activity closes. */
object PhonePlaybackOwnership {
    val queueNeedsSync = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private lateinit var prefs: SharedPreferences
    private val remote = MutableStateFlow<SharedPlaybackOutput?>(null)
    val remoteOutput = remote.asStateFlow()
    @Volatile var token = ""
        private set
    @Volatile var leaseUntilMs = 0L
        private set
    @Volatile private var relinquishing = false
    @Volatile var localHandoff = false
    val ownerId: String get() = prefs.getString("owner_id", "").orEmpty()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences("shared_playback_output", Context.MODE_PRIVATE)
        if (prefs.getString("owner_id", "").isNullOrBlank()) prefs.edit().putString("owner_id", UUID.randomUUID().toString()).apply()
        // A restored queue is not a lease. Reconcile with the server before claiming anything.
    }

    suspend fun claim(serial: String) {
        val output = AlexaBackendApi.phoneOutputRequest("claim", ownerId, serial = serial)
        accept(output)
    }

    fun accept(output: SharedPlaybackOutput) {
        check(output.mode == "phone" && output.owner == ownerId && output.token.isNotBlank())
        token = output.token
        relinquishing = false
        // Pause before the server lease expires, leaving room for a slow status request.
        leaseUntilMs = SystemClock.elapsedRealtime() + (output.leaseMs - 4_000).coerceAtLeast(0)
        prefs.edit().putString("last_token", token).apply()
        remote.value = null
    }

    fun restoreIfCurrent(output: SharedPlaybackOutput): Boolean {
        if (output.mode != "phone" || output.owner != ownerId || output.token != prefs.getString("last_token", "") || output.leaseMs <= 0) return false
        accept(output)
        return true
    }

    suspend fun releaseTo(output: SharedPlaybackOutput) {
        relinquishing = true
        token = ""
        leaseUntilMs = 0
        // Call only after the actual Media3 player has paused.
        try { AlexaBackendApi.phoneOutputRequest("ack", ownerId, output.token) }
        finally { if (token.isBlank()) remote.value = output }
    }

    fun permitsPlayback(expectedToken: String = token): Boolean =
        canStartPhonePlayback(expectedToken, token, relinquishing, leaseUntilMs, SystemClock.elapsedRealtime())

    fun forget(allowOffline: Boolean = false) {
        relinquishing = !allowOffline
        token = ""
        leaseUntilMs = 0
    }
}
