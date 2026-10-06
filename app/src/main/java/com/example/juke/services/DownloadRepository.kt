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

private data class DownloadResult(val status: Int, val bytes: Long, val total: Long, val reason: Int)
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
    private val pending = mutableListOf<DownloadEntry>()
    private val completed = mutableListOf<DownloadEntry>()
    private val queued = mutableMapOf<String, Track>()
    private val starting = mutableSetOf<String>()
    private val savedCollections = mutableListOf<DownloadedCollection>()
    private val dao = com.example.juke.database.DownloadManifestDatabase.get(context).manifests()
    private val snapshots = kotlinx.coroutines.channels.Channel<List<com.example.juke.database.DownloadManifestRow>>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private var initialized = false
    private val ready = CompletableDeferred<Unit>()
    suspend fun awaitReady() { ready.await() }
    private val unavailable = mutableMapOf<String, com.example.juke.database.DownloadManifestRow>()
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
        scope.launch {
            val rows = try { withContext(Dispatchers.IO) {
                if (dao.migrated() == 0) {
                    val legacy = mutableListOf<com.example.juke.database.DownloadManifestRow>()
                    for (kind in listOf("pending", "completed")) readEntries(kind).forEach {
                        legacy += manifest(kind, it.track.ytVideoId.orEmpty(), kind, json.encodeToString(it))
                    }
                    runCatching { json.decodeFromString<List<Track>>(prefs.getString("queued", "[]").orEmpty()) }.getOrDefault(emptyList()).forEach {
                        legacy += manifest("queued", it.ytVideoId.orEmpty(), "queued", json.encodeToString(it))
                    }
                    runCatching { json.decodeFromString<List<DownloadedCollection>>(prefs.getString("collections", "[]").orEmpty()) }.getOrDefault(emptyList()).forEach {
                        legacy += collectionRows(it)
                    }
                    dao.replace(legacy)
                    // Clear legacy JSON only after a successful transaction. A crash safely retries.
                    prefs.edit().clear().apply()
                }
                dao.read()
            } } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                NetworkFeedback.notify("Couldn't load downloads. Check device storage and restart the app.")
                ready.complete(Unit)
                return@launch
            }
            rows.filter { it.kind == "pending" }.forEach { runCatching { json.decodeFromString<DownloadEntry>(it.payload) }.getOrNull()?.let(pending::add) }
            rows.filter { it.kind == "unavailable" }.forEach { unavailable[it.key] = it }
            rows.filter { it.kind == "completed" }.forEach { row ->
                runCatching { json.decodeFromString<DownloadEntry>(row.payload) }.getOrNull()?.let { entry ->
                    if (file(entry.track)?.let { f -> f.exists() && f.length() > 0 } == true) completed += entry
                    else unavailable[row.key] = manifest("unavailable", row.key, "missing", row.payload)
                }
            }
            rows.filter { it.kind == "queued" }.forEach { row ->
                runCatching { json.decodeFromString<Track>(row.payload) }.getOrNull()?.let { queued.putIfAbsent(it.ytVideoId.orEmpty(), it) }
            }
            rows.filter { it.kind == "collection" }.forEach { row ->
                runCatching { json.decodeFromString<DownloadedCollection>(row.payload) }.getOrNull()?.let { collection ->
                    val members = rows.filter { it.kind == "member:${row.key}" }.sortedBy { it.key.toIntOrNull() ?: 0 }.mapNotNull {
                        runCatching { json.decodeFromString<Track>(it.payload) }.getOrNull()
                    }
                    if (savedCollections.none { it.key == collection.key }) savedCollections += collection.copy(tracks = members)
                }
            }
            val retained = (pending + completed).mapNotNull { it.track.ytVideoId }.toSet()
            queued.keys.removeAll(retained)
            initialized = true
            ready.complete(Unit)
            batchTotal = pending.size + queued.size
            save(); publishStatus(); pump(); poll()
            if (_status.value.active > 0) DownloadService.start(context)
            for (snapshot in snapshots) try { withContext(Dispatchers.IO) { dao.replace(snapshot) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { NetworkFeedback.notify("Couldn't save download changes. Check device storage.") }
        }
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
        additions.forEach { unavailable.remove(it.ytVideoId); queued[requireNotNull(it.ytVideoId)] = it }; batchTotal += additions.size
        save(); publishStatus(); DownloadService.start(context); pump()
        return true
    }
    private val rateLimitAttempts = mutableMapOf<String, Int>()
    private var cooldownUntil = 0L
    private var cooldownJob: Job? = null
    private fun pump() {
        if (!initialized) return
        val remaining = cooldownUntil - android.os.SystemClock.elapsedRealtime()
        if (remaining > 0) {
            if (cooldownJob?.isActive != true) cooldownJob = scope.launch { delay(remaining); cooldownJob = null; pump() }
            return
        }
        val slots = (3 - pending.size - starting.size).coerceAtLeast(0)
        queued.filterKeys { it !in starting }.entries.take(slots).forEach { (video, track) ->
            starting += video
            scope.launch {
                try {
                    check(AlexaBackendApi.isConfigured()) { "Device downloads are unavailable in this build" }
                    val directory = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: error("Device storage is unavailable")
                    val target = File(directory, "$video.audio")
                    val credential = com.example.juke.network.AudioCredentials.header(download = true)
                    val id = withContext(Dispatchers.IO) {
                        directory.mkdirs(); target.delete()
                        manager.enqueue(DownloadManager.Request(AlexaBackendApi.audioUrl(video).toUri())
                            .addRequestHeader(credential.first, credential.second)
                            .addRequestHeader("X-MusicBox-Download", "1")
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
        videos.forEach { queued.remove(it); unavailable.remove(it); rateLimitAttempts.remove(it) }
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
        if (initialized) {
            val rows = pending.map { manifest("pending", it.track.ytVideoId.orEmpty(), "downloading", json.encodeToString(it)) } +
                completed.map { manifest("completed", it.track.ytVideoId.orEmpty(), "complete", json.encodeToString(it)) } +
                queued.values.map { manifest("queued", it.ytVideoId.orEmpty(), "queued", json.encodeToString(it)) } +
                savedCollections.flatMap(::collectionRows) + unavailable.values
            snapshots.trySend(rows)
        }
        _collections.value = savedCollections.toList(); _tracks.value = completed.map { it.track }
        completedByVideo = _tracks.value.associateBy { it.ytVideoId }
    }
    private fun manifest(kind: String, key: String, state: String, payload: String) =
        com.example.juke.database.DownloadManifestRow(kind, key, state, payload)
    private fun collectionRows(collection: DownloadedCollection): List<com.example.juke.database.DownloadManifestRow> =
        listOf(manifest("collection", collection.key, "complete", json.encodeToString(collection.copy(tracks = emptyList())))) +
            collection.tracks.mapIndexed { index, track -> manifest("member:${collection.key}", index.toString(),
                "member", json.encodeToString(track)) }

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
                            if (!cursor.moveToFirst()) DownloadResult(DownloadManager.STATUS_FAILED, 0L, 0L, 0)
                            else DownloadResult(cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                                cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                                cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)))
                        }
                    }
                    if (entry !in pending) continue
                    val (status, bytes, total, reason) = result
                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            pending.remove(entry); entry.track.ytVideoId?.let { rateLimitAttempts.remove(it) }; changed = true
                            if (file(entry.track)?.let { it.exists() && it.length() > 0 } == true) {
                                completed.removeAll { it.track.ytVideoId == entry.track.ytVideoId }; completed += entry; unavailable.remove(entry.track.ytVideoId); batchCompleted++
                            } else { batchFailed++
                                unavailable[entry.track.ytVideoId.orEmpty()] = manifest("unavailable", entry.track.ytVideoId.orEmpty(), "missing", json.encodeToString(entry))
                                NetworkFeedback.notify("Couldn't save ${entry.track.title}. Please download it again.") }
                        }
                        DownloadManager.STATUS_FAILED -> {
                            pending.remove(entry); changed = true
                            val video = entry.track.ytVideoId.orEmpty()
                            val attempts = rateLimitAttempts[video] ?: 0
                            if (com.example.juke.network.shouldRetryRateLimitedDownload(reason, attempts)) {
                                rateLimitAttempts[video] = attempts + 1
                                queued[video] = entry.track.copy(localUri = null, isStream = true)
                                cooldownUntil = android.os.SystemClock.elapsedRealtime() + 60_000
                                NetworkFeedback.notify("Downloads paused briefly by the server. They will retry automatically.")
                            } else {
                                rateLimitAttempts.remove(video); batchFailed++
                                unavailable[video] = manifest("unavailable", video, "failed", json.encodeToString(entry))
                                NetworkFeedback.notify("Couldn't download ${entry.track.title}. Check your connection and try again.")
                            }
                            withContext(Dispatchers.IO) { manager.remove(entry.id); file(entry.track)?.delete() }
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
