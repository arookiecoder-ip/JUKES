package com.example.juke.services

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.example.juke.database.MusicDatabase
import com.example.juke.database.toEntity
import com.example.juke.models.Track
import com.example.juke.network.AlexaBackendApi
import com.example.juke.network.toAppTrack
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

    private val settingsPrefs = context.getSharedPreferences(
        "music_settings_prefs",
        Context.MODE_PRIVATE
    )

    // Queue state
    private val _currentQueue = MutableStateFlow<List<Track>>(emptyList())
    val currentQueue: StateFlow<List<Track>> = _currentQueue.asStateFlow()

    data class AlexaQueueWindow(val currentUuid: String, val tracks: List<Track>)
    private val _alexaQueueWindow = MutableStateFlow<AlexaQueueWindow?>(null)
    val alexaQueueWindow = _alexaQueueWindow.asStateFlow()
    private val alexaRadioSeeds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** What the queue refresh is doing: songs being fetched now and songs waiting in reserve. */
    data class RecStatus(val resolving: Int = 0, val reserve: Int = 0)
    private val _recStatus = MutableStateFlow(RecStatus())
    val recStatus: StateFlow<RecStatus> = _recStatus.asStateFlow()

    private val fillMutex = Mutex()
    private val sessionGen = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** How many songs to keep ready ahead of the playing one (Audio Settings). */
    private fun lookahead(): Int = settingsPrefs.getInt("recommendation_count", 5).coerceIn(1, 49)

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
        alexaRadioSeeds.clear()
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

    /** Refresh a bounded window in the server's current order, including removals. */
    suspend fun refreshAlexaQueue(current: Track): AlexaQueueWindow? {
        val videoId = current.ytVideoId?.takeIf { it.isNotBlank() } ?: return null
        if (!AlexaBackendApi.isConfigured() || !com.example.juke.network.NetworkFeedback.online.value) return null
        val gen = sessionGen.get()
        return fillMutex.withLock {
            if (gen != sessionGen.get()) return@withLock null
            val target = lookahead()
            _recStatus.value = RecStatus(resolving = target)
            try {
                // next_track is consulted at request time, rather than trusting the old phone queue.
                val next = AlexaBackendApi.nextTrack(videoId)
                var items = if (next == null) emptyList() else AlexaBackendApi.queueTracks(videoId, target)
                if (items.size < target && videoId !in alexaRadioSeeds) {
                    try {
                        val radio = AlexaBackendApi.getRadio(videoId)
                        coroutineContext.ensureActive()
                        if (gen != sessionGen.get()) return@withLock null
                        if (radio.isNotEmpty()) AlexaBackendApi.updateQueue("extend", videoId, radio.take(200))
                        if (gen != sessionGen.get()) return@withLock null
                        alexaRadioSeeds.add(videoId)
                        items = AlexaBackendApi.queueTracks(videoId, target)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Radio availability must not prevent existing queue edits from syncing.
                        Log.w(TAG, "Radio extension failed: ${e.message}")
                    }
                }
                val existing = _currentQueue.value + (_alexaQueueWindow.value?.tracks ?: emptyList())
                val reusable = existing.distinctBy { it.uuid }.filter { it.uuid != current.uuid }
                    .groupBy { it.ytVideoId }.mapValues { it.value.toMutableList() }
                val tracks = items.map { item ->
                    coroutineContext.ensureActive()
                    val cached = reusable[item.videoId]?.removeFirstOrNull()
                    cached?.takeIf { !it.localUri.isNullOrBlank() }
                        ?: item.toAppTrack(AlexaBackendApi.getStreamUrl(item.videoId)).also {
                            coroutineContext.ensureActive()
                            if (gen == sessionGen.get()) trackDao.insertTrack(it.toEntity())
                        }
                }
                coroutineContext.ensureActive()
                if (gen != sessionGen.get()) return@withLock null
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
