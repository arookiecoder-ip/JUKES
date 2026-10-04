package com.example.juke.services

import android.content.Context
import androidx.core.content.edit

/** The audio providers a song can be fetched from. */
enum class Source { SPOTSAVER, GAMEPVZ, SPOTMATE }

/**
 * Remembers which provider produced each song's audio and which providers the user rejected for a
 * song ("wrong song" → refetch). Later pulls of that song put rejected providers last, so a bad source
 * is not picked again. Persisted so it survives restarts.
 */
class SourceMemory(context: Context) {
    private val prefs = context.getSharedPreferences("source_memory", Context.MODE_PRIVATE)

    /** Provider that produced the audio currently stored for [uuid], if known. */
    fun lastUsed(uuid: String): Source? = prefs.getString("used:$uuid", null)?.toSourceOrNull()

    fun recordUsed(uuid: String, source: Source) = prefs.edit { putString("used:$uuid", source.name) }

    /** Providers the user rejected for this song. */
    fun avoided(songKey: String): Set<Source> =
        prefs.getString("avoid:$songKey", null)?.split(',')?.mapNotNull { it.toSourceOrNull() }?.toSet() ?: emptySet()

    fun avoid(songKey: String, source: Source) {
        val next = avoided(songKey) + source
        // Every provider rejected means the problem is the song, not the source: start over.
        if (next.size >= Source.entries.size) prefs.edit { remove("avoid:$songKey") }
        else prefs.edit { putString("avoid:$songKey", next.joinToString(",") { it.name }) }
    }

    /**
     * Provider order for one pull of [songKey]: Spotsaver first, then the legacy pair in
     * [gamepvzFirst] order, with rejected providers moved to the end.
     */
    fun order(songKey: String, gamepvzFirst: Boolean): List<Source> {
        val base = if (gamepvzFirst) listOf(Source.SPOTSAVER, Source.GAMEPVZ, Source.SPOTMATE)
        else listOf(Source.SPOTSAVER, Source.SPOTMATE, Source.GAMEPVZ)
        val avoided = avoided(songKey)
        return base.filterNot { it in avoided } + base.filter { it in avoided }
    }

    private fun String.toSourceOrNull() = Source.entries.firstOrNull { it.name == this }
}
