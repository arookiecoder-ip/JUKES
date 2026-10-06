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
import com.example.juke.network.flag
import com.example.juke.network.number
import com.example.juke.network.objectOrEmpty
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
import kotlinx.coroutines.async
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class SortOption {
    DEFAULT,
    TITLE,
    ARTIST
}

/** What the library shows: liked songs, followed artists, listening history or one playlist. */
enum class LibraryView { ALL, LIKED, ARTISTS, HISTORY, PLAYLIST }

data class LibraryUiState(
    val view: LibraryView = LibraryView.ALL,
    val selectedPlaylist: BrowseItem? = null,
    val playlists: List<BrowseItem> = emptyList(),
    val artists: List<BrowseItem> = emptyList(),
    val albums: List<BrowseItem> = emptyList(),
    /** Songs of the current view after search and sort. */
    val tracks: List<Track> = emptyList(),
    val trackCount: Int = 0,
    val hasMore: Boolean = false,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
    /** The server has no working YouTube Music sign-in. */
    val needsYouTube: Boolean = false,
    val searchQuery: String = "",
    val sortOption: SortOption = SortOption.DEFAULT,
    val showSortSheet: Boolean = false,
    val isSelectionMode: Boolean = false,
    val selectedTrackUuids: Set<String> = emptySet()
)

