package `in`.synthora.musicbox.models

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
    val lyricsOffsetMs: Long = 0L,
    val artists: List<ArtistCredit> = emptyList()
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


@Serializable
data class ArtistCredit(val name: String, val id: String? = null)

/**
 * Match the webapp's artistLinksHtml rules: authoritative structured credits
 * preserve literal names (including "and" and "&"). An ID-less combined
 * credit is a legacy byline, not a declaration of a single artist.
 */
fun Track.artistCredits(): List<ArtistCredit> {
    val supplied = artists.filter { it.name.isNotBlank() }.map {
        ArtistCredit(it.name.trim(), it.id?.trim()?.takeIf(String::isNotEmpty))
    }
    val credits = if (supplied.size > 1 || supplied.singleOrNull()?.id != null) {
        supplied
    } else {
        val byline = supplied.singleOrNull()?.name ?: artist.trim()
        byline.split(ARTIST_BYLINE_SEPARATOR).map(String::trim).filter(String::isNotEmpty)
            .mapIndexed { index, name ->
                ArtistCredit(name, if (index == 0) artistId?.trim()?.takeIf(String::isNotEmpty) else null)
            }
    }
    return credits.distinctBy { it.id ?: it.name.lowercase(java.util.Locale.ROOT) }
}

// Server credit joins use "and" and commas, never ampersands. Preserve band
// names such as Simon & Garfunkel; structured collaborations already have rows.
private val ARTIST_BYLINE_SEPARATOR = Regex(
    ",\\s+and\\s+|,\\s*|\\s+and\\s+|\\s*·\\s*|\\s+(?:feat\\.?|ft\\.?|featuring)\\s+",
    RegexOption.IGNORE_CASE
)
