package com.example.juke.services

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.example.juke.models.Track
import com.example.juke.utils.SafeLog as Log
import kotlinx.coroutines.*

/** The service owns this loop; Activity/notification controllers never renew phone leases. */
@UnstableApi
class PhonePlaybackSynchronizer(private val applicationContext: Context,
    private val serviceScope: CoroutineScope, private val player: Player,
    private val trackById: suspend (String) -> Track?, private val extendQueue: () -> Unit) {
    private val outputPrefs = applicationContext.getSharedPreferences("music_settings_prefs", Context.MODE_PRIVATE)
    private val TAG = "PhonePlaybackSync"
    fun start() {
        serviceScope.launch {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                delay(250)
                if (canContinueDownloadedOffline(com.example.juke.network.NetworkFeedback.online.value, player.currentMediaItem?.localConfiguration?.uri?.scheme) && PhonePlaybackOwnership.token.isNotBlank()) {
                    // An inaccessible server cannot renew a lease; completed downloads still play offline.
                    PhonePlaybackOwnership.forget(allowOffline = true)
                }
                if (PhonePlaybackOwnership.token.isNotBlank() && player.playWhenReady &&
                    android.os.SystemClock.elapsedRealtime() >= PhonePlaybackOwnership.leaseUntilMs) player.pause()
            }
        }
        // Ownership remains enforced by the foreground service when the Activity is closed.
        serviceScope.launch {
            var lastReport = 0L
            var lastOwnershipAttempt = 0L
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                delay(1_000)
                val claim = PhonePlaybackOwnership.token
                if (claim.isBlank()) continue
                if (PhonePlaybackOwnership.localHandoff) {
                    // Keep a preparing phone target's lease alive, without publishing the
                    // paused source cursor over the queue being installed on Alexa.
                    try {
                        val renewed = kotlinx.coroutines.withTimeoutOrNull(1_500) {
                            com.example.juke.network.AlexaBackendApi.phoneOutputRequest("heartbeat", PhonePlaybackOwnership.ownerId, claim)
                        }
                        if (PhonePlaybackOwnership.token == claim && renewed != null) {
                            if (renewed.belongsToPhone(PhonePlaybackOwnership.ownerId, claim)) PhonePlaybackOwnership.accept(renewed)
                            else player.pause()
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { /* Existing lease expiry still enforces exclusivity. */ }
                    continue
                }
                extendQueue()
                try {
                    val attemptAt = android.os.SystemClock.elapsedRealtime()
                    if (attemptAt - lastOwnershipAttempt < 2_000) continue
                    lastOwnershipAttempt = attemptAt
                    val status = kotlinx.coroutines.withTimeoutOrNull(1_500) {
                        try {
                            com.example.juke.network.AlexaBackendApi.phoneOutputRequest("heartbeat", PhonePlaybackOwnership.ownerId, claim)
                        } catch (e: com.example.juke.network.BackendHttpException) {
                            if (e.statusCode != 409) throw e
                            com.example.juke.network.AlexaBackendApi.phoneOutputStatus()
                        }
                    }
                    if (!canApplyPhoneOwnershipPoll(claim, PhonePlaybackOwnership.token, PhonePlaybackOwnership.localHandoff)) continue
                    if (status != null && !status.belongsToPhone(PhonePlaybackOwnership.ownerId, claim)) {
                        player.pause()
                        if (!PhonePlaybackOwnership.localHandoff && status.mode == "alexa") {
                            outputPrefs.edit().putString("playback_output", "ALEXA")
                                .apply { if (status.serial.isNotBlank()) putString("echo_serial", status.serial) }.apply()
                        }
                        PhonePlaybackOwnership.releaseTo(status)
                        if (!PhonePlaybackOwnership.localHandoff && status.mode == "alexa") {
                            val snapshot = com.example.juke.network.AlexaBackendApi.phoneQueueSnapshot()
                            RemotePlaybackService.start(applicationContext, parseEchoSnapshot(snapshot,
                                android.os.SystemClock.elapsedRealtime(), null, false), false)
                        }
                        continue
                    }
                    if (status != null) PhonePlaybackOwnership.accept(status)
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (status != null && now - lastReport >= 3_000) {
                        val renewed = status
                        if (renewed != null) {
                            if (!canApplyPhoneOwnershipPoll(claim, PhonePlaybackOwnership.token, PhonePlaybackOwnership.localHandoff)) continue
                            PhonePlaybackOwnership.accept(renewed)
                            lastReport = now
                            val mediaId = player.currentMediaItem?.mediaId
                            val current = mediaId?.let { trackById(it) }
                            if (player.currentMediaItem?.mediaId != mediaId || !canApplyPhoneOwnershipPoll(claim, PhonePlaybackOwnership.token, PhonePlaybackOwnership.localHandoff)) continue
                            current?.ytVideoId?.takeIf { it.isNotBlank() }?.let { video ->
                                kotlinx.coroutines.withTimeoutOrNull(1_500) {
                                    com.example.juke.network.AlexaBackendApi.updateQueue("current", video, emptyList(),
                                        player.isPlaying, player.currentPosition.coerceAtLeast(0), player.currentMediaItemIndex,
                                        buffering = player.playbackState == Player.STATE_BUFFERING, expectedToken = claim, currentEntryId = current.uuid)
                                }
                            }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) {
                    if (e is com.example.juke.network.BackendHttpException && e.statusCode == 409 && PhonePlaybackOwnership.token == claim)
                        PhonePlaybackOwnership.queueNeedsSync.tryEmit(Unit)
                    Log.w(TAG, "Playback ownership refresh failed: ${e.javaClass.simpleName}")
                }
                if (PhonePlaybackOwnership.token.isNotBlank() &&
                    android.os.SystemClock.elapsedRealtime() >= PhonePlaybackOwnership.leaseUntilMs) {
                    // During a partition, never keep streaming beyond our exclusive lease.
                    player.pause()
                }
            }
        }

    }
}
