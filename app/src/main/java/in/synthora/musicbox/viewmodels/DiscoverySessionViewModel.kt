package `in`.synthora.musicbox.viewmodels

import androidx.lifecycle.ViewModel
import kotlinx.serialization.json.JsonObject

/** Account discovery data lives with the Search navigation entry, not the album screen. */
class DiscoverySessionViewModel : ViewModel() {
    private val pages = mutableMapOf<String, JsonObject>()
    fun get(key: String): JsonObject? = pages[key]
    fun put(key: String, page: JsonObject) { pages[key] = page }
    fun clear() = pages.clear()
}
