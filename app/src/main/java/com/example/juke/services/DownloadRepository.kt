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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
private data class DownloadEntry(val id: Long, val track: Track)

/** Downloads are offline copies of account songs, keyed by YouTube video id. */
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
    private val pending = read("pending").toMutableList()
    private val completed = read("completed").filter { file(it.track)?.let { f -> f.exists() && f.length() > 0 } == true }.toMutableList()
    private val _tracks = MutableStateFlow(completed.map { it.track })
    val tracks = _tracks.asStateFlow()
    private val _progress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val progress = _progress.asStateFlow()
    private val savedCollections = runCatching {
        json.decodeFromString<List<DownloadedCollection>>(prefs.getString("collections", "[]").orEmpty())
    }.getOrDefault(emptyList()).toMutableList()
    private val _collections = MutableStateFlow(savedCollections.toList())
    val collections = _collections.asStateFlow()
    private val collectionJobs = mutableMapOf<String, Job>()
    private val starting = mutableSetOf<String>()
    private var polling: Job? = null

    init {
        ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) poll()
            }
        }, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
        poll()
        savedCollections.forEach(::retryCollection)
    }

    private fun read(key: String): List<DownloadEntry> = runCatching {
        json.decodeFromString<List<DownloadEntry>>(prefs.getString(key, "[]").orEmpty())
    }.getOrDefault(emptyList())
    private fun file(track: Track): File? = track.localUri?.let { File(it.toUri().path ?: it) }
    fun localTrack(track: Track): Track? = _tracks.value.firstOrNull {
        it.ytVideoId == track.ytVideoId && file(it)?.let { f -> f.exists() && f.length() > 0 } == true
    }?.let { track.copy(localUri = it.localUri, isStream = false) }

    fun download(track: Track, silent: Boolean = false) {
        val video = track.ytVideoId?.takeIf { Regex("[A-Za-z0-9_-]{11}").matches(it) } ?: return
        if (localTrack(track) != null) { if (!silent) NetworkFeedback.notify("Already downloaded"); return }
        if (pending.any { it.track.ytVideoId == video } || video in _progress.value) return
        starting += video
        _progress.value = _progress.value + (video to -1)
        scope.launch {
            try {
                check(AlexaBackendApi.isConfigured()) { "Device downloads are unavailable in this build" }
                val directory = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                    ?: error("Device storage is unavailable")
                val target = File(directory, "$video.audio")
                val id = withContext(Dispatchers.IO) {
                    directory.mkdirs()
                    target.delete()
                    manager.enqueue(DownloadManager.Request((AlexaBackendApi.audioUrl(video) + "&wait=1").toUri())
                        .addRequestHeader("X-Api-Key", com.example.juke.network.Backend.apiKey)
                        .setTitle(track.title).setDescription(track.artist)
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationUri(target.toUri()))
                }
                starting -= video
                pending += DownloadEntry(id, track.copy(localUri = target.toUri().toString(), isStream = false))
                save(); poll()
                if (!silent) NetworkFeedback.notify("Downloading ${track.title}")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                starting -= video
                _progress.value = _progress.value - video
                NetworkFeedback.notify("Couldn't start download: ${e.message ?: "Please try again"}")
            }
        }
    }

    fun downloadCollection(item: BrowseItem, tracks: List<Track>) {
        val eligible = tracks.filter { it.ytVideoId?.matches(Regex("[A-Za-z0-9_-]{11}")) == true }.distinctBy { it.ytVideoId }
        check(eligible.isNotEmpty()) { "This collection has no downloadable songs" }
        val collection = DownloadedCollection(item.id, item.kind, item.title, item.image, item.subtitle, eligible)
        savedCollections.removeAll { it.key == collection.key }
        savedCollections += collection
        save()
        retryCollection(collection)
        NetworkFeedback.notify("Downloading ${collection.title}")
    }

    fun retryCollection(collection: DownloadedCollection) {
        if (collectionJobs[collection.key]?.isActive == true) return
        collectionJobs[collection.key] = scope.launch {
            for (track in collection.tracks) {
                if (localTrack(track) != null || pending.any { it.track.ytVideoId == track.ytVideoId }) continue
                while ((pending.map { it.track.ytVideoId } + starting).distinct().size >= 3) delay(500)
                download(track, silent = true)
                yield()
            }
        }
    }

    fun removeCollection(collection: DownloadedCollection) {
        collectionJobs.remove(collection.key)?.cancel()
        savedCollections.removeAll { it.key == collection.key }
        val retained = savedCollections.flatMap { it.tracks }.map { it.ytVideoId }.toSet()
        collection.tracks.filter { it.ytVideoId !in retained }.forEach(::remove)
        save()
        NetworkFeedback.notify("Collection download removed")
    }

    fun remove(track: Track) {
        scope.launch {
            val entries = (pending + completed).filter { it.track.ytVideoId == track.ytVideoId }
            withContext(Dispatchers.IO) { entries.forEach { manager.remove(it.id); file(it.track)?.delete() } }
            pending.removeAll(entries.toSet()); completed.removeAll(entries.toSet())
            _progress.value = _progress.value - track.ytVideoId.orEmpty()
            save(); NetworkFeedback.notify("Download removed")
        }
    }

    private fun save() {
        prefs.edit().putString("pending", json.encodeToString(pending.toList()))
            .putString("completed", json.encodeToString(completed.toList()))
            .putString("collections", json.encodeToString(savedCollections.toList())).apply()
        _collections.value = savedCollections.toList()
        _tracks.value = completed.map { it.track }
    }

    private fun poll() {
        if (polling?.isActive == true || pending.isEmpty()) return
        polling = scope.launch {
            while (pending.isNotEmpty()) {
                val progress = mutableMapOf<String, Int>()
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
                            pending.remove(entry)
                            if (file(entry.track)?.let { it.exists() && it.length() > 0 } == true) {
                                completed.removeAll { it.track.ytVideoId == entry.track.ytVideoId }
                                completed += entry
                                NetworkFeedback.notify("Downloaded ${entry.track.title}")
                            } else NetworkFeedback.notify("Download unavailable. Please download ${entry.track.title} again.")
                        }
                        DownloadManager.STATUS_FAILED -> {
                            pending.remove(entry)
                            withContext(Dispatchers.IO) { manager.remove(entry.id); file(entry.track)?.delete() }
                            NetworkFeedback.notify("Couldn't download ${entry.track.title}. Check your connection and try again.")
                        }
                        else -> progress[entry.track.ytVideoId.orEmpty()] = if (total > 0) (bytes * 100 / total).toInt() else -1
                    }
                }
                _progress.value = progress + starting.associateWith { -1 }
                save()
                if (pending.isNotEmpty()) delay(3_000)
            }
        }
    }
}
