package `in`.synthora.musicbox.services

internal fun unheardQueueIds(existing: List<String>, radio: List<String>): List<String> {
    val played = existing.toSet()
    return radio.distinct().filter { it !in played }.take((5000 - existing.size).coerceAtLeast(0))
}

internal fun canApplyQueueContinuation(before: List<String>, now: List<String>, expectedToken: String,
    currentToken: String, phoneOwnsPlayback: Boolean): Boolean = phoneOwnsPlayback &&
    expectedToken.isNotBlank() && expectedToken == currentToken && before == now

internal fun shouldAdvanceExhaustedQueue(ended: Boolean, beforeIndex: Int, afterIndex: Int,
    playWhenReady: Boolean, hasNext: Boolean): Boolean = ended && beforeIndex == afterIndex && playWhenReady && hasNext
