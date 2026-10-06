package com.example.juke.services

/** Bounded aggregate measurements. No URIs, song names, tokens, headers or account identifiers. */
object PlaybackDiagnostics {
    enum class Stage { SERVER_REQUEST, AUDIO_CONNECT, FIRST_BYTE, HANDOFF, RECOVERY }
    data class Measurement(val count: Long, val failures: Long, val totalMs: Long, val maxMs: Long)
    private val values = mutableMapOf<Stage, Measurement>()
    @Synchronized fun record(stage: Stage, elapsedMs: Long, failed: Boolean = false) {
        val previous = values[stage] ?: Measurement(0, 0, 0, 0)
        val elapsed = elapsedMs.coerceAtLeast(0)
        values[stage] = Measurement(previous.count + 1, previous.failures + if (failed) 1 else 0,
            previous.totalMs + elapsed, maxOf(previous.maxMs, elapsed))
    }
    @Synchronized fun snapshot(): Map<Stage, Measurement> = values.toMap()
}
