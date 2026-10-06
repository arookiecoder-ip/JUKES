package com.example.juke.services

internal fun unheardQueueIds(existing: List<String>, radio: List<String>): List<String> =
    radio.distinct().filter { it !in existing.toSet() }.take((5000 - existing.size).coerceAtLeast(0))

internal fun canApplyQueueContinuation(before: List<String>, now: List<String>, expectedToken: String,
    currentToken: String, phoneOwnsPlayback: Boolean): Boolean = phoneOwnsPlayback &&
    expectedToken.isNotBlank() && expectedToken == currentToken && before == now
