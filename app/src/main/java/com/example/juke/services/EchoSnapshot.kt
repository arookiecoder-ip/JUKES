package com.example.juke.services

import android.os.SystemClock
import com.example.juke.models.Track
import com.example.juke.network.BrowseParser
import com.example.juke.network.array
import com.example.juke.network.flag
import com.example.juke.network.number
import com.example.juke.network.objectOrEmpty
import com.example.juke.network.text
import com.example.juke.network.toTrack
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** What the selected Echo is playing, as the server reports it. */
data class EchoState(
    val track: Track? = null,
    val queue: List<Track> = emptyList(),
    val index: Int = -1,
    val playing: Boolean = false,
    /** Position when the snapshot arrived, and the [SystemClock.elapsedRealtime] it arrived at. */
    val positionMs: Long = 0,
    val anchoredAt: Long = 0,
    val durationMs: Long = 0,
    val volume: Int? = null,
    val confirmed: Boolean = false,
    val processing: Boolean = false,
    val sharedOutput: SharedPlaybackOutput = SharedPlaybackOutput()
) {
    /** Live position: the server anchor plus the time since, while playing (like the web progress bar). */
    fun livePosition(now: Long = SystemClock.elapsedRealtime()): Long {
        val elapsed = if (playing && confirmed) (now - anchoredAt).coerceAtLeast(0) else 0
        val position = positionMs + elapsed
        return if (durationMs > 0) position.coerceAtMost(durationMs) else position
    }
}

/** Decode the shared web-remote snapshot without turning unknown fields into real values. */
internal fun parseEchoSnapshot(np: JsonObject, now: Long, previousVolume: Int?, preserveVolume: Boolean): EchoState {
    val queue = np.array("queue").mapIndexedNotNull { i, raw ->
        val item = BrowseParser.item(raw.objectOrEmpty())
        item.takeIf { it.videoId.isNotBlank() }?.toTrack()?.copy(uuid = raw.objectOrEmpty().text("entry_id").ifBlank { "echo:$i:${item.videoId}" })
    }
    val index = (np["queue_index"] as? JsonPrimitive)?.intOrNull ?: -1
    val videoId = np.text("video_id")
    // Current now-playing metadata wins over queued thumbnail/title snapshots for the same video.
    val queued = queue.getOrNull(index)?.takeIf { it.ytVideoId == videoId }
    val track = videoId.takeIf { it.isNotBlank() }?.let {
        val live = BrowseParser.item(np).toTrack()
        live.copy(uuid = queued?.uuid ?: "echo:current:$it", ytVideoId = it,
            title = np.text("title").takeIf(String::isNotBlank) ?: queued?.title ?: live.title,
            artist = live.artist.ifBlank { queued?.artist.orEmpty() },
            thumbnailUri = live.thumbnailUri ?: queued?.thumbnailUri,
            durationSec = live.durationSec.takeIf { it > 0 } ?: queued?.durationSec ?: 0,
            artists = live.artists.ifEmpty { queued?.artists.orEmpty() },
            artistId = live.artistId ?: queued?.artistId,
            albumId = live.albumId ?: queued?.albumId)
    }
    val volume = (np["volume"] as? JsonPrimitive)?.intOrNull?.coerceIn(0, 100)
    return EchoState(
        track = track,
        queue = queue,
        index = if (track != null && queue.getOrNull(index)?.uuid == track.uuid) index else -1,
        playing = np.flag("playing"),
        positionMs = np.number("position_ms").coerceAtLeast(0),
        anchoredAt = now,
        durationMs = np.number("duration_ms").takeIf { it > 0 } ?: (track?.durationSec?.times(1000L) ?: 0),
        volume = if (preserveVolume) previousVolume else volume ?: previousVolume,
        confirmed = np.flag("playback_confirmed"),
        processing = np.flag("playback_processing"),
        sharedOutput = sharedPlaybackOutput(np)
    )
}

/** Mirrored phone metadata is never proof that an Echo handoff started. */
internal fun EchoState.confirmedOnAlexa(videoId: String): Boolean = sharedOutput.mode != "phone" &&
    confirmed && playing && track?.ytVideoId == videoId
