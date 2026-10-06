package com.example.juke.services

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.example.juke.models.Track
import com.example.juke.network.BrowseItem
import com.example.juke.network.AlexaBackendApi
import com.example.juke.network.NetworkFeedback
import com.example.juke.network.text
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable private data class DownloadEntry(val id: Long, val track: Track)
data class DownloadStatus(val active: Int = 0, val total: Int = 0, val completed: Int = 0,
    val failed: Int = 0, val title: String = "", val percent: Int = -1)

/** System-managed audio transfers with bounded concurrency and app-owned download notifications. */
class DownloadRepository private constructor(private val context: Context) {
    companion object {
        @Volatile private var instance: DownloadRepository? = null
        fun get(context: Context): DownloadRepository = instance ?: synchronized(this) {
            instance ?: DownloadRepository(context.applicationContext).also { instance = it }
        }
    }
    private val manager = context.getSystemService(DownloadManager::class.java)
    private val prefs = context.getSharedPreferences("account_downloads", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pending = readEntries("pending").toMutableList()
    private val completed = readEntries("completed").filter { file(it.track)?.let { f -> f.exists() && f.length() > 0 } == true }.toMutableList()
    private val queued = runCatching { json.decodeFromString<List<Track>>(prefs.getString("queued", "[]").orEmpty()) }
        .getOrDefault(emptyList()).associateBy { it.ytVideoId.orEmpty() }.toMutableMap()
    private val starting = mutableSetOf<String>()
    private val savedCollections = runCatching { json.decodeFromString<List<DownloadedCollection>>(prefs.getString("collections", "[]").orEmpty()) }
        .getOrDefault(emptyList()).toMutableList()
    @Volatile private var completedByVideo = completed.map { it.track }.associateBy { it.ytVideoId }
    private val _tracks = MutableStateFlow(completed.map { it.track })
    val tracks = _tracks.asStateFlow()
    private val _progress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val progress = _progress.asStateFlow()
    private val _activeTracks = MutableStateFlow<List<Track>>(emptyList())
    val activeTracks = _activeTracks.asStateFlow()
    private val _collections = MutableStateFlow(savedCollections.toList())
    val collections = _collections.asStateFlow()
    private val _status = MutableStateFlow(DownloadStatus())
    val status = _status.asStateFlow()
    private var batchTotal = pending.size + queued.size
    private var batchCompleted = 0
    private var batchFailed = 0
    private var polling: Job? = null

    init {
        ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) poll()
            }
        }, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
        // Do not automatically download intentionally removed collection songs again on launch.
        publishStatus(); pump(); poll()
        if (_status.value.active > 0) DownloadService.start(context)
    }
    private fun readEntries(key: String): List<DownloadEntry> = runCatching {
        json.decodeFromString<List<DownloadEntry>>(prefs.getString(key, "[]").orEmpty())
    }.getOrDefault(emptyList())
    private fun file(track: Track): File? = track.localUri?.let { File(it.toUri().path ?: it) }
    fun localTrack(track: Track): Track? = completedByVideo[track.ytVideoId]?.takeIf {
        file(it)?.let { f -> f.exists() && f.length() > 0 } == true
    }?.let { track.copy(localUri = it.localUri, isStream = false) }

    fun download(track: Track, silent: Boolean = false) {
        if (localTrack(track) != null) { if (!silent) NetworkFeedback.notify("Already downloaded"); return }
        if (enqueue(listOf(track)) && !silent) NetworkFeedback.notify("Downloading ${track.title}")
    }
    private fun enqueue(tracks: List<Track>): Boolean {
        val pendingIds = pending.map { it.track.ytVideoId }.toSet()
        val additions = tracks.filter { it.ytVideoId?.matches(Regex("[A-Za-z0-9_-]{11}")) == true &&
            it.ytVideoId !in queued && it.ytVideoId !in pendingIds && localTrack(it) == null }.distinctBy { it.ytVideoId }
        if (additions.isEmpty()) return false
        if (_status.value.active == 0) { batchTotal = 0; batchCompleted = 0; batchFailed = 0 }
        additions.forEach { queued[requireNotNull(it.ytVideoId)] = it }; batchTotal += additions.size
        save(); publishStatus(); DownloadService.start(context); pump()
        return true
    }
    private fun pump() {
        val slots = (3 - pending.size - starting.size).coerceAtLeast(0)
        queued.filterKeys { it !in starting }.entries.take(slots).forEach { (video, track) ->
            starting += video
            scope.launch {
                try {
                    check(AlexaBackendApi.isConfigured()) { "Device downloads are unavailable in this build" }
                    val directory = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: error("Device storage is unavailable")
                    val target = File(directory, "$video.audio")
                    val id = withContext(Dispatchers.IO) {
                        directory.mkdirs(); target.delete()
                        manager.enqueue(DownloadManager.Request(AlexaBackendApi.audioUrl(video).toUri())
                            .addRequestHeader("X-Api-Key", com.example.juke.network.Backend.apiKey)
                            .setTitle(track.title).setDescription(track.artist)
                            // The app notification opens Downloads, never an incomplete/raw audio file.
                            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
                            .setDestinationUri(target.toUri()))
                    }
                    if (video in queued) pending += DownloadEntry(id, track.copy(localUri = target.toUri().toString(), isStream = false))
                    else withContext(Dispatchers.IO) { manager.remove(id); target.delete() }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { batchFailed++; NetworkFeedback.notify("Couldn't download ${track.title}. Please try again.") }
                finally { starting -= video; queued.remove(video); save(); publishStatus(); poll(); pump() }
            }
        }
    }
    fun downloadCollection(item: BrowseItem, tracks: List<Track>) {
        val eligible = tracks.filter { it.ytVideoId?.matches(Regex("[A-Za-z0-9_-]{11}")) == true }
        check(eligible.isNotEmpty()) { "This collection has no downloadable songs" }
        val collection = DownloadedCollection(item.id, item.kind, item.title, item.image, item.subtitle, eligible, item.raw.text("year"), item.raw.text("description"))
        savedCollections.removeAll { it.key == collection.key }; savedCollections += collection
        save(); retryCollection(collection)
        NetworkFeedback.notify("Downloading ${collection.title}")
    }
    fun updateCollectionDetails(item: BrowseItem) {
        val index = savedCollections.indexOfFirst { it.id == item.id && it.kind == item.kind }
        if (index < 0) return
        savedCollections[index] = savedCollections[index].copy(title = item.title, image = item.image,
            subtitle = item.subtitle, year = item.raw.text("year"), description = item.raw.text("description"))
        save()
    }
    fun retryCollection(collection: DownloadedCollection) { enqueue(collection.tracks) }
    fun removeCollection(collection: DownloadedCollection) {
        savedCollections.removeAll { it.key == collection.key }
        val retained = savedCollections.flatMap { it.tracks }.map { it.ytVideoId }.toSet()
        save(); removeAll(collection.tracks.filter { it.ytVideoId !in retained })
    }
    fun remove(track: Track) = removeAll(listOf(track))
    fun removeAll(tracks: List<Track>) {
        val videos = tracks.mapNotNull { it.ytVideoId }.toSet()
        // Collection membership deliberately survives deletion, so re-download rejoins its collection.
        videos.forEach(queued::remove)
        scope.launch {
            val entries = (pending + completed).filter { it.track.ytVideoId in videos }
            pending.removeAll(entries.toSet()); completed.removeAll(entries.toSet())
            save(); publishStatus()
            withContext(Dispatchers.IO) { entries.forEach { manager.remove(it.id); file(it.track)?.delete() } }
            pump(); NetworkFeedback.notify(if (videos.size == 1) "Download removed" else "${videos.size} downloads removed")
        }
    }
    fun cancelAll() { removeAll(pending.map { it.track } + queued.values) }
    private fun save() {
        prefs.edit().putString("pending", json.encodeToString(pending.toList()))
            .putString("completed", json.encodeToString(completed.toList()))
            .putString("queued", json.encodeToString(queued.values.toList()))
            .putString("collections", json.encodeToString(savedCollections.toList())).apply()
        _collections.value = savedCollections.toList(); _tracks.value = completed.map { it.track }
        completedByVideo = _tracks.value.associateBy { it.ytVideoId }
    }
    private fun publishStatus(percentages: Map<String, Int> = _progress.value) {
        val activeTracks = (pending.map { it.track } + queued.values).distinctBy { it.ytVideoId }
        _activeTracks.value = activeTracks
        val ids = activeTracks.mapNotNull { it.ytVideoId }
        _progress.value = ids.associateWith { percentages[it] ?: -1 }
        val known = _progress.value.values.filter { it >= 0 }
        _status.value = DownloadStatus(activeTracks.size, batchTotal, batchCompleted, batchFailed,
            activeTracks.firstOrNull()?.title.orEmpty(),
            if (known.isEmpty() || batchTotal == 0) -1 else ((batchCompleted * 100 + known.sum()) / batchTotal).coerceIn(0, 100))
    }
    private fun poll() {
        if (polling?.isActive == true || pending.isEmpty()) return
        polling = scope.launch {
            while (pending.isNotEmpty()) {
                val percentages = mutableMapOf<String, Int>()
                var changed = false
                for (entry in pending.toList()) {
                    val result = withContext(Dispatchers.IO) {
                        manager.query(DownloadManager.Query().setFilterById(entry.id)).use { cursor ->
                            if (!cursor.moveToFirst()) Triple(DownloadManager.STATUS_FAILED, 0L, 0L)
                            else Triple(cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                                cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                                cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)))
                        }
                    }
                    if (entry !in pending) continue
                    val (status, bytes, total) = result
                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            pending.remove(entry); changed = true
                            if (file(entry.track)?.let { it.exists() && it.length() > 0 } == true) {
                                completed.removeAll { it.track.ytVideoId == entry.track.ytVideoId }; completed += entry; batchCompleted++
                            } else { batchFailed++; NetworkFeedback.notify("Couldn't save ${entry.track.title}. Please download it again.") }
                        }
                        DownloadManager.STATUS_FAILED -> {
                            pending.remove(entry); batchFailed++; changed = true
                            withContext(Dispatchers.IO) { manager.remove(entry.id); file(entry.track)?.delete() }
                            NetworkFeedback.notify("Couldn't download ${entry.track.title}. Check your connection and try again.")
                        }
                        else -> percentages[entry.track.ytVideoId.orEmpty()] = if (total > 0) (bytes * 100 / total).toInt().coerceIn(0, 100) else -1
                    }
                }
                if (changed) save()
                pump(); publishStatus(percentages)
                if (pending.isNotEmpty()) delay(1_000)
            }
            polling = null
            if (pending.isNotEmpty()) poll()
        }
    }
}
