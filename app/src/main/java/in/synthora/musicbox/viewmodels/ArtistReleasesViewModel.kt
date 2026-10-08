package `in`.synthora.musicbox.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import `in`.synthora.musicbox.network.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

data class ArtistReleasesState(val items: List<BrowseItem> = emptyList(), val loading: Boolean = false,
    val error: String? = null, val hasMore: Boolean = true, val offset: Long = 0)

/** One request at a time; entering an album doesn't discard the loaded collection. */
class ArtistReleasesViewModel : ViewModel() {
    private val _state = MutableStateFlow(ArtistReleasesState())
    val state = _state.asStateFlow()
    private var artist = ""
    private var kind = ""
    private var request: Job? = null

    fun open(artistId: String, category: String) {
        if (artist == artistId && kind == category) return
        request?.cancel()
        artist = artistId; kind = category
        _state.value = ArtistReleasesState()
        loadMore()
    }

    fun loadMore() {
        if (request?.isActive == true || !_state.value.hasMore) return
        request = viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val before = _state.value
                val data = Backend.get("/api/artist/$artist/releases", mapOf("kind" to kind,
                    "offset" to before.offset.toString(), "limit" to "30")).objectOrEmpty()
                val page = data.array("items").mapNotNull { (it as? JsonObject)?.let { row ->
                    BrowseParser.item(row, if (kind == "playlists") "" else "albums")
                } }.filter { it.id.isNotBlank() }
                _state.value = before.copy(items = (before.items + page).distinctBy { it.id },
                    offset = data.number("next_offset"), hasMore = data.flag("has_more") && page.isNotEmpty(), loading = false)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val message = if (e is BackendHttpException && e.statusCode == 404)
                    "Full artist collections aren't available on your music server yet. Update the server and try again."
                else networkErrorMessage(e) ?: e.message ?: "Couldn't load releases"
                _state.value = _state.value.copy(loading = false, error = message)
            }
        }
    }
}
