package `in`.synthora.musicbox.services

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import `in`.synthora.musicbox.network.array

/** Public category names/parameters survive leaving Search and restarting the app. */
class DiscoveryCache(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("discovery_names", Context.MODE_PRIVATE)
    fun moods(): JsonObject? = runCatching {
        Json.parseToJsonElement(prefs.getString("moods", "").orEmpty()) as? JsonObject
    }.getOrNull()
    fun fresh(): Boolean = System.currentTimeMillis() - prefs.getLong("saved_at", 0) < 24 * 60 * 60 * 1000L
    fun save(data: JsonObject) {
        if (data.array("moods_and_genres").isEmpty()) return
        prefs.edit().putString("moods", buildJsonObject { put("moods_and_genres", data.array("moods_and_genres")) }.toString())
            .putLong("saved_at", System.currentTimeMillis()).apply()
    }
}
