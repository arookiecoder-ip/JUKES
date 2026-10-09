package `in`.synthora.musicbox.viewmodels

import android.app.Application
import `in`.synthora.musicbox.utils.SafeLog as Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import `in`.synthora.musicbox.models.Track
import `in`.synthora.musicbox.network.Backend
import `in`.synthora.musicbox.network.BrowseItem
import `in`.synthora.musicbox.network.BrowseParser
import `in`.synthora.musicbox.network.array
import `in`.synthora.musicbox.network.flag
import `in`.synthora.musicbox.network.imageUrl
import `in`.synthora.musicbox.network.number
import `in`.synthora.musicbox.network.objectOrEmpty
import `in`.synthora.musicbox.network.text
import `in`.synthora.musicbox.network.toTrack
import `in`.synthora.musicbox.services.AccountRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

data class PlaylistDetailUiState(
    val playlist: BrowseItem? = null,
    val title: String = "",
    val author: String = "",
    val imageUrl: String = "",
    val description: String = "",
    val trackCount: Int = 0,
    val tracks: List<Track> = emptyList(),
    val hasMore: Boolean = false,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null
)

/** A YouTube Music playlist (the account's own, liked music, or any public one), paged by 100. */
class PlaylistDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(PlaylistDetailUiState())
    val uiState: StateFlow<PlaylistDetailUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var nextOffset = 0L

    /** The playlist id the server accepts: plain PL…/RD…/LM ids, not browse (VL…) ids. */
    val playlistId: String get() = _uiState.value.playlist?.let { it.playlistId.ifBlank { it.id } }.orEmpty()

    fun loadPlaylistDetails(playlist: BrowseItem) {
        loadJob?.cancel()
        nextOffset = 0
        _uiState.value = PlaylistDetailUiState(
            playlist = playlist, title = playlist.title, author = playlist.subtitle,
            imageUrl = playlist.image, isLoading = true
        )
        loadJob = viewModelScope.launch { loadPage(first = true) }
    }

    fun loadMore() {
        val state = _uiState.value
        if (!state.hasMore || state.isLoadingMore || state.isLoading) return
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }
            loadPage(first = false)
        }
    }

    private suspend fun loadPage(first: Boolean) {
        val id = playlistId
        try {
            val data = Backend.get(
                "/api/library/playlists/$id",
                mapOf("offset" to nextOffset.toString(), "limit" to PAGE.toString())
            ).objectOrEmpty()
            val liked = AccountRepository.liked.value
            val tracks = data.array("tracks").mapNotNull { (it as? JsonObject)?.let(BrowseParser::item) }
                .filter { it.videoId.isNotBlank() }
                .map { it.toTrack(liked) }
            nextOffset = data.number("next_offset").takeIf { it > 0 } ?: (nextOffset + tracks.size)
            _uiState.update { state ->
                val author = data["author"].objectOrEmpty().text("name").ifBlank { data.text("author") }
                state.copy(
                    title = data.text("title").ifBlank { state.title },
                    author = author.ifBlank { state.author },
                    imageUrl = imageUrl(data["thumbnails"]).ifBlank { state.imageUrl },
                    description = data.text("description").ifBlank { state.description },
                    trackCount = data.number("trackCount").toInt().takeIf { it > 0 } ?: (state.tracks.size + tracks.size),
                    tracks = if (first) tracks else state.tracks + tracks,
                    hasMore = data.flag("has_more") && tracks.isNotEmpty(),
                    isLoading = false,
                    isLoadingMore = false
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("PlaylistDetailViewModel", "Error loading playlist: ${`in`.synthora.musicbox.network.networkErrorMessage(e) ?: e.message}", e)
            _uiState.update { it.copy(isLoading = false, isLoadingMore = false, error = `in`.synthora.musicbox.network.networkErrorMessage(e) ?: e.message ?: "Couldn't load the playlist") }
        }
    }

    fun clearPlaylistDetail() {
        loadJob?.cancel()
        _uiState.value = PlaylistDetailUiState()
    }

    companion object {
        private const val PAGE = 100
    }
}
