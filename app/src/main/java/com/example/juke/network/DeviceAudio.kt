package com.example.juke.network

/** Media3 needs a completed, seekable file; a live pipe has no stable length or byte ranges. */
fun deviceAudioUrl(baseUrl: String, videoId: String): String {
    require(videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) { "Invalid YouTube video id" }
    return "${baseUrl.trimEnd('/')}/audio/?video_id=$videoId&wait=1"
}

// Cold requests include server extraction/download time before response headers arrive.
// Keep this separate from the short TCP connection timeout; cached songs still start immediately.
const val DEVICE_AUDIO_READ_TIMEOUT_MS = 180_000
