package com.example.juke.services

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Skip Silence, edges only: drops silence at the start and end of a track and leaves silence in
 * the middle untouched (ExoPlayer's built-in skipSilence also cuts mid-song pauses).
 *
 * Leading: dropped until the first loud chunk. Trailing: silent chunks after that are held back;
 * a later loud chunk releases them (it was a mid-song gap), end-of-stream discards them.
 */
@UnstableApi
class EdgeSilenceProcessor : BaseAudioProcessor() {

    @Volatile var enabled = false

    private var leading = true
    private val held = ByteArrayOutputStream()
    private var heldFrames = 0L
    private var bytesPerFrame = 4
    private var sampleRate = 44100

    /** Frames dropped for good since the last flush; the sink adds this to the playback position. */
    @Volatile var skippedFrames = 0L
        private set

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        bytesPerFrame = inputAudioFormat.bytesPerFrame
        sampleRate = inputAudioFormat.sampleRate
        return inputAudioFormat
    }

    override fun onFlush() {
        leading = true
        held.reset()
        heldFrames = 0
        skippedFrames = 0
    }

    override fun onReset() = onFlush()

    override fun onQueueEndOfStream() {
        // Whatever silence is still held is the track's tail: drop it, and re-arm for the next track.
        skippedFrames += heldFrames
        held.reset()
        heldFrames = 0
        leading = true
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!enabled) {
            // Copy first: BaseAudioProcessor reuses its output buffer, which can be the very buffer
            // we were handed, and ByteBuffer.put(self) throws.
            val input = ByteArray(inputBuffer.remaining()).also { inputBuffer.get(it) }
            val out = replaceOutputBuffer(held.size() + input.size)
            if (held.size() > 0) { out.put(held.toByteArray()); held.reset(); heldFrames = 0 }
            out.put(input)
            out.flip()
            return
        }
        val chunkFrames = sampleRate / 100 // 10 ms
        val chunkBytes = chunkFrames * bytesPerFrame
        val result = ByteArrayOutputStream()
        val chunk = ByteArray(chunkBytes)
        while (inputBuffer.remaining() > 0) {
            val n = minOf(chunkBytes, inputBuffer.remaining())
            inputBuffer.get(chunk, 0, n)
            val frames = n / bytesPerFrame
            if (isSilent(chunk, n)) {
                if (leading) {
                    skippedFrames += frames
                } else {
                    held.write(chunk, 0, n)
                    heldFrames += frames
                    if (heldFrames > sampleRate * 20L) { // very long gap: it is mid-song, release it
                        result.write(held.toByteArray()); held.reset(); heldFrames = 0
                    }
                }
            } else {
                leading = false
                if (held.size() > 0) { result.write(held.toByteArray()); held.reset(); heldFrames = 0 }
                result.write(chunk, 0, n)
            }
        }
        val out = replaceOutputBuffer(result.size())
        out.put(result.toByteArray())
        out.flip()
    }

    private fun isSilent(bytes: ByteArray, n: Int): Boolean {
        val bb = ByteBuffer.wrap(bytes, 0, n).order(java.nio.ByteOrder.nativeOrder())
        while (bb.remaining() >= 2) if (abs(bb.short.toInt()) > THRESHOLD) return false
        return true
    }

    private companion object {
        const val THRESHOLD = 512 // about -36 dBFS
    }
}

/** Default chain (speed/pitch via Sonic) plus our processors; reports edge-skipped frames to the sink. */
@UnstableApi
class JukeAudioChain(
    private val edge: EdgeSilenceProcessor,
    vararg processors: AudioProcessor
) : DefaultAudioSink.DefaultAudioProcessorChain(edge, *processors) {
    override fun getSkippedOutputFrameCount(): Long = super.getSkippedOutputFrameCount() + edge.skippedFrames
}
