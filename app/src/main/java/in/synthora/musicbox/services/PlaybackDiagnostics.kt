package `in`.synthora.musicbox.services

/** Bounded aggregate measurements. No URIs, song names, tokens, headers or account identifiers. */
object PlaybackDiagnostics {
    enum class Stage { SERVER_REQUEST, AUDIO_CONNECT, FIRST_BYTE, HANDOFF, HANDOFF_STATE, PLAYER_READY, RECOVERY }
    data class Measurement(val count: Long, val failures: Long, val totalMs: Long, val maxMs: Long)
    data class AudioFailure(val code: Int, val causes: List<String>, val httpStatus: Int?)
    data class AudioResponse(val network: Boolean, val contentType: String?, val declaredBytes: Long, val rejected: Boolean)
    private val audioResponses = ArrayDeque<AudioResponse>()
    @Synchronized fun recordAudioResponse(network: Boolean, contentType: String?, declaredBytes: Long, rejected: Boolean) {
        if (audioResponses.size == 8) audioResponses.removeFirst()
        // Store a MIME token only, never parameters or arbitrary header text.
        val mime = contentType?.substringBefore(';')?.takeIf { it.matches(Regex("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+")) }
        audioResponses.addLast(AudioResponse(network, mime, declaredBytes, rejected))
    }
    @Synchronized fun recentAudioResponses(): List<AudioResponse> = audioResponses.toList()
    private val audioFailures = ArrayDeque<AudioFailure>()
    @Synchronized fun recordAudioFailure(code: Int, causes: List<String>, httpStatus: Int?) {
        if (audioFailures.size == 8) audioFailures.removeFirst()
        audioFailures.addLast(AudioFailure(code, causes.take(8), httpStatus))
    }
    @Synchronized fun recentAudioFailures(): List<AudioFailure> = audioFailures.toList()
    data class AudioTransfer(val kind: String, val expectedBytes: Long, val receivedBytes: Long)
    private val audioTransfers = ArrayDeque<AudioTransfer>()
    @Synchronized internal fun recordTransferFailure(kind: String, expected: Long, received: Long) {
        if (audioTransfers.size == 16) audioTransfers.removeFirst()
        audioTransfers.addLast(AudioTransfer(kind, expected, received))
    }
    @Synchronized fun recentAudioTransfers(): List<AudioTransfer> = audioTransfers.toList()
    data class PlaybackEvent(val timeMs: Long, val event: String, val reason: Int, val state: Int,
        val wantsPlay: Boolean, val positionMs: Long, val bufferedMs: Long, val fullyCached: Boolean)
    private val playbackEvents = ArrayDeque<PlaybackEvent>()
    @Synchronized internal fun recordPlaybackEvent(event: String, reason: Int, state: Int,
        wantsPlay: Boolean, position: Long, buffered: Long, cached: Boolean) {
        if (playbackEvents.size == 32) playbackEvents.removeFirst()
        playbackEvents.addLast(PlaybackEvent(android.os.SystemClock.elapsedRealtime(), event, reason, state,
            wantsPlay, position, buffered, cached))
    }
    @Synchronized fun recentPlaybackEvents(): List<PlaybackEvent> = playbackEvents.toList()
    private val values = mutableMapOf<Stage, Measurement>()
    @Synchronized fun record(stage: Stage, elapsedMs: Long, failed: Boolean = false) {
        val previous = values[stage] ?: Measurement(0, 0, 0, 0)
        val elapsed = elapsedMs.coerceAtLeast(0)
        values[stage] = Measurement(previous.count + 1, previous.failures + if (failed) 1 else 0,
            previous.totalMs + elapsed, maxOf(previous.maxMs, elapsed))
    }
    suspend fun <T> measure(stage: Stage, action: suspend () -> T): T {
        val start = System.nanoTime()
        var failed = true
        try { return action().also { failed = false } }
        finally { record(stage, (System.nanoTime() - start) / 1_000_000, failed) }
    }
    @Synchronized fun snapshot(): Map<Stage, Measurement> = values.toMap()
}
