package com.example.juke.models

import kotlinx.serialization.Serializable

/**
 * Track data model representing a music track in the JUKE app.
 */
@Serializable
data class Track(
    val uuid: String,
    val title: String,
    val artist: String,
    val thumbnailUri: String? = null,
    val durationSec: Int,
    val localUri: String? = null,
    val ytVideoId: String? = null,
    val syncedLyrics: String? = null,
    val plainLyrics: String? = null,
    val romanizedSyncedLyrics: String? = null,
    val romanizedPlainLyrics: String? = null,
    val isFavourite: Boolean = false,
    val playCount: Int = 0,
    val lastPlayedAt: String? = null,
    val albumId: String? = null,
    val artistId: String? = null,
    val isStream: Boolean = false,
    val lyricsOffsetMs: Long = 0L
)

fun Track.withUpdatedLyrics(
    syncedLyrics: String?,
    plainLyrics: String?
): Track {
    return copy(
        syncedLyrics = syncedLyrics,
        plainLyrics = plainLyrics,
        romanizedSyncedLyrics = if (this.syncedLyrics == syncedLyrics) romanizedSyncedLyrics else null,
        romanizedPlainLyrics = if (this.plainLyrics == plainLyrics) romanizedPlainLyrics else null
    )
}

/**
 * Lyrics result from LRCLib API.
 */
@Serializable
data class LRCLibResult(
    val id: Int,
    val name: String,
    val trackName: String,
    val artistName: String,
    val albumName: String,
    val duration: Double,
    val instrumental: Boolean,
    val plainLyrics: String?,
    val syncedLyrics: String?
)

