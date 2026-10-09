package `in`.synthora.musicbox.services

import android.content.SharedPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal const val HOME_CACHE_TTL_MS = 30 * 60 * 1000L
internal fun isHomeFeedFresh(savedAt: Long, now: Long): Boolean =
    savedAt > 0 && now >= savedAt && now - savedAt < HOME_CACHE_TTL_MS

internal data class CachedHomeFeed(val feed: JsonObject, val savedAt: Long) {
    fun isFresh(now: Long): Boolean = isHomeFeedFresh(savedAt, now)
}

/** Cleared on sign-out or account changes; only successful, nonempty feeds are stored. */
internal class HomeFeedCache(private val preferences: SharedPreferences) {
    fun read(): CachedHomeFeed? = runCatching {
        val savedAt = preferences.getLong("saved_at", 0)
        if (savedAt <= 0) return null
        val raw = preferences.getString("feed", null) ?: return null
        if (raw.length > 2_000_000) return null
        val feed = Json.parseToJsonElement(raw) as? JsonObject ?: return null
        CachedHomeFeed(feed, savedAt)
    }.getOrNull()

    fun save(feed: String, now: Long) {
        if (feed.length <= 2_000_000) preferences.edit().putString("feed", feed).putLong("saved_at", now).apply()
    }
    fun clear() { preferences.edit().clear().apply() }
}
