package com.example.juke.services

import com.example.juke.models.Track

/** Progress ticks reuse row objects; queue edits and account changes invalidate them. */
internal class TrackQueuePresentation {
    private var source: List<Track>? = null
    private var likes: Set<String>? = null
    private var current: Track? = null
    private var result = emptyList<Track>()
    fun present(queue: List<Track>, liked: Set<String>? = null, active: Track? = null): List<Track> {
        if (queue === source && liked == likes && active == current) return result
        source = queue; likes = liked; current = active
        result = queue.map { original ->
            val track = if (active != null && original.uuid == active.uuid) active else original
            if (liked == null || track.isFavourite == (track.ytVideoId in liked)) track
            else track.copy(isFavourite = track.ytVideoId in liked)
        }
        return result
    }
}
