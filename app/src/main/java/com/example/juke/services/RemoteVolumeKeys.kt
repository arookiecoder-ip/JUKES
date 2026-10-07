package com.example.juke.services

/** A five-percent step, shared by key repeats and bounded at the device limits. */
internal fun remoteVolumeStep(current: Int, direction: Int): Int =
    (current.coerceIn(0, 100) + direction.coerceIn(-1, 1) * 5).coerceIn(0, 100)
