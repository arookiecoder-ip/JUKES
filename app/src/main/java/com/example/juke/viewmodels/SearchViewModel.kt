package com.example.juke.viewmodels

import android.app.Application
import com.example.juke.utils.SafeLog as Log
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.juke.analytics.AnalyticsManager
import com.example.juke.models.Track
import com.example.juke.network.ApiClient
import com.example.juke.network.Backend
import com.example.juke.network.BackendAuthException
import com.example.juke.network.BrowseItem
import com.example.juke.network.BrowseParser
import com.example.juke.network.number
import com.example.juke.network.flag
import com.example.juke.network.array
import com.example.juke.network.imageUrl
import com.example.juke.network.objectOrEmpty
import com.example.juke.network.text
import com.example.juke.network.toTrack
import com.example.juke.services.AccountRepository
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

data class SearchUiState(
    val query: String = "",
    val suggestions: List<String> = emptyList(),
    val isShowingSuggestions: Boolean = false,
    val tracks: List<Track> = emptyList(),
    val artists: List<BrowseItem> = emptyList(),
    val topResult: BrowseItem? = null,
    val topArtistTracks: List<Track> = emptyList(),
    val playlists: List<BrowseItem> = emptyList(),
    val albums: List<BrowseItem> = emptyList(),
    val isSearching: Boolean = false,
    val error: String? = null,
    val recentSearches: List<String> = emptyList()
)

data class ArtistDetailUiState(
    val artist: BrowseItem? = null,
    val imageUrl: String = "",
    val subscribers: String = "",
    val description: String = "",
    val topTracks: List<Track> = emptyList(),
    val allTracks: List<Track> = emptyList(),
    val topSongsBrowseId: String = "",
    val albums: List<BrowseItem> = emptyList(),
    val singles: List<BrowseItem> = emptyList(),
    val playlists: List<BrowseItem> = emptyList(),
    val related: List<BrowseItem> = emptyList(),
    val subscriptionBusy: Boolean = false,
    val allSongsLoaded: Boolean = false,
    val songsNextOffset: Long = 0,
    val albumsHasMore: Boolean = false,
    val singlesHasMore: Boolean = false,
    val songsLoading: Boolean = false,
    val durationsLoading: Boolean = false,
    val isSubscribed: Boolean? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)

