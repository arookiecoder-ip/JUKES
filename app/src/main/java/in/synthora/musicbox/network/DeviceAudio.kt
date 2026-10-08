package `in`.synthora.musicbox.network

/** Media3 needs a completed, seekable file; a live pipe has no stable length or byte ranges. */
fun deviceAudioUrl(baseUrl: String, videoId: String): String {
    require(videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) { "Invalid YouTube video id" }
    return "${baseUrl.trimEnd('/')}/audio/?video_id=$videoId&wait=1"
}

// Cold requests include server extraction/download time before response headers arrive.
// Keep this separate from the short TCP connection timeout; cached songs still start immediately.
const val DEVICE_AUDIO_READ_TIMEOUT_MS = 180_000

/** Match the configured audio endpoint, including reverse-proxy prefixes and default ports. */
fun isBackendAudioRequest(baseUrl: String, requestUrl: String): Boolean = runCatching {
    val base = java.net.URI(baseUrl)
    val request = java.net.URI(requestUrl)
    fun java.net.URI.effectivePort(): Int = if (port != -1) port else when (scheme?.lowercase()) {
        "https" -> 443
        "http" -> 80
        else -> -1
    }
    !base.host.isNullOrBlank() && base.scheme?.lowercase() in setOf("http", "https") &&
        base.scheme.equals(request.scheme, ignoreCase = true) &&
        base.host.equals(request.host, ignoreCase = true) &&
        base.effectivePort() == request.effectivePort() &&
        request.path == base.path.orEmpty().trimEnd('/') + "/audio/"
}.getOrDefault(false)
