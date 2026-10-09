package `in`.synthora.musicbox.services

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import `in`.synthora.musicbox.models.Track
import `in`.synthora.musicbox.utils.SafeLog as Log
import kotlinx.coroutines.*

/** The service owns this loop; Activity/notification controllers never renew phone leases. */
@UnstableApi
class PhonePlaybackSynchronizer(private val applicationContext: Context,
    private val serviceScope: CoroutineScope, private val player: Player,
    private val trackById: suspend (String) -> Track?, private val extendQueue: () -> Unit,
    private val pauseForLease: () -> Unit, private val leaseRenewed: () -> Unit,
    private val offlineAudioAvailable: () -> Boolean = { false },
    private val recoveryPending: () -> Boolean = { false },
    private val pauseForHandoff: suspend () -> Pair<Boolean, Long> = {
        val playing = player.playWhenReady
        val position = player.currentPosition.coerceAtLeast(0)
        player.pause()
        playing to position
    }) {
    private val outputPrefs = applicationContext.getSharedPreferences("music_settings_prefs", Context.MODE_PRIVATE)
    private val TAG = "PhonePlaybackSync"
    fun start() {
        serviceScope.launch {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                val leaseRemaining = PhonePlaybackOwnership.leaseUntilMs - android.os.SystemClock.elapsedRealtime()
                delay(if (PhonePlaybackOwnership.token.isBlank()) 2_000 else if (leaseRemaining <= 0) 1_000 else leaseRemaining.coerceIn(50, 1_000))
                // Retain the token so reconnect can reconcile a switch made during the outage.
                if (PhonePlaybackOwnership.token.isNotBlank() && player.playWhenReady &&
                    android.os.SystemClock.elapsedRealtime() >= PhonePlaybackOwnership.leaseUntilMs && !offlineAudioAvailable()) pauseForLease()
            }
        }
        // Ownership remains enforced by the foreground service when the Activity is closed.
        serviceScope.launch {
            var lastOwnershipAttempt = 0L
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                delay(1_000)
                val claim = PhonePlaybackOwnership.token
                if (claim.isBlank() || !`in`.synthora.musicbox.network.NetworkFeedback.online.value) continue
                if (PhonePlaybackOwnership.localHandoff) {
                    // Keep a preparing phone target's lease alive, without publishing the
                    // paused source cursor over the queue being installed on Alexa.
                    try {
                        val renewed = kotlinx.coroutines.withTimeoutOrNull(4_000) {
                            `in`.synthora.musicbox.network.AlexaBackendApi.phoneOutputRequest("heartbeat", PhonePlaybackOwnership.ownerId, claim)
                        }
                        if (PhonePlaybackOwnership.token == claim && renewed != null) {
                            if (renewed.belongsToPhone(PhonePlaybackOwnership.ownerId, claim)) PhonePlaybackOwnership.accept(renewed)
                            else player.pause()
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { /* Existing lease expiry still enforces exclusivity. */ }
                    continue
                }
                try {
                    if (player.playWhenReady) extendQueue()
                    val attemptAt = android.os.SystemClock.elapsedRealtime()
                    if (attemptAt - lastOwnershipAttempt < (if (player.playWhenReady || recoveryPending()) 2_000 else 5_000)) continue
                    lastOwnershipAttempt = attemptAt
                    val status = kotlinx.coroutines.withTimeoutOrNull(4_000) {
                        try {
                            `in`.synthora.musicbox.network.AlexaBackendApi.phoneOutputRequest("heartbeat", PhonePlaybackOwnership.ownerId, claim)
                        } catch (e: `in`.synthora.musicbox.network.BackendHttpException) {
                            if (e.statusCode != 409) throw e
                            `in`.synthora.musicbox.network.AlexaBackendApi.phoneOutputStatus()
                        }
                    }
                    if (!canApplyPhoneOwnershipPoll(claim, PhonePlaybackOwnership.token, PhonePlaybackOwnership.localHandoff)) continue
                    if (status != null && !status.belongsToPhone(PhonePlaybackOwnership.ownerId, claim)) {
                        // A lease/network pause is not a user pause. Preserve
                        // the original play intent when acknowledging a handoff.
                        val (sourcePlaying, sourcePosition) = pauseForHandoff()
                        if (!PhonePlaybackOwnership.localHandoff && status.mode == "alexa") {
                            outputPrefs.edit().putString("playback_output", "ALEXA")
                                .apply { if (status.serial.isNotBlank()) putString("echo_serial", status.serial) }.apply()
                        }
                        PhonePlaybackOwnership.releaseTo(status, sourcePosition, sourcePlaying)
                        if (!PhonePlaybackOwnership.localHandoff && status.mode == "alexa") {
                            val snapshot = `in`.synthora.musicbox.network.AlexaBackendApi.phoneQueueSnapshot()
                            RemotePlaybackService.start(applicationContext, parseEchoSnapshot(snapshot,
                                android.os.SystemClock.elapsedRealtime(), null, false), false)
                        }
                        continue
                    }
                    if (status != null) { PhonePlaybackOwnership.accept(status); leaseRenewed() }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) {
                    if (e is `in`.synthora.musicbox.network.BackendHttpException && e.statusCode == 409 && PhonePlaybackOwnership.token == claim)
                        PhonePlaybackOwnership.queueNeedsSync.tryEmit(Unit)
                    Log.w(TAG, "Playback ownership refresh failed: ${e.javaClass.simpleName}")
                }
                if (PhonePlaybackOwnership.token.isNotBlank() &&
                    android.os.SystemClock.elapsedRealtime() >= PhonePlaybackOwnership.leaseUntilMs && !offlineAudioAvailable()) {
                    // During a partition, never keep streaming beyond our exclusive lease.
                    pauseForLease()
                }
            }
        }

        // A slow queue write must not occupy the heartbeat loop and expire an
        // otherwise healthy output lease. Publishing is independent and bounded.
        serviceScope.launch {
            var published: PublishedCursor? = null
            while (currentCoroutineContext().isActive) {
                delay(3_000)
                val claim = PhonePlaybackOwnership.token
                if (claim.isBlank() || PhonePlaybackOwnership.localHandoff || !PhonePlaybackOwnership.permitsPlayback(claim)) continue
                try {
                    val mediaId = player.currentMediaItem?.mediaId
                    val current = mediaId?.let { trackById(it) } ?: continue
                    if (player.currentMediaItem?.mediaId != mediaId ||
                        !canApplyPhoneOwnershipPoll(claim, PhonePlaybackOwnership.token, PhonePlaybackOwnership.localHandoff) ||
                        !PhonePlaybackOwnership.permitsPlayback(claim)) continue
                    val cursor = PublishedCursor(claim, current.uuid, player.isPlaying,
                        player.playbackState == Player.STATE_BUFFERING, player.currentPosition.coerceAtLeast(0),
                        android.os.SystemClock.elapsedRealtime())
                    if (!`in`.synthora.musicbox.network.NetworkFeedback.online.value || !cursor.needsPublication(published)) continue
                    current.ytVideoId?.takeIf { it.isNotBlank() }?.let { video ->
                        val completed = withTimeoutOrNull(4_000) {
                            `in`.synthora.musicbox.network.AlexaBackendApi.updateQueue("current", video, emptyList(),
                                player.isPlaying, player.currentPosition.coerceAtLeast(0), player.currentMediaItemIndex,
                                buffering = cursor.buffering, expectedToken = claim, currentEntryId = current.uuid)
                            true
                        }
                        if (completed == true) published = cursor
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (e is `in`.synthora.musicbox.network.BackendHttpException && e.statusCode == 409 && PhonePlaybackOwnership.token == claim)
                        PhonePlaybackOwnership.queueNeedsSync.tryEmit(Unit)
                    Log.w(TAG, "Playback cursor publish failed: ${e.javaClass.simpleName}")
                }
            }
        }

    }
}
