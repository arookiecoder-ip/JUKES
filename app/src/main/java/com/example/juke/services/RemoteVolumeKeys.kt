package com.example.juke.services

/** A five-percent step, shared by key repeats and bounded at the device limits. */
internal fun remoteVolumeStep(current: Int, direction: Int): Int =
    (current.coerceIn(0, 100) + direction.coerceIn(-1, 1) * 5).coerceIn(0, 100)

/** Round to the nearest target stream step; truncation can discard a volume-up key. */
internal fun volumePercentToStreamIndex(percent: Int, maximum: Int): Int =
    ((percent.coerceIn(0, 100) * maximum.coerceAtLeast(1) + 50) / 100).coerceIn(0, maximum.coerceAtLeast(1))
