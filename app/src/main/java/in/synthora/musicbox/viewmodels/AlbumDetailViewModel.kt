package `in`.synthora.musicbox.viewmodels

import `in`.synthora.musicbox.utils.SafeLog as Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import `in`.synthora.musicbox.models.Track
import `in`.synthora.musicbox.network.Backend
import `in`.synthora.musicbox.network.BrowseItem
import `in`.synthora.musicbox.network.BrowseParser
import `in`.synthora.musicbox.network.array
import `in`.synthora.musicbox.network.imageUrl
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

data class AlbumDetailUiState(
    val album: BrowseItem? = null,
    val title: String = "",
    val artist: String = "",
    val artistId: String = "",
    val year: String = "",
    val imageUrl: String = "",
    val description: String = "",
    val tracks: List<Track> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

/** Album page from YouTube Music (`/api/album/<MPRE…|OLAK…>`). */
class AlbumDetailViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(AlbumDetailUiState())
    val uiState: StateFlow<AlbumDetailUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null

    fun loadAlbumDetails(album: BrowseItem) {
        loadJob?.cancel()
        _uiState.value = AlbumDetailUiState(
            album = album, title = album.title, artist = album.subtitle,
            imageUrl = album.image, isLoading = true
        )
        loadJob = viewModelScope.launch {
            try {
                val data = Backend.get("/api/album/${album.id}").objectOrEmpty()
                val image = imageUrl(data["thumbnail"]).ifBlank { imageUrl(data["thumbnails"]) }.ifBlank { album.image }
                val liked = AccountRepository.liked.value
                val tracks = data.array("tracks").mapNotNull { (it as? JsonObject)?.let(BrowseParser::item) }
                    .filter { it.videoId.isNotBlank() }
                    .map { it.toTrack(liked, fallbackImage = image).copy(albumId = album.id) }
                _uiState.update {
                    it.copy(
                        title = data.text("title").ifBlank { album.title },
                        artist = data.text("artist").ifBlank { album.subtitle },
                        artistId = data.text("channelId", "artist_id"),
                        year = data.text("year"),
                        imageUrl = image,
                        description = data.text("description"),
                        tracks = tracks,
                        isLoading = false
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("AlbumDetailViewModel", "Error loading album: ${`in`.synthora.musicbox.network.networkErrorMessage(e) ?: e.message}", e)
                _uiState.update { it.copy(isLoading = false, error = `in`.synthora.musicbox.network.networkErrorMessage(e) ?: e.message ?: "Couldn't load the album") }
            }
        }
    }

    fun loadAlbumDetailsById(albumId: String) {
        loadAlbumDetails(BrowseItem(albumId, "album", "", "", "", "", "", 0, "", albumId, false, JsonObject(emptyMap())))
    }

    fun clearAlbumDetail() {
        loadJob?.cancel()
        _uiState.value = AlbumDetailUiState()
    }
}