/** Search and artist pages, from YouTube Music through the server. */
class SearchViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val LIVE_SUGGESTION_DEBOUNCE_MS = 100L
        private const val MIN_SUGGESTION_QUERY_LENGTH = 2
        private const val SUGGESTION_CACHE_MAX_ENTRIES = 64
        private const val YT_SUGGESTIONS_URL =
            "https://music.youtube.com/youtubei/v1/music/get_search_suggestions?prettyPrint=false"
        private const val YT_CLIENT_NAME = "WEB_REMIX"
        private const val YT_CLIENT_VERSION = "1.20260421.03.01"
        private const val YT_CLIENT_NAME_HEADER = "67"
    }

    private val searchPrefs =
        application.getSharedPreferences("search_history", android.content.Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(
        SearchUiState(
            recentSearches = loadRecentSearches()
        )
    )
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val _artistDetailState = MutableStateFlow(ArtistDetailUiState())
    val artistDetailState: StateFlow<ArtistDetailUiState> = _artistDetailState.asStateFlow()

    private val _signedOut = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val signedOut: SharedFlow<Unit> = _signedOut.asSharedFlow()

    private var searchJob: Job? = null
    private var artistJob: Job? = null
    private var songsJob: Job? = null
    private var durationJob: Job? = null
    private val suggestionRequestNonce = AtomicLong(0L)
    private val warmupRequestNonce = AtomicLong(0L)
    private val suggestionPrefixCache =
        LinkedHashMap<String, List<String>>(SUGGESTION_CACHE_MAX_ENTRIES)

    fun updateQuery(query: String) {
        // Skip duplicate consecutive input values (distinctUntilChanged behavior).
        if (query == _uiState.value.query) return

        // Typing always puts us in suggestion mode
        _uiState.value = _uiState.value.copy(
            query = query,
            isShowingSuggestions = true
        )

        searchJob?.cancel()
        val requestNonce = suggestionRequestNonce.incrementAndGet()

        if (query.length >= MIN_SUGGESTION_QUERY_LENGTH) {
            // Serve an immediate best-effort prefix hit while a fresh request is in-flight.
            getCachedSuggestions(query)?.let { cached ->
                _uiState.value = _uiState.value.copy(
                    suggestions = cached
                )
            }

            searchJob = viewModelScope.launch {
                delay(LIVE_SUGGESTION_DEBOUNCE_MS) // Fast debounce for near-immediate typing suggestions
                if (requestNonce != suggestionRequestNonce.get()) return@launch
                fetchSuggestions(query, requestNonce)
            }
        } else if (query.isNotBlank()) {
            // For short inputs, keep typing mode active but avoid remote calls.
            _uiState.value = _uiState.value.copy(
                suggestions = emptyList()
            )
        } else {
            // Clear everything when the field is emptied
            _uiState.value = _uiState.value.copy(
                suggestions = emptyList(),
                isShowingSuggestions = false,
                tracks = emptyList(),
                artists = emptyList(),
                playlists = emptyList(),
                albums = emptyList(), topResult = null, topArtistTracks = emptyList()
            )
        }
    }

    fun warmSuggestionsConnection() {
        // Run exactly once per process to warm DNS/TLS/HTTP connection for suggestion endpoint.
        if (!warmupRequestNonce.compareAndSet(0L, 1L)) return

        viewModelScope.launch {
            try {
                val warmupPayload = """
                    {
                        "input": "a",
                        "context": {
                            "client": {
                                "clientName": "WEB_REMIX",
                                "clientVersion": "1.20260414.05.00",
                                "hl": "en-US",
                                "gl": "US"
                            }
                        }
                    }
                """.trimIndent()

                ApiClient.httpClient.post(YT_SUGGESTIONS_URL) {
                    header("Referer", "https://music.youtube.com/")
                    header("X-Origin", "https://music.youtube.com")
                    contentType(ContentType.Application.Json)
                    setBody(warmupPayload)
                }.bodyAsText()

                Log.d("SearchViewModel", "YT suggestions warm-up completed")
            } catch (e: Exception) {
                Log.w("SearchViewModel", "YT suggestions warm-up failed", e)
            }
        }
    }

    private fun normalizeSuggestionKey(query: String): String {
        return query.trim().lowercase(Locale.ROOT)
    }

    private fun getCachedSuggestions(query: String): List<String>? {
        val key = normalizeSuggestionKey(query)
        var bestPrefix: String? = null

        for (cachedKey in suggestionPrefixCache.keys) {
            if (key.startsWith(cachedKey) && (bestPrefix == null || cachedKey.length > bestPrefix.length)) {
                bestPrefix = cachedKey
            }
        }

        val prefix = bestPrefix ?: return null
        val source = suggestionPrefixCache[prefix] ?: return null
        return source.filter { it.startsWith(query, ignoreCase = true) }
    }

    private fun putCachedSuggestions(query: String, suggestions: List<String>) {
        val key = normalizeSuggestionKey(query)
        suggestionPrefixCache.remove(key)
        suggestionPrefixCache[key] = suggestions

        while (suggestionPrefixCache.size > SUGGESTION_CACHE_MAX_ENTRIES) {
            val oldestKey = suggestionPrefixCache.keys.firstOrNull() ?: break
            suggestionPrefixCache.remove(oldestKey)
        }
    }

    private suspend fun fetchSuggestions(query: String, requestNonce: Long) {
        try {
            val locale = Locale.getDefault()
            val languageTag = locale.toLanguageTag().ifBlank { "en-US" }
            val region = locale.country.ifBlank { "US" }
            val payload = """
                {
                    "input": "${query.replace("\"", "\\\"")}",
                    "context": {
                        "client": {
                            "clientName": "$YT_CLIENT_NAME",
                            "clientVersion": "$YT_CLIENT_VERSION",
                            "hl": "$languageTag",
                            "gl": "$region",
                            "platform": "DESKTOP"
                        }
                    }
                }
            """.trimIndent()

            val response = ApiClient.httpClient.post(YT_SUGGESTIONS_URL) {
                header("Accept", "*/*")
                header("Accept-Language", "$languageTag,en;q=0.9")
                header("Referer", "https://music.youtube.com/")
                header("Origin", "https://music.youtube.com")
                header("X-Origin", "https://music.youtube.com")
                header("x-youtube-client-name", YT_CLIENT_NAME_HEADER)
                header("x-youtube-client-version", YT_CLIENT_VERSION)
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
            val responseBody = response.bodyAsText()

            val json = JSONObject(responseBody)
            val results = mutableListOf<String>()
            val contents = json.optJSONArray("contents")

            if (contents != null) {
                for (i in 0 until contents.length()) {
                    val sectionContents = contents.getJSONObject(i)
                        .optJSONObject("searchSuggestionsSectionRenderer")
                        ?.optJSONArray("contents") ?: continue

                    for (j in 0 until sectionContents.length()) {
                        val suggestionRenderer = sectionContents.getJSONObject(j)
                            .optJSONObject("searchSuggestionRenderer")

                        val runs = suggestionRenderer
                            ?.optJSONObject("suggestion")
                            ?.optJSONArray("runs")
                        if (runs != null) {
                            val textBuilder = StringBuilder()
                            for (k in 0 until runs.length()) {
                                textBuilder.append(runs.getJSONObject(k).optString("text", ""))
                            }
                            val text = textBuilder.toString().trim()
                            if (text.isNotEmpty()) results.add(text)
                        }
                    }
                }
            }

            // Drop stale responses if a newer query was typed while this request was in-flight.
            if (requestNonce != suggestionRequestNonce.get()) return
            if (_uiState.value.query != query || !_uiState.value.isShowingSuggestions) return

            putCachedSuggestions(query, results)

            _uiState.value = _uiState.value.copy(
                suggestions = results
            )
        } catch (e: Exception) {
            Log.e("SearchViewModel", "Failed to fetch suggestions", e)
            _uiState.value = _uiState.value.copy(
                suggestions = emptyList()
            )
        }
    }

    fun resetToDiscovery() {
        searchJob?.cancel()
        suggestionRequestNonce.incrementAndGet()
        _uiState.value = _uiState.value.copy(query = "", suggestions = emptyList(), isShowingSuggestions = false,
            tracks = emptyList(), artists = emptyList(), playlists = emptyList(), albums = emptyList(), topResult = null,
            topArtistTracks = emptyList(), isSearching = false, error = null)
    }

    fun search(query: String) {
        val trimmedQuery = query.trim()
        searchJob?.cancel() // Cancel any pending suggestion fetch
        // Flip out of suggestion mode immediately so results can render
        _uiState.value = _uiState.value.copy(
            isShowingSuggestions = false,
            suggestions = emptyList(),
            query = trimmedQuery
        )
        if (trimmedQuery.isBlank()) {
            _uiState.value = _uiState.value.copy(
                tracks = emptyList(),
                artists = emptyList(),
                playlists = emptyList(),
                albums = emptyList(), topResult = null, topArtistTracks = emptyList()
            )
            return
        }

        AnalyticsManager.getInstance(getApplication()).trackSearchQuery(trimmedQuery)
        saveRecentSearch(trimmedQuery)

        searchJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSearching = true, error = null)
            try {
                val result = Backend.get("/alexa/search/", mapOf("q" to trimmedQuery)).objectOrEmpty()
                val liked = AccountRepository.liked.value
                fun rows(key: String) = result.array(key).mapNotNull { (it as? JsonObject)?.let { raw -> BrowseParser.item(raw, key) } }
                val all = rows("all")
                val songs = rows("songs").filter { com.example.juke.network.isAudioSearchItem(it) }
                val hero = com.example.juke.network.audioSearchHero(all, songs)
                val heroIndex = all.indexOf(hero)
                val preview = if (hero?.kind == "artist") all.drop(heroIndex + 1).takeWhile { it.kind == "track" }
                    .filter { com.example.juke.network.isAudioSearchItem(it) }.take(3).map { it.toTrack(liked) } else emptyList()
                _uiState.value = _uiState.value.copy(
                    tracks = songs.filter { it.videoId.isNotBlank() }.map { it.toTrack(liked) },
                    topResult = hero, topArtistTracks = preview,
                    artists = rows("artists").filter { it.id.isNotBlank() },
                    albums = rows("albums").filter { it.id.isNotBlank() },
                    playlists = rows("playlists").filter { it.playlistId.isNotBlank() || it.id.isNotBlank() },
                    isSearching = false
                )
                if (hero?.kind == "artist" && preview.size < 3) {
                    try {
                        val artist = Backend.get("/api/artist/${hero.id}").objectOrEmpty()
                        val topSongs = artist.array("topSongs").mapNotNull { (it as? JsonObject)?.let(BrowseParser::item) }
                            .filter { it.videoId.isNotBlank() }.take(3).map { it.toTrack(liked) }
                        if (_uiState.value.query == trimmedQuery && _uiState.value.topResult?.id == hero.id) {
                            _uiState.value = _uiState.value.copy(topArtistTracks = topSongs.ifEmpty { preview })
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* Keep the successful search when artist enrichment is unavailable. */ }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackendAuthException) {
                _uiState.value = _uiState.value.copy(isSearching = false)
                _signedOut.tryEmit(Unit)
            } catch (e: Exception) {
                val noResults = e.message?.contains("no results", ignoreCase = true) == true
                _uiState.value = _uiState.value.copy(
                    isSearching = false,
                    tracks = emptyList(), artists = emptyList(), albums = emptyList(), playlists = emptyList(), topResult = null,
                    error = if (noResults) null else e.message ?: "Search failed"
                )
            }
        }
    }

    private fun loadRecentSearches(): List<String> {
        val json = searchPrefs.getString("recent_searches", null) ?: return emptyList()
        return try {
            json.split("|||")
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveRecentSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return
        val current = loadRecentSearches().toMutableList()
        current.remove(trimmed) // remove duplicate
        current.add(0, trimmed) // add to front
        val updated = current.take(15) // keep only last 15
        searchPrefs.edit { putString("recent_searches", updated.joinToString("|||")) }
        _uiState.value = _uiState.value.copy(recentSearches = updated)
    }

    fun removeRecentSearch(query: String) {
        val current = loadRecentSearches().toMutableList()
        current.remove(query)
        searchPrefs.edit { putString("recent_searches", current.joinToString("|||")) }
        _uiState.value = _uiState.value.copy(recentSearches = current)
    }

    /** Open an artist page. [artist] carries the channel id and what is already known. */
    fun loadArtistDetails(artist: BrowseItem) {
        songsJob?.cancel()
        durationJob?.cancel()
        artistJob?.cancel()
        _artistDetailState.value = ArtistDetailUiState(artist = artist, imageUrl = artist.image, isLoading = true)
        artistJob = viewModelScope.launch {
            try {
                val data = Backend.get("/api/artist/${artist.id}").objectOrEmpty()
                val info = data["artist"].objectOrEmpty()
                val liked = AccountRepository.liked.value
                fun releases(key: String) = data.array(key).mapNotNull { (it as? JsonObject)?.let { raw -> BrowseParser.item(raw, if (key == "playlists") "" else "albums") } }
                    .filter { it.id.isNotBlank() }
                val name = info.text("name").ifBlank { artist.title }
                _artistDetailState.value = ArtistDetailUiState(
                    artist = artist.copy(title = name),
                    imageUrl = imageUrl(info["thumbnails"]).ifBlank { artist.image },
                    subscribers = info.text("subscribers"),
                    description = info.text("description"),
                    topTracks = data.array("topSongs").mapNotNull { (it as? JsonObject)?.let(BrowseParser::item) }
                        .filter { it.videoId.isNotBlank() }.map { it.toTrack(liked) },
                    topSongsBrowseId = data.text("topSongsBrowseId"),
                    albums = releases("albums"),
                    singles = releases("singles"),
                    playlists = releases("playlists"),
                    albumsHasMore = info["albums"].objectOrEmpty().text("params").isNotBlank(),
                    singlesHasMore = info["singles"].objectOrEmpty().text("params").isNotBlank(),
                    related = data.array("related").mapNotNull { (it as? JsonObject)?.let { row -> BrowseParser.item(row, "related") } }.filter { it.id.isNotBlank() },
                    isSubscribed = null,
                    isLoading = false
                )
                loadAllArtistSongs()
                if (_artistDetailState.value.topSongsBrowseId.isBlank()) enrichPreviewDurations(artist.id)
                val subscribed = subscribedArtists()?.contains(artist.id)
                _artistDetailState.update { it.copy(isSubscribed = subscribed) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackendAuthException) {
                _artistDetailState.update { it.copy(isLoading = false) }
                _signedOut.tryEmit(Unit)
            } catch (e: Exception) {
                _artistDetailState.update { it.copy(isLoading = false, error = com.example.juke.network.networkErrorMessage(e) ?: e.message ?: "Failed to load artist") }
            }
        }
    }

    fun loadAllArtistSongs() {
        val state = _artistDetailState.value
        val artist = state.artist ?: return
        if (state.songsLoading || state.allSongsLoaded || state.topSongsBrowseId.isBlank()) return
        _artistDetailState.update { it.copy(songsLoading = true, error = null) }
        songsJob = viewModelScope.launch {
            try {
                val data = Backend.get("/api/library/playlists/${state.topSongsBrowseId}",
                    mapOf("offset" to state.songsNextOffset.toString(), "limit" to "30")).objectOrEmpty()
                val songs = data.array("tracks").mapNotNull { (it as? JsonObject)?.let(BrowseParser::item) }
                    .filter { it.videoId.isNotBlank() }.map { it.toTrack(AccountRepository.liked.value) }
                if (_artistDetailState.value.artist?.id != artist.id) return@launch
                _artistDetailState.update { current ->
                    val loaded = (current.allTracks + songs).distinctBy { it.ytVideoId }
                    val durations = loaded.filter { it.durationSec > 0 }.associateBy { it.ytVideoId }
                    current.copy(allTracks = loaded,
                        topTracks = current.topTracks.map { track -> durations[track.ytVideoId]?.let { track.copy(durationSec = it.durationSec) } ?: track },
                        songsNextOffset = data.number("next_offset").takeIf { it > state.songsNextOffset } ?: state.songsNextOffset + songs.size,
                        allSongsLoaded = !data.flag("has_more") || songs.isEmpty())
                }

            } catch (e: CancellationException) { throw e }
            catch (e: BackendAuthException) { _signedOut.tryEmit(Unit) }
            catch (e: Exception) { _artistDetailState.update { it.copy(error = com.example.juke.network.networkErrorMessage(e) ?: e.message ?: "Could not load artist songs") } }
            finally {
                if (_artistDetailState.value.artist?.id == artist.id) {
                    _artistDetailState.update { it.copy(songsLoading = false) }
                    if (state.songsNextOffset == 0L) enrichPreviewDurations(artist.id)
                }
            }
        }
    }

    private fun enrichPreviewDurations(artistId: String) {
        val missing = _artistDetailState.value.topTracks.take(5).filter { it.durationSec <= 0 }
        if (missing.isEmpty()) return
        durationJob?.cancel()
        _artistDetailState.update { it.copy(durationsLoading = true) }
        durationJob = viewModelScope.launch {
            try {
                for (track in missing) {
                    if (_artistDetailState.value.artist?.id != artistId) break
                    val seconds = try {
                        val metadata = Backend.get("/api/track/${track.ytVideoId}/metadata").objectOrEmpty()
                        (metadata.number("duration_ms") / 1000).toInt()
                    } catch (e: CancellationException) { throw e }
                    catch (e: BackendAuthException) { _signedOut.tryEmit(Unit); break }
                    catch (_: Exception) {
                        try {
                            val result = Backend.get("/alexa/search/", mapOf("q" to track.ytVideoId.orEmpty())).objectOrEmpty()
                            (result.array("songs") + result.array("all")).mapNotNull { (it as? JsonObject)?.let(BrowseParser::item) }
                                .firstOrNull { it.videoId == track.ytVideoId && it.durationMs > 0 }?.durationMs?.div(1000)?.toInt() ?: 0
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { 0 }
                    }
                    if (seconds > 0 && _artistDetailState.value.artist?.id == artistId) _artistDetailState.update { current ->
                        current.copy(topTracks = current.topTracks.map { if (it.ytVideoId == track.ytVideoId) it.copy(durationSec = seconds) else it })
                    }
                }
            } finally {
                if (_artistDetailState.value.artist?.id == artistId) _artistDetailState.update { it.copy(durationsLoading = false) }
            }
        }
    }

    fun loadArtistDetailsById(artistId: String, name: String = "") {
        loadArtistDetails(BrowseItem(artistId, "artist", name, "", "", "", "", 0, artistId, "", false, JsonObject(emptyMap())))
    }

    /** Resolve an artist by name (songs from a radio may carry only the name), then open it. */
    fun loadArtistDetailsByName(name: String) {
        artistJob?.cancel()
        _artistDetailState.value = ArtistDetailUiState(isLoading = true)
        artistJob = viewModelScope.launch {
            try {
                val id = Backend.get("/api/artist/resolve/", mapOf("name" to name)).objectOrEmpty()
                    .text("channel_id", "artist_id", "id")
                if (id.isBlank()) {
                    _artistDetailState.update { it.copy(isLoading = false, error = "Artist not found") }
                } else {
                    loadArtistDetailsById(id, name)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _artistDetailState.update { it.copy(isLoading = false, error = com.example.juke.network.networkErrorMessage(e) ?: e.message ?: "Artist not found") }
            }
        }
    }

    private suspend fun subscribedArtists(): Set<String>? = try {
        Backend.get("/api/subscribed_artists/").objectOrEmpty().array("artists")
            .map { it.objectOrEmpty().text("channel_id") }.filter { it.isNotBlank() }.toSet()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** Subscribe to or unsubscribe from the artist on YouTube Music. */
    fun toggleSubscription() {
        val state = _artistDetailState.value
        val artist = state.artist ?: return
        if (state.subscriptionBusy || state.isSubscribed == null) return
        val subscribe = state.isSubscribed != true
        viewModelScope.launch {
            _artistDetailState.update { it.copy(isSubscribed = subscribe, subscriptionBusy = true) }
            try {
                val body = JsonObject(mapOf("channel_id" to kotlinx.serialization.json.JsonPrimitive(artist.id)))
                if (subscribe) Backend.post("/api/subscribed_artists/", body) else Backend.delete("/api/subscribed_artists/", body)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _artistDetailState.update { it.copy(isSubscribed = !subscribe, error = e.message) }
            } finally {
                _artistDetailState.update { it.copy(subscriptionBusy = false) }
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun clearArtistDetail() {
        songsJob?.cancel()
        durationJob?.cancel()
        artistJob?.cancel()
        _artistDetailState.value = ArtistDetailUiState()
    }
}
