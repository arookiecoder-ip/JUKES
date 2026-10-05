package com.example.juke.utils

/** Media3 TIME_UNSET and invalid lengths must never become a visible negative timer. */
fun playbackTime(milliseconds: Long): String {
    if (milliseconds <= 0) return "0:00"
    val seconds = milliseconds / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
