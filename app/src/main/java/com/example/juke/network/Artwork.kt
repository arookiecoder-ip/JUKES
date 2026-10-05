package com.example.juke.network

/** Upgrade known YouTube sizing suffixes without changing signed query parameters. */
fun largeArtworkUrl(url: String): String {
    val host = runCatching { java.net.URI(url).host }.getOrNull().orEmpty()
    if (host == "lh3.googleusercontent.com" || host.endsWith(".ggpht.com")) {
        return url.replace(Regex("=(?:w\\d+(?:-h\\d+)?|s\\d+)[^?]*?(?=\\?|$)"), "=w1200-h1200-l90-rj")
    }
    if (host == "i.ytimg.com" || host.endsWith(".ytimg.com")) {
        return url.replace(Regex("/(default|mqdefault|sddefault|hqdefault|hq720)\\.(jpg|webp)(?=\\?|$)"), "/maxresdefault.$2")
    }
    return url
}

fun artworkCandidates(source: String, videoId: String?, large: Boolean): List<String> {
    val original = source.ifBlank {
        videoId?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
            ?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }.orEmpty()
    }
    val upgraded = largeArtworkUrl(original)
    val alternatives = if (upgraded.contains("/maxresdefault."))
        listOf(upgraded.replace("/maxresdefault.", "/hq720.")) else emptyList()
    val videoArtwork = videoId?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }?.let {
        listOf("https://i.ytimg.com/vi/$it/maxresdefault.jpg", "https://i.ytimg.com/vi/$it/hq720.jpg")
    }.orEmpty()
    // Never fall back to SD/mq/default images. The renderer also checks decoded dimensions.
    return (listOf(upgraded) + alternatives + videoArtwork).filter { it.isNotBlank() }.distinct().ifEmpty { listOf("") }
}

fun isHdArtwork(width: Int, height: Int): Boolean = minOf(width, height) >= 720
