package com.example.juke.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.juke.models.Track
import com.example.juke.network.Backend
import com.example.juke.network.BackendAuthException
import com.example.juke.network.BrowseItem
import com.example.juke.network.BrowseParser
import com.example.juke.network.array
import com.example.juke.network.objectOrEmpty
import com.example.juke.network.text
import com.example.juke.network.toTrack
import com.example.juke.services.AccountRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar

/** One Home shelf. [tracks] are its playable songs in order; [items] keep every card. */
data class HomeShelf(
    val id: String,
    val title: String,
    val layout: String,
    val items: List<BrowseItem>,
    val tracks: List<Track>
)

data class HomeUiState(
    val greeting: String = "",
    val shelves: List<HomeShelf> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null
)

/**
 * Home recommendations from the server's YouTube Music feed: the signed-in account's own Home
 * when YouTube is connected, otherwise charts and trending music.
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(HomeUiState(greeting = buildGreeting()))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _signedOut = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val signedOut: SharedFlow<Unit> = _signedOut.asSharedFlow()

    private var loadJob: Job? = null

    fun loadHomeData() {
        if (_uiState.value.shelves.isNotEmpty() || loadJob?.isActive == true) return
        load(refresh = false)
    }

    fun clear() {
        loadJob?.cancel()
        _uiState.value = HomeUiState(greeting = buildGreeting())
    }

    fun refresh() {
        _uiState.update { it.copy(isRefreshing = true) }
        load(refresh = true)
    }

    /** Accounts changed (YouTube connected or signed in again): rebuild the feed. */
    fun reload() {
        _uiState.update { it.copy(isLoading = it.shelves.isEmpty()) }
        load(refresh = true)
    }

    private fun load(refresh: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val feed = Backend.get("/api/home/", if (refresh) mapOf("refresh" to "1") else emptyMap()).objectOrEmpty()
                val liked = AccountRepository.liked.value
                val shelves = feed.array("shelves").mapNotNull { raw ->
                    val shelf = raw.objectOrEmpty()
                    val items = shelf.array("items").mapNotNull { (it as? kotlinx.serialization.json.JsonObject)?.let(BrowseParser::item) }
                    if (items.isEmpty()) return@mapNotNull null
                    HomeShelf(
                        id = shelf.text("id"),
                        title = shelf.text("title"),
                        layout = shelf.text("layout"),
                        items = items,
                        tracks = items.filter { it.kind == "track" && it.videoId.isNotBlank() }.map { it.toTrack(liked) }
                    )
                }
                _uiState.update {
                    it.copy(
                        greeting = buildGreeting(),
                        shelves = shelves,
                        isLoading = false,
                        isRefreshing = false,
                        error = if (shelves.isEmpty()) "No recommendations yet. Pull down to try again." else null
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackendAuthException) {
                _uiState.update { it.copy(isLoading = false, isRefreshing = false) }
                _signedOut.tryEmit(Unit)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        error = if (it.shelves.isEmpty()) "Couldn't load recommendations. Pull down to try again." else null
                    )
                }
            }
        }
    }

    private fun buildGreeting(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..11 -> "First Light Sounds !"
            in 12..16 -> "Afternoon Drift !"
            in 17..20 -> "The Golden Hour !"
            else -> "After Dark !"
        }
    }
}
