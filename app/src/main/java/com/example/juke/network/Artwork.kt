package com.example.juke.network

/** Upgrade only known YouTube image sizing suffixes; preserve other/signed image URLs. */
fun largeArtworkUrl(url: String): String {
    val host = runCatching { java.net.URI(url).host }.getOrNull().orEmpty()
    if (host == "lh3.googleusercontent.com" || host.endsWith(".ggpht.com")) {
        return url.replace(Regex("=w\\d+-h\\d+[^?]*$"), "=w1200-h1200-l90-rj")
    }
    if (host == "i.ytimg.com" || host.endsWith(".ytimg.com")) {
        return url.replace(Regex("/(default|mqdefault|sddefault)\\.jpg(?=\\?|$)"), "/hqdefault.jpg")
    }
    return url
}
