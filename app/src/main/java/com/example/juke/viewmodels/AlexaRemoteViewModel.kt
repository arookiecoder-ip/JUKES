package com.example.juke.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.juke.network.*
import io.ktor.http.HttpMethod
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

data class RemoteDestination(val title: String, val path: String, val query: Map<String, String> = emptyMap(), val entity: RemoteItem? = null)
data class AlexaRemoteState(
    val page: RemotePage? = null, val destination: RemoteDestination = RemoteDestination("Home", "/api/home/"),
    val devices: List<JsonObject> = emptyList(), val serial: String = "", val nowPlaying: JsonObject = JsonObject(emptyMap()),
    val profile: JsonObject = JsonObject(emptyMap()), val jam: JsonObject = JsonObject(emptyMap()),
    val suggestions: List<String> = emptyList(), val liked: Set<String> = emptySet(),
    val busy: Boolean = false, val error: String? = null, val notice: String? = null, val authToken: String = "",
    val browserUrl: String? = null, val canGoBack: Boolean = false, val qr: String = "", val guest: Boolean = false
)

class AlexaRemoteViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("music_settings_prefs", 0)
    private val authPrefs = app.getSharedPreferences("alexa_remote_auth", 0)
    private var isVisible = false
    private var api: AlexaRemoteApi? = null
    private var server = ""
    private var key = ""
    private val _state = MutableStateFlow(AlexaRemoteState())
    val state = _state.asStateFlow()
    private val history = mutableListOf<RemoteDestination>()
    private val commands = Mutex()
    private var poll: Job? = null
    private var browse: Job? = null
    private var suggest: Job? = null
    private var version = 0

    fun configure(address: String) {
        val newKey = authPrefs.getString("api_key", "").orEmpty()
        if (api != null && address == server && newKey == key) return
        poll?.cancel(); browse?.cancel(); api?.close(); version++
        server = AlexaRemotePolicy.server(address); key = newKey
        api = AlexaRemoteApi(server, key)
        history.clear(); _state.value = AlexaRemoteState()
        initialize(); active(isVisible)
    }
    fun saveKey(value: String) {
        authPrefs.edit().putString("api_key", value.trim()).apply()
        configure(server)
    }
    private suspend fun get(path: String, query: Map<String, String> = emptyMap()) = requireNotNull(api).request(path, query = query)
    private suspend fun send(path: String, body: JsonObject = JsonObject(emptyMap()), method: HttpMethod = HttpMethod.Post) = requireNotNull(api).request(path, method, body)
    private fun launchAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            commands.withLock {
                _state.update { it.copy(busy = true, error = null, notice = null) }
                try { block() } catch (e: CancellationException) { throw e }
                catch (e: Exception) { _state.update { it.copy(error = e.message ?: "Request failed") } }
                finally { _state.update { it.copy(busy = false) } }
            }
        }
    }
    fun initialize() = launchAction {
        val init = get(if(_state.value.guest) "/api/jam/session/" else "/alexa/init/").objectOrEmpty()
        val devices = init.array("devices").map { it.objectOrEmpty() }
        val saved = prefs.getString("alexa_remote_device", "").orEmpty()
        val serial = devices.firstOrNull { it.text("serial") == saved }?.text("serial") ?: init.text("serial")
        _state.update { it.copy(devices = devices, serial = serial, nowPlaying = init["now_playing"].objectOrEmpty()) }
        if (!_state.value.guest) { refreshProfileInternal(); refreshLikes() }; navigate(_state.value.destination, replace = true)
    }
    private suspend fun refreshProfileInternal() {
        val profile = get("/api/profile_status/").objectOrEmpty()
        _state.update { it.copy(profile = profile) }
    }
    private suspend fun refreshLikes() {
        val liked = get("/api/liked_songs/").objectOrEmpty().array("liked_songs").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
        _state.update { it.copy(liked = liked) }
    }
    fun refreshProfile() = launchAction { refreshProfileInternal() }
    fun active(enabled: Boolean) {
        isVisible = enabled
        poll?.cancel(); poll = null
        if (enabled) poll = viewModelScope.launch {
            while (isActive) {
                delay(2500)
                try { commands.withLock { refreshNow() } }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { _state.update { it.copy(error = e.message) }; delay(5000) }
            }
        }
    }
    private suspend fun refreshNow() {
        val serial = _state.value.serial
        if (serial.isBlank()) return
        val np = get("/alexa/now_playing/", mapOf("serial" to serial)).objectOrEmpty()
        if (serial == _state.value.serial) _state.update {
            it.copy(nowPlaying = np, error = np["playback_error"].objectOrEmpty().text("message").takeIf(String::isNotBlank) ?: it.error)
        }
    }
    fun selectDevice(serial: String) = launchAction {
        prefs.edit().putString("alexa_remote_device", serial).apply()
        _state.update { it.copy(serial = serial, nowPlaying = JsonObject(emptyMap())) }; refreshNow()
    }
    fun refreshDevices() = initialize()
    fun navigate(destination: RemoteDestination, replace: Boolean = false) {
        if (!replace) history.add(_state.value.destination)
        _state.update { it.copy(destination = destination, canGoBack = history.isNotEmpty(), page = null, error = null) }
        loadPage()
    }
    fun back() {
        if (history.isEmpty()) return
        navigate(history.removeAt(history.lastIndex), replace = true)
    }
    fun tab(name: String) {
        history.clear()
        val path = when (name) { "Library" -> "/api/library/"; "Explore" -> "/api/explore/"; "History" -> "/history/"; "Artists" -> "/api/subscribed_artists/"; "Liked music" -> "/api/library/playlists/LM"; else -> if(_state.value.guest) "/api/jam/home/" else "/api/home/" }
        navigate(RemoteDestination(name, path), true)
    }
    fun loadPage(more: Boolean = false) {
        browse?.cancel()
        val generation = ++version
        val dest = _state.value.destination
        val previous = _state.value.page
        browse = viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val query = dest.query + if (more) mapOf("offset" to (previous?.nextOffset ?: 0).toString(), "limit" to "30") else emptyMap()
                var page = AlexaRemoteParser.page(get(dest.path, query), dest.title)
                if (more && previous != null) {
                    val tracks = previous.shelves.flatMap { it.items } + page.shelves.flatMap { it.items }
                    page = page.copy(shelves = listOf(RemoteShelf("Tracks", tracks)))
                }
                if (generation == version) _state.update { it.copy(page = page) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == version) _state.update { it.copy(error = e.message) } }
            finally { if (generation == version) _state.update { it.copy(busy = false) } }
        }
    }
    fun search(query: String) {
        if (query.isBlank()) return
        suggest?.cancel(); _state.update { it.copy(suggestions = emptyList()) }
        navigate(RemoteDestination("Search: $query", "/alexa/search/", mapOf("q" to query)))
    }
    fun suggestions(query: String) {
        suggest?.cancel()
        if (query.isBlank() || query.startsWith("http")) { _state.update { it.copy(suggestions = emptyList()) }; return }
        suggest = viewModelScope.launch {
            delay(350)
            try {
                val data = get("/alexa/suggest/", mapOf("q" to query))
                val values = data as? JsonArray ?: data.objectOrEmpty().array("suggestions")
                _state.update { it.copy(suggestions = values.mapNotNull { value -> (value as? JsonPrimitive)?.contentOrNull ?: value.objectOrEmpty().text("query", "text").takeIf(String::isNotBlank) }) }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
    }
    fun open(item: RemoteItem) {
        when (item.kind) {
            "track" -> play(item)
            "mood" -> navigate(RemoteDestination(item.title, "/api/explore/moods/", mapOf("params" to item.raw.text("params"), "title" to item.title), item))
            "artist" -> navigate(RemoteDestination(item.title, "/api/artist/${item.id}", entity = item))
            "album" -> navigate(RemoteDestination(item.title, "/api/album/${item.id}", entity = item))
            else -> navigate(RemoteDestination(item.title, "/api/library/playlists/${item.playlistId.ifBlank { item.id }}", entity = item))
        }
    }
    private fun serial(): String = _state.value.serial.takeIf(String::isNotBlank) ?: error("Select a connected Echo device in Player.")
    fun command(action: String, value: Long? = null) = launchAction {
        send("/alexa/command/", buildJsonObject { put("serial", serial()); put("action", action); value?.let { put("value", it) } }); refreshNow()
    }
    fun seek(position: Long) = launchAction { send("/alexa/seek/", buildJsonObject { put("serial", serial()); put("position_ms", position) }); refreshNow() }
    fun play(item: RemoteItem, radio: Boolean = false, index: Int? = null) = launchAction {
        if (item.videoId.isNotBlank()) send("/alexa/play_queue/", buildJsonObject {
            item.metadata().forEach { (k,v) -> put(k,v) }; put("serial", serial()); put("force_radio", radio)
            index?.let { put("queue_index", it) }
            val collection = _state.value.destination.entity
            if(!radio && index == null && collection != null) {
                if(collection.kind == "playlist") put("playlist_id", collection.playlistId)
                else if(collection.kind in listOf("album", "artist")) put("queue_items", JsonArray(_state.value.page?.shelves.orEmpty().flatMap { it.items }.filter { it.videoId.isNotBlank() }.map { it.metadata() }))
                put("suppress_radio", true)
            }
        }) else {
            val pl = item.playlistId.ifBlank { error("Open this collection to choose a song.") }
            send("/alexa/play/", buildJsonObject { put("serial", serial()); put("query", "https://music.youtube.com/playlist?list=$pl") })
        }; refreshNow()
    }
    fun playLink(query: String) = launchAction { send("/alexa/play/", buildJsonObject { put("serial", serial()); put("query", query) }); refreshNow() }
    fun queue(item: RemoteItem, next: Boolean = false) = launchAction {
        send("/alexa/queue_add/", buildJsonObject {
            item.metadata().forEach { (k,v) -> put(k,v) }; put("serial", serial()); put("position", if(next) "next" else "last")
            if (item.videoId.isBlank()) {
                if (item.playlistId.isNotBlank()) put("playlist_id", item.playlistId)
                else put("queue_items", JsonArray(_state.value.page?.shelves.orEmpty().flatMap { it.items }.filter { it.videoId.isNotBlank() }.map { it.metadata() }))
            }
        }); refreshNow()
    }
    fun queueEdit(action: String, index: Int = 0, to: Int = 0) = launchAction {
        val path = when(action) { "remove" -> "queue_remove"; "move" -> "queue_reorder"; "shuffle" -> "shuffle_queue"; else -> "clear" }
        send("/alexa/$path/", buildJsonObject {
            put("serial", serial()); if(action == "remove") {
                put("index", index)
                _state.value.nowPlaying.array("queue").getOrNull(index)?.objectOrEmpty()?.text("video_id", "videoId")?.let { put("video_id", it) }
            }
            if(action == "move") { put("from_index", index); put("to_index", to) }
        }); refreshNow()
    }
    fun like(item: RemoteItem, liked: Boolean) = launchAction {
        send("/alexa/like/", buildJsonObject { put("video_id", item.videoId); put("action", if(liked) "LIKE" else "INDIFFERENT") }); refreshLikes()
    }
    fun createPlaylist(name: String) = launchAction { send("/api/library/playlists/", buildJsonObject { put("name", name) }); tab("Library") }
    fun renamePlaylist(item: RemoteItem, name: String) = launchAction { send("/api/library/playlists/${item.playlistId}", buildJsonObject { put("title", name) }, HttpMethod.Patch); tab("Library") }
    fun deletePlaylist(item: RemoteItem) = launchAction { send("/api/library/playlists/${item.playlistId}", method = HttpMethod.Delete); tab("Library") }
    fun addToPlaylist(item: RemoteItem, playlist: RemoteItem) = launchAction {
        send("/api/library/playlists/${playlist.playlistId}/tracks", buildJsonObject { put("video_id", item.videoId) }); _state.update { it.copy(notice = "Added to ${playlist.title}") }
    }
    suspend fun playlists(): List<RemoteItem> = AlexaRemoteParser.page(get("/api/library/"), "Library").shelves.flatMap { it.items }.filter { it.editable }
    fun subscribe(item: RemoteItem, subscribe: Boolean) = launchAction {
        send("/api/subscribed_artists/", buildJsonObject { put("channel_id", item.id) }, if(subscribe) HttpMethod.Post else HttpMethod.Delete); _state.update { it.copy(notice = if(subscribe) "Artist subscribed" else "Artist unsubscribed") }
    }
    fun artist(item: RemoteItem) = launchAction {
        val id = item.artistId.ifBlank { get("/api/artist/resolve/", mapOf("name" to item.subtitle)).objectOrEmpty().text("channel_id", "artist_id", "id") }
        require(id.isNotBlank()) { "Artist unavailable for this song" }; open(item.copy(id = id, kind = "artist", title = item.subtitle))
    }
    fun album(item: RemoteItem) = launchAction {
        val id = item.albumId.ifBlank { get("/api/album/resolve/${item.videoId}").objectOrEmpty().text("album_id") }
        require(id.isNotBlank()) { "Album unavailable for this song" }; open(item.copy(id = id, kind = "album"))
    }
    fun artistSongs() {
        val id = _state.value.destination.entity?.id ?: return
        val browseId = _state.value.page?.raw?.text("topSongsBrowseId").orEmpty()
        if (browseId.isNotBlank()) navigate(RemoteDestination("All songs", "/api/artist/$id/songs", mapOf("browse_id" to browseId)))
    }
    fun jam(action: String) = launchAction {
        val result = if(action == "status") get("/alexa/jam/status/") else send("/alexa/jam/$action/")
        val data = result.objectOrEmpty()
        _state.update { it.copy(jam = data, qr = "") }
        if(data.flag("active")) { val svg = requireNotNull(api).qr(); _state.update { it.copy(qr = svg) } }
    }
    fun joinJam(url: String) = launchAction {
        require(AlexaRemotePolicy.trusted(server, url)) { "Use a Jam invitation from the selected server." }
        val guestApi = AlexaRemoteApi(server, "")
        try { guestApi.join(java.net.URI(url).path) } catch(e: Exception) { guestApi.close(); throw e }
        api?.close(); api = guestApi
        history.clear(); _state.value = AlexaRemoteState(guest = true, destination = RemoteDestination("Home", "/api/jam/home/"))
        initialize()
    }
    fun leaveJam() = launchAction {
        send("/api/jam/leave/"); api?.close(); api = AlexaRemoteApi(server,key)
        _state.value = AlexaRemoteState(); initialize()
    }
    fun login(username: String, password: String, code: String = "") = launchAction {
        val result = send("/login/", buildJsonObject {
            if (_state.value.authToken.isNotBlank()) { put("step", "totp"); put("token", _state.value.authToken); put("code", code) }
            else { put("username", username); put("password", password) }
        }).objectOrEmpty()
        _state.update { it.copy(authToken = if(result.flag("totp_required")) result.text("token") else "", notice = if(result.flag("totp_required")) "Enter your authentication code" else "Owner signed in") }
        if (!result.flag("totp_required")) initialize()
    }
    fun logout() = launchAction { send("/logout/"); api?.close(); api = AlexaRemoteApi(server, key); _state.value = AlexaRemoteState(notice = "Owner signed out") }
    fun amazon(email: String, password: String) = launchAction {
        val result = send("/alexa/proxy_login/", buildJsonObject { put("email", email); put("password", password) }).objectOrEmpty()
        _state.update { it.copy(browserUrl = result.text("login_url")) }
    }
    fun amazonSignOut() = launchAction { send("/alexa/amazon_signout/"); initialize() }
    fun youtube(action: String) = launchAction {
        val result = if(action == "status") get("/api/youtube/browser-session/status") else send("/api/youtube/browser-session/$action")
        _state.update { it.copy(notice = result.objectOrEmpty().text("message", "status"), browserUrl = result.objectOrEmpty().text("url").takeIf(String::isNotBlank)) }
        refreshProfileInternal()
    }
    suspend fun browserCookies(url: String): List<String> = requireNotNull(api).browserCookies(url)
    fun browserFinished(url: String, cookies: String) = launchAction {
        require(AlexaRemotePolicy.trusted(server, url))
        requireNotNull(api).importBrowserCookies(url, cookies)
        refreshProfileInternal(); initialize()
    }
    fun browserConsumed() { _state.update { it.copy(browserUrl = null) } }
    fun dismiss() { _state.update { it.copy(error = null, notice = null) } }
    override fun onCleared() { api?.close(); super.onCleared() }
}
