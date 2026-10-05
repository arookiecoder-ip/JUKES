package com.example.juke.network

/** Match the mobile website: a song/audio upload takes precedence over a music video hero. */
fun isAudioSearchItem(item: BrowseItem): Boolean {
    if (item.kind != "track") return false
    val videoType = item.raw.text("videoType", "video_type")
    return videoType.contains("ATV", true) ||
        (!videoType.contains("OMV", true) && !videoType.contains("UGC", true) &&
            !item.raw.text("resultType", "result_type", "type").equals("video", true))
}

fun audioSearchHero(all: List<BrowseItem>, songs: List<BrowseItem>): BrowseItem? {
    val top = all.firstOrNull { it.raw.text("category").equals("Top result", true) && it.id.isNotBlank() }
        ?: all.firstOrNull { it.id.isNotBlank() } ?: songs.firstOrNull()
    if (top == null || top.kind != "track" || isAudioSearchItem(top)) return top
    fun normalize(value: String) = value.lowercase().replace(Regex("\\([^)]*\\)|\\[[^]]*\\]"), "")
        .replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()
    val audio = (songs + all).filter(::isAudioSearchItem).distinctBy { it.videoId }
    return audio.firstOrNull { normalize(it.title) == normalize(top.title) &&
        (top.subtitle.isBlank() || it.subtitle.isBlank() || normalize(it.subtitle) == normalize(top.subtitle)) }
        ?: audio.firstOrNull { normalize(it.title) == normalize(top.title) }
        ?: audio.firstOrNull() ?: top
}
