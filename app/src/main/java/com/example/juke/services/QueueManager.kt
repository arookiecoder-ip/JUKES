package com.example.juke.services

import android.annotation.SuppressLint
import android.content.Context
import com.example.juke.utils.SafeLog as Log
import com.example.juke.database.MusicDatabase
import com.example.juke.database.toEntity
import com.example.juke.models.Track
import com.example.juke.network.AlexaBackendApi
import com.example.juke.network.*
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

/**
 * Phone queue bookkeeping. Upcoming songs come from the server's shared queue, which is
 * extended from the YouTube Music radio of the playing song, so the phone, the Echo and the web
 * remote all follow the same order.
 */
class QueueManager private constructor(private val context: Context) {

    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: QueueManager? = null

        fun getInstance(context: Context): QueueManager {
            return instance ?: synchronized(this) {
                instance ?: QueueManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val TAG = "QueueManager"
    private val trackDao = MusicDatabase.getDatabase(context).trackDao()

    // Queue state
    private val _currentQueue = MutableStateFlow<List<Track>>(emptyList())
    val currentQueue: StateFlow<List<Track>> = _currentQueue.asStateFlow()

    data class AlexaQueueWindow(val currentUuid: String, val tracks: List<Track>)
    private val _alexaQueueWindow = MutableStateFlow<AlexaQueueWindow?>(null)
    val alexaQueueWindow = _alexaQueueWindow.asStateFlow()

    /** What the queue refresh is doing: songs being fetched now and songs waiting in reserve. */
    data class RecStatus(val resolving: Int = 0, val reserve: Int = 0)
    private val _recStatus = MutableStateFlow(RecStatus())
    val recStatus: StateFlow<RecStatus> = _recStatus.asStateFlow()

    private val fillMutex = Mutex()
    private val sessionGen = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Initialize the queue with a list of tracks (a new song or collection was picked).
     * [isRadioMode] starts a fresh radio from the first track.
     */
    fun initializeQueue(tracks: List<Track>, isRadioMode: Boolean = false, preserveHistory: Boolean = false) {
        if (!preserveHistory || isRadioMode) resetSession()
        _currentQueue.value = tracks.toList()
        Log.d(TAG, "Queue initialized with ${tracks.size} tracks (preserveHistory: $preserveHistory)")
        tracks.firstOrNull()?.let { requestFill(it) }
    }

    /** Kept for callers that re-seat the queue cursor; history does not affect the server queue. */
    fun updateHistory(history: List<Track>) = Unit

    fun insertQueueItem(index: Int, track: Track) {
        val currentList = _currentQueue.value.toMutableList()
        currentList.add(index.coerceIn(0, currentList.size), track)
        _currentQueue.value = currentList
    }

    fun removeFromQueue(trackId: String) {
        _currentQueue.value = _currentQueue.value.filterNot { it.uuid == trackId }
    }

    fun replaceTrackInQueue(trackId: String, updatedTrack: Track) {
        val currentList = _currentQueue.value.toMutableList()
        val index = currentList.indexOfFirst { it.uuid == trackId }
        if (index != -1) {
            currentList[index] = updatedTrack
            _currentQueue.value = currentList
        }
    }

    /**
     * Called every time a song starts. [remaining] is how many songs the player still has queued
     * after it. Refreshes the upcoming window from the server queue.
     */
    fun onPlaybackAdvanced(track: Track, remaining: Int) = requestFill(track)

    /** [isRadioMode] (Mix) starts a new radio from this song. */
    fun fetchAndQueueRecommendations(currentTrack: Track, isRadioMode: Boolean = false) {
        if (isRadioMode) resetSession()
        requestFill(currentTrack)
    }

    fun clearQueue() {
        _currentQueue.value = emptyList()
        resetSession()
    }

    fun getCurrentQueueList(): List<Track> = _currentQueue.value.toList()

    private fun resetSession() {
        sessionGen.incrementAndGet()
        sessionScope.cancel()
        sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        _alexaQueueWindow.value = null
        _recStatus.value = RecStatus()
    }

    private fun requestFill(current: Track) {
        if (current.ytVideoId.isNullOrBlank() || !com.example.juke.network.NetworkFeedback.online.value) return
        sessionScope.launch {
            try {
                refreshAlexaQueue(current)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Queue refresh failed: ${e.message}")
            }
        }
    }

    /** Read the entire web queue. Rendering is lazy; queue membership is never a lookahead window. */
    suspend fun refreshAlexaQueue(current: Track): AlexaQueueWindow? {
        val videoId = current.ytVideoId ?: return null
        if (!NetworkFeedback.online.value) return null
        val gen = sessionGen.get()
        return fillMutex.withLock {
            if (gen != sessionGen.get()) return@withLock null
            try {
                val snapshot = Backend.get("/alexa/now_playing/", mapOf("serial" to "phone")).objectOrEmpty()
                val items = snapshot.array("queue").mapNotNull { (it as? JsonObject)?.let(BrowseParser::item) }
                    .filter { it.videoId.isNotBlank() }
                val reportedIndex = snapshot.number("queue_index").toInt()
                val localIndex = _currentQueue.value.indexOfFirst { it.uuid == current.uuid }
                val index = sharedQueueIndex(items.map { it.videoId }, videoId,
                    snapshot.text("video_id"), reportedIndex, localIndex)
                // A replaced or stale server queue must not erase a locally playing collection.
                if (index < 0) return@withLock null
                val reusable = (_currentQueue.value + (_alexaQueueWindow.value?.tracks ?: emptyList()))
                    .distinctBy { it.uuid }.filter { it.uuid != current.uuid }
                    .groupBy { it.ytVideoId }.mapValues { it.value.toMutableList() }
                val downloads = DownloadRepository.get(context)
                val tracks = items.drop(index + 1).map { item ->
                    coroutineContext.ensureActive()
                    val old = reusable[item.videoId]?.removeFirstOrNull()
                    val track = old ?: item.toTrack().copy(localUri = AlexaBackendApi.audioUrl(item.videoId), isStream = true)
                    downloads.localTrack(track) ?: track
                }
                coroutineContext.ensureActive()
                if (gen != sessionGen.get()) return@withLock null
                trackDao.insertTracks(tracks.map { it.toEntity() })
                _currentQueue.value = _currentQueue.value.take(localIndex.coerceAtLeast(0)) + current + tracks
                AlexaQueueWindow(current.uuid, tracks).also { _alexaQueueWindow.value = it }
            } finally {
                if (gen == sessionGen.get()) _recStatus.value = RecStatus()
            }
        }
    }

    fun cleanup() {
        resetSession()
        sessionScope.cancel()
        instance = null
        Log.d(TAG, "QueueManager cleaned up and instance reset")
    }
}
