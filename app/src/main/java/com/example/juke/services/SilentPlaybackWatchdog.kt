package com.example.juke.services

import android.media.AudioManager
import android.media.audiofx.Visualizer
import androidx.media3.exoplayer.ExoPlayer
import com.example.juke.utils.SafeLog as Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Peak deviation of an 8-bit Visualizer waveform from digital silence (128).
 * Waveform bytes are unsigned 0-255 held in signed Bytes, so mask before
 * comparing (a silent 128 arrives as -128). Pure so it can be unit-tested
 * without an audio session.
 */
fun waveformPeakDeviation(waveform: ByteArray): Int {
    var peak = 0
    for (sample in waveform) {
        val deviation = abs((sample.toInt() and 0xFF) - 128)
        if (deviation > peak) peak = deviation
    }
    return peak
}

/**
 * Detects "progress moves but no sound" on phone playback and recovers without
 * an app restart.
 *
 * The player can reach a state where it consumes buffers and advances position
 * while nothing audible comes out (wedged renderer/DSP path, stuck route). The
 * only remedy used to be killing the app. This samples the player's own audio
 * session waveform shortly after play starts: if the position advances while
 * the session stays digitally silent, the first strike re-prepares the player
 * in place (same position); if it is still silent afterwards the user gets a
 * toast pointing at Bluetooth/output instead of silence.
 *
 * The Visualizer lives only for the few seconds of a check, so there is no
 * steady battery cost. Checks are skipped while muted, in a call, or when the
 * session/effects state can't be read (treated as unknown, never as silent).
 */
class SilentPlaybackWatchdog(
    private val scope: CoroutineScope,
    private val audioManager: AudioManager,
    private val player: ExoPlayer,
    /** True while a sound check is meaningful (phone output, no call, volume up). */
    private val eligible: () -> Boolean,
    /** (trackId, firstStrike): recover in place on the first strike, toast on the second. */
    private val onSilent: (trackId: String, firstStrike: Boolean) -> Unit
) {
    private var checkJob: Job? = null
    private var lastTrackId: String? = null
    private var strikes = 0

    /** (Re)starts one sound check for the current track; a no-op after two strikes. */
    fun notePlaying() {
        val trackId = player.currentMediaItem?.mediaId ?: return
        if (trackId != lastTrackId) {
            lastTrackId = trackId
            strikes = 0
            checkJob?.cancel()
        }
        if (strikes >= MAX_STRIKES) return
        checkJob?.cancel()
        checkJob = scope.launch { runCheck(trackId) }
    }

    fun cancel() {
        checkJob?.cancel()
        checkJob = null
    }

    private suspend fun runCheck(trackId: String) {
        try {
            // Give the sink a moment to start flowing before sampling.
            delay(SETTLE_MS)
            if (!eligible() || !player.isPlaying || player.currentMediaItem?.mediaId != trackId) return
            val sessionId = player.audioSessionId
            if (sessionId == 0) return
            val silent = sampleSilence(sessionId, trackId) ?: return
            if (!silent) return
            if (!player.isPlaying || player.currentMediaItem?.mediaId != trackId) return
            strikes++
            Log.w(TAG, "Silent playback detected for $trackId (strike $strikes)")
            onSilent(trackId, strikes <= 1)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            Log.w(TAG, "Silence check failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * True when the session waveform stays flat while the position advances;
     * null when the check itself couldn't run (unknown, not silent).
     */
    private suspend fun sampleSilence(sessionId: Int, trackId: String): Boolean? {
        val visualizer = try {
            Visualizer(sessionId)
        } catch (_: Exception) {
            return null
        }
        try {
            visualizer.captureSize = Visualizer.getCaptureSizeRange()[0]
            visualizer.enabled = true
            var peak = 0
            var captures = 0
            val startPosition = player.currentPosition
            repeat(SAMPLES) {
                delay(SAMPLE_MS)
                if (!player.isPlaying || player.currentMediaItem?.mediaId != trackId) return null
                val capture = ByteArray(visualizer.captureSize)
                if (visualizer.getWaveForm(capture) == Visualizer.SUCCESS) {
                    captures++
                    val samplePeak = waveformPeakDeviation(capture)
                    if (samplePeak > peak) peak = samplePeak
                }
            }
            val advancedMs = player.currentPosition - startPosition
            Log.d(TAG, "Silence check: peak=$peak advancedMs=$advancedMs")
            // Genuinely quiet intros stay under the peak only if they are also
            // digital silence; anything audible trips the threshold fast.
            if (captures < SAMPLES / 2) return null
            return peak < SOUND_THRESHOLD && advancedMs >= MIN_ADVANCE_MS
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { visualizer.enabled = false }
            runCatching { visualizer.release() }
        }
    }

    private companion object {
        const val TAG = "SilentPlayback"
        const val MAX_STRIKES = 2
        const val SETTLE_MS = 3_000L
        const val SAMPLES = 8
        const val SAMPLE_MS = 250L
        const val MIN_ADVANCE_MS = 1_500L
        /** Waveform units (0-128 scale): anything audible exceeds this quickly. */
        const val SOUND_THRESHOLD = 8
    }
}