/** The YouTube Music library of the account behind the server. Nothing is stored on the phone. */
class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _signedOut = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val signedOut: SharedFlow<Unit> = _signedOut.asSharedFlow()

    /** Songs of the current view as loaded, before search and sort. */
    private var loadedTracks: List<Track> = emptyList()
    private var nextOffset = 0L
    private var viewJob: Job? = null
    private var started = false
    private var playlistsJob: Job? = null

    init {
        viewModelScope.launch {
            AccountRepository.liked.collect { publishTracks() }
        }
    }

    fun clear() {
        viewJob?.cancel()
        playlistsJob?.cancel()
        loadedTracks = emptyList()
        nextOffset = 0
        started = false
        _uiState.value = LibraryUiState()
    }

    /** First open of the library screen. */
    fun start() {
        if (started) return
        started = true
        refresh()
    }

    /** Reload playlists and the current view (pull to refresh, accounts changed). */
    fun refresh() {
        if (_uiState.value.view != LibraryView.ALL) loadPlaylists()
        when (_uiState.value.view) {
            LibraryView.ALL -> loadOverview()
            LibraryView.LIKED -> showLiked()
            LibraryView.ARTISTS -> showArtists()
            LibraryView.HISTORY -> showHistory()
            LibraryView.PLAYLIST -> _uiState.value.selectedPlaylist?.let(::showPlaylist) ?: showLiked()
        }
    }

    private fun loadOverview() {
        viewJob?.cancel()
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewJob = viewModelScope.launch {
            guarded {
                kotlinx.coroutines.coroutineScope {
                    val library = async { Backend.get("/api/library/").objectOrEmpty() }
                    val subscriptions = async { Backend.get("/api/subscribed_artists/").objectOrEmpty() }
                    val data = library.await()
                    val collections = data.array("playlists").mapNotNull { (it as? JsonObject)?.let(BrowseParser::item) }
                        .filterNot { it.title.trim().lowercase() in setOf("episodes for later", "sounds from shorts", "new episodes", "new episdes") }
                    val liked = collections.firstOrNull { it.playlistId == "LM" || it.id == "LM" }
                        ?: BrowseParser.item(JsonObject(mapOf("playlistId" to JsonPrimitive("LM"), "title" to JsonPrimitive("Liked Music"))))
                    val albums = (collections.filter { it.kind == "album" } + data.array("albums").mapNotNull {
                        (it as? JsonObject)?.let { raw -> BrowseParser.item(raw, "albums") }
                    }).distinctBy { it.id }
                    val artists = subscriptions.await().array("artists").mapNotNull {
                        (it as? JsonObject)?.let { raw -> BrowseParser.item(raw, "artists") }
                    }.filter { it.id.isNotBlank() }.distinctBy { it.id }
                    _uiState.update { it.copy(
                        playlists = listOf(liked) + collections.filter { item -> item.kind != "album" && item.playlistId != "LM" && item.id != "LM" }.distinctBy { item -> item.id },
                        albums = albums, artists = artists, isLoading = false, needsYouTube = false
                    ) }
                }
            }
        }
    }

    private fun loadPlaylists() {
        if (_uiState.value.view == LibraryView.ALL) { loadOverview(); return }
        playlistsJob?.cancel()
        playlistsJob = viewModelScope.launch {
            try {
                val data = Backend.get("/api/library/").objectOrEmpty()
                val playlists = data.array("playlists").mapNotNull { (it as? JsonObject)?.let(BrowseParser::item) }
                    // Liked Music has its own chip.
                    .filter { it.playlistId.ifBlank { it.id } != "LM" }
                _uiState.update { it.copy(playlists = playlists, needsYouTube = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackendAuthException) {
                _signedOut.tryEmit(Unit)
            } catch (e: Exception) {
                if (isYouTubeAuthError(e)) _uiState.update { it.copy(needsYouTube = true) }
            }
        }
    }

    fun showLiked() = showPlaylistTracks(LibraryView.LIKED, null, "LM")

    fun showPlaylist(playlist: BrowseItem) =
        showPlaylistTracks(LibraryView.PLAYLIST, playlist, playlist.playlistId.ifBlank { playlist.id })

    private fun showPlaylistTracks(view: LibraryView, playlist: BrowseItem?, id: String) {
        startView(view, playlist)
        viewJob = viewModelScope.launch { loadPlaylistPage(id, first = true) }
    }

    fun loadMore() {
        val state = _uiState.value
        if (!state.hasMore || state.isLoadingMore || state.isLoading) return
        val id = when (state.view) {
            LibraryView.LIKED -> "LM"
            LibraryView.PLAYLIST -> state.selectedPlaylist?.let { it.playlistId.ifBlank { it.id } } ?: return
            else -> return
        }
        _uiState.update { it.copy(isLoadingMore = true) }
        viewJob = viewModelScope.launch { loadPlaylistPage(id, first = false) }
    }

    private suspend fun loadPlaylistPage(id: String, first: Boolean) {
        guarded {
            val data = Backend.get(
                "/api/library/playlists/$id",
                mapOf("offset" to nextOffset.toString(), "limit" to PAGE.toString())
            ).objectOrEmpty()
            val page = data.array("tracks").mapNotNull { (it as? JsonObject)?.let(BrowseParser::item) }
                .filter { it.videoId.isNotBlank() }
                .map { it.toTrack() }
            nextOffset = data.number("next_offset").takeIf { it > 0 } ?: (nextOffset + page.size)
            loadedTracks = if (first) page else loadedTracks + page
            _uiState.update {
                it.copy(
                    trackCount = data.number("trackCount").toInt().takeIf { n -> n > 0 } ?: loadedTracks.size,
                    hasMore = data.flag("has_more") && page.isNotEmpty(),
                    isLoading = false,
                    isLoadingMore = false,
                    needsYouTube = false
                )
            }
            publishTracks()
        }
    }

    fun showHistory() {
        startView(LibraryView.HISTORY, null)
        viewJob = viewModelScope.launch {
            guarded {
                val history = Backend.get("/history/")
                loadedTracks = (history as? kotlinx.serialization.json.JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonObject)?.let(BrowseParser::item) }
                    .filter { it.videoId.isNotBlank() }
                    .map { it.toTrack() }
                _uiState.update { it.copy(trackCount = loadedTracks.size, isLoading = false) }
                publishTracks()
            }
        }
    }

    fun showArtists() {
        startView(LibraryView.ARTISTS, null)
        viewJob = viewModelScope.launch {
            guarded {
                val data = Backend.get("/api/subscribed_artists/").objectOrEmpty()
                val artists = data.array("artists").mapNotNull { (it as? JsonObject)?.let { raw -> BrowseParser.item(raw, "artists") } }
                    .filter { it.id.isNotBlank() }
                _uiState.update { it.copy(artists = artists, isLoading = false) }
            }
        }
    }

    private fun startView(view: LibraryView, playlist: BrowseItem?) {
        viewJob?.cancel()
        loadedTracks = emptyList()
        nextOffset = 0
        _uiState.update {
            it.copy(
                view = view, selectedPlaylist = playlist, tracks = emptyList(), trackCount = 0,
                hasMore = false, isLoading = true, isLoadingMore = false, error = null,
                isSelectionMode = false, selectedTrackUuids = emptySet()
            )
        }
    }

    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackendAuthException) {
            _uiState.update { it.copy(isLoading = false, isLoadingMore = false) }
            _signedOut.tryEmit(Unit)
        } catch (e: Exception) {
            val needsYouTube = isYouTubeAuthError(e)
            _uiState.update {
                it.copy(
                    isLoading = false, isLoadingMore = false, needsYouTube = needsYouTube,
                    error = if (needsYouTube) null else e.message ?: "Couldn't load your library"
                )
            }
        }
    }

    private fun isYouTubeAuthError(e: Exception) =
        e.message?.contains("YouTube Music authentication", ignoreCase = true) == true ||
            e.message?.contains("Connect YouTube Music", ignoreCase = true) == true

    /** Apply search, sort and the account's likes to the loaded songs. */
    private fun publishTracks() {
        val state = _uiState.value
        val liked = AccountRepository.liked.value
        val query = state.searchQuery.trim().lowercase()
        val filtered = if (query.isEmpty()) loadedTracks else loadedTracks.filter {
            it.title.lowercase().contains(query) || it.artist.lowercase().contains(query)
        }
        val sorted = when (state.sortOption) {
            SortOption.DEFAULT -> filtered
            SortOption.TITLE -> filtered.sortedBy { it.title.lowercase() }
            SortOption.ARTIST -> filtered.sortedBy { it.artist.lowercase() }
        }
        _uiState.update { it.copy(tracks = sorted.map { t -> t.copy(isFavourite = t.ytVideoId in liked) }) }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        publishTracks()
    }

    fun clearSearch() = updateSearchQuery("")

    fun updateSortOption(option: SortOption) {
        _uiState.update { it.copy(sortOption = option, showSortSheet = false) }
        publishTracks()
    }

    fun toggleSortSheet() {
        _uiState.update { it.copy(showSortSheet = !it.showSortSheet) }
    }

    fun toggleFavorite(track: Track) {
        val videoId = track.ytVideoId ?: return
        viewModelScope.launch {
            try {
                AccountRepository.setLiked(videoId, !AccountRepository.isLiked(videoId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit("Couldn't update the like: ${com.example.juke.network.networkErrorMessage(e) ?: e.message}")
            }
        }
    }

    // ---------- Playlists on YouTube Music ----------

    /** The account's own playlists that songs can be added to. */
    suspend fun editablePlaylists(): List<BrowseItem> {
        val data = Backend.get("/api/library/").objectOrEmpty()
        return data.array("playlists").mapNotNull { (it as? JsonObject)?.let(BrowseParser::item) }
            .filter { it.editable }
    }

    fun createPlaylist(name: String, thenAdd: List<Track> = emptyList()) {
        viewModelScope.launch {
            try {
                val created = Backend.post("/api/library/playlists/", JsonObject(mapOf("name" to JsonPrimitive(name.trim())))).objectOrEmpty()
                val id = created["id"]?.let { (it as? JsonPrimitive)?.content }.orEmpty()
                thenAdd.forEach { track -> addTrack(id, track) }
                _messages.tryEmit(if (thenAdd.isEmpty()) "Created \"$name\"" else "Added to \"$name\"")
                loadPlaylists()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit("Couldn't create the playlist: ${com.example.juke.network.networkErrorMessage(e) ?: e.message}")
            }
        }
    }

    fun renamePlaylist(playlist: BrowseItem, name: String) {
        val id = playlist.playlistId.ifBlank { playlist.id }
        viewModelScope.launch {
            try {
                Backend.patch("/api/library/playlists/$id", JsonObject(mapOf("title" to JsonPrimitive(name.trim()))))
                val renamed = playlist.copy(title = name.trim())
                _uiState.update { state ->
                    state.copy(
                        playlists = state.playlists.map { if (it.id == playlist.id) renamed else it },
                        selectedPlaylist = if (state.selectedPlaylist?.id == playlist.id) renamed else state.selectedPlaylist
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit("Couldn't rename the playlist: ${com.example.juke.network.networkErrorMessage(e) ?: e.message}")
            }
        }
    }

    fun deletePlaylist(playlist: BrowseItem) {
        val id = playlist.playlistId.ifBlank { playlist.id }
        viewModelScope.launch {
            try {
                Backend.delete("/api/library/playlists/$id")
                _uiState.update { state -> state.copy(playlists = state.playlists.filterNot { it.id == playlist.id }) }
                if (_uiState.value.selectedPlaylist?.id == playlist.id) showLiked()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit("Couldn't delete the playlist: ${com.example.juke.network.networkErrorMessage(e) ?: e.message}")
            }
        }
    }

    fun addTracksToPlaylist(playlist: BrowseItem, tracks: List<Track>) {
        val id = playlist.playlistId.ifBlank { playlist.id }
        viewModelScope.launch {
            try {
                tracks.forEach { addTrack(id, it) }
                _messages.tryEmit(if (tracks.size == 1) "Added to ${playlist.title}" else "Added ${tracks.size} songs to ${playlist.title}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit("Couldn't add to ${playlist.title}: ${com.example.juke.network.networkErrorMessage(e) ?: e.message}")
            }
        }
    }

    private suspend fun addTrack(playlistId: String, track: Track) {
        val videoId = track.ytVideoId ?: return
        Backend.post("/api/library/playlists/$playlistId/tracks", JsonObject(mapOf("video_id" to JsonPrimitive(videoId))))
    }

    // ---------- Selection ----------

    fun toggleSelectionMode(enabled: Boolean) {
        _uiState.update { it.copy(isSelectionMode = enabled, selectedTrackUuids = emptySet()) }
    }

    fun toggleTrackSelection(trackUuid: String) {
        _uiState.update { state ->
            val selected = if (trackUuid in state.selectedTrackUuids) state.selectedTrackUuids - trackUuid
            else state.selectedTrackUuids + trackUuid
            state.copy(selectedTrackUuids = selected, isSelectionMode = selected.isNotEmpty())
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(isSelectionMode = false, selectedTrackUuids = emptySet()) }
    }

    fun selectAll() {
        _uiState.update { it.copy(selectedTrackUuids = it.tracks.map { t -> t.uuid }.toSet()) }
    }

    companion object {
        private const val PAGE = 100
    }
}
