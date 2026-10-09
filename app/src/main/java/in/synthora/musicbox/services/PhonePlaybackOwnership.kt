package `in`.synthora.musicbox.services

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import `in`.synthora.musicbox.network.AlexaBackendApi
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
    @Volatile var remoteControlled = false
        private set
    @Volatile private var relinquishing = false
    @Volatile private var handingOff = false
    private val preparation = PhonePreparationGuard()
    private val serverHealth = OwnershipServerHealth()
    val serverUnavailable: Boolean get() = serverHealth.unavailable(token)
    fun ownershipRequestFailed(expectedToken: String, unavailable: Boolean) =
        serverHealth.failed(expectedToken, token, unavailable)
    fun permitsLocalContinuation(expectedToken: String = token): Boolean =
        !relinquishing && expectedToken == token && !remoteControlled && !localHandoff
    var localHandoff: Boolean
        get() = handingOff || preparation.active
        set(value) { handingOff = value }
    val preparingSong: Boolean get() = preparation.active
    fun beginSongPreparation() = preparation.begin()
    fun endSongPreparation() = preparation.end()
    private data class PendingPauseAck(val token: String, val position: Long?, val playing: Boolean?)
    private var pendingPauseAck: PendingPauseAck? = null
    val ownerId: String get() = prefs.getString("owner_id", "").orEmpty()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences("shared_playback_output", Context.MODE_PRIVATE)
        val androidId = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        if (!androidId.isNullOrBlank()) {
            // A restored backup must not make two phones share one playback identity.
            val id = UUID.nameUUIDFromBytes("${context.packageName}:$androidId".toByteArray()).toString()
            if (prefs.getString("owner_id", "") != id) prefs.edit().putString("owner_id", id).remove("last_token").apply()
        } else if (prefs.getString("owner_id", "").isNullOrBlank()) {
            prefs.edit().putString("owner_id", UUID.randomUUID().toString()).apply()
        }
        // A restored queue is not a lease. Reconcile with the server before claiming anything.
    }

    suspend fun claim(serial: String) {
        val output = AlexaBackendApi.phoneOutputRequest("claim", ownerId, serial = serial)
        accept(output)
    }

    fun accept(output: SharedPlaybackOutput) {
        check(output.mode == "phone" && output.owner == ownerId && output.token.isNotBlank() && !output.handoffPending)
        val current = MobileDeviceConnection.rememberOutput(output)
        check(current.token == output.token && !output.olderThan(current)) { "A newer device switch superseded this lease." }
        serverHealth.clear()
        token = output.token
        remoteControlled = output.controller.isNotBlank() && output.controller != ownerId
        relinquishing = false
        // Pause before the server lease expires, leaving room for a slow status request.
        leaseUntilMs = SystemClock.elapsedRealtime() + (output.leaseMs - 4_000).coerceAtLeast(0)
        prefs.edit().putString("last_token", token).apply()
        remote.value = null
    }

    fun restoreIfCurrent(output: SharedPlaybackOutput): Boolean {
        if (output.mode != "phone" || output.owner != ownerId || output.token != prefs.getString("last_token", "") || output.leaseMs <= 0 || output.handoffPending) return false
        val latest = MobileDeviceConnection.rememberOutput(output)
        if (latest.token != output.token || output.olderThan(latest)) return false
        accept(output)
        return true
    }

    suspend fun releaseTo(output: SharedPlaybackOutput, positionMs: Long? = null, playing: Boolean? = null) {
        relinquishing = true
        serverHealth.clear()
        token = ""
        leaseUntilMs = 0
        // Call only after the actual Media3 player has paused.
        pendingPauseAck = PendingPauseAck(output.token, positionMs, playing)
        try { retryPauseAcknowledgment(output) }
        finally { if (token.isBlank()) remote.value = output }
    }

    suspend fun retryPauseAcknowledgment(output: SharedPlaybackOutput) {
        val pending = pendingPauseAck ?: return
        // Never let a late source cursor overwrite a target that already started.
        if (output.token != pending.token || !output.handoffPending || output.owner == ownerId) {
            pendingPauseAck = null
            return
        }
        try {
            val acknowledged = kotlinx.coroutines.withTimeoutOrNull(2_000) {
                AlexaBackendApi.phoneOutputRequest("ack", ownerId, pending.token,
                    positionMs = pending.position, playing = pending.playing)
            }
            if (acknowledged != null && pendingPauseAck == pending) pendingPauseAck = null
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { /* Presence retries while this exact handoff is pending. */ }
    }

    fun permitsPlayback(expectedToken: String = token): Boolean =
        canStartPhonePlayback(expectedToken, token, relinquishing, leaseUntilMs, SystemClock.elapsedRealtime())

    fun forget(allowOffline: Boolean = false) {
        serverHealth.clear()
        relinquishing = !allowOffline
        remoteControlled = false
        token = ""
        leaseUntilMs = 0
    }
}
