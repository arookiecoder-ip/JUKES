package com.example.juke.network

import java.net.URI
import java.net.URLDecoder

data class YoutubeLink(val videoId: String? = null, val playlistId: String? = null)
fun youtubeLink(input: String): YoutubeLink? = runCatching {
    val uri = URI(input.trim())
    if (uri.scheme !in listOf("https", "http")) return@runCatching null
    val host = uri.host?.lowercase() ?: return@runCatching null
    if (host !in setOf("youtube.com", "www.youtube.com", "music.youtube.com", "m.youtube.com", "youtu.be", "www.youtu.be")) return@runCatching null
    val params = uri.rawQuery.orEmpty().split('&').associate { entry ->
        URLDecoder.decode(entry.substringBefore('='), "UTF-8") to URLDecoder.decode(entry.substringAfter('=', ""), "UTF-8")
    }
    val path = uri.path.orEmpty().trim('/').split('/')
    val video = if (host.endsWith("youtu.be")) path.firstOrNull() else params["v"]
        ?: if (path.firstOrNull() in setOf("shorts", "embed", "live")) path.getOrNull(1) else null
    if (video?.matches(Regex("[A-Za-z0-9_-]{11}")) == true) YoutubeLink(videoId = video)
    else params["list"]?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{2,200}")) }?.let { YoutubeLink(playlistId = it) }
}.getOrNull()
