package com.example.juke.services

import android.util.Log
import com.example.juke.network.Backend
import com.example.juke.network.array
import com.example.juke.network.objectOrEmpty
import kotlinx.coroutines.CancellationException
import com.example.juke.network.BackendAuthException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Liked songs of the YouTube account behind the server, keyed by video id. Shared by the player,
 * the notification button and the library so a like shows everywhere at once.
 */
object AccountRepository {
    private const val TAG = "AccountRepository"

    private val _liked = MutableStateFlow<Set<String>>(emptySet())
    val liked: StateFlow<Set<String>> = _liked.asStateFlow()

    fun isLiked(videoId: String?): Boolean = videoId != null && videoId in _liked.value

    suspend fun refreshLiked() {
        try {
            _liked.value = Backend.get("/api/liked_songs/").objectOrEmpty().array("liked_songs")
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackendAuthException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Liked songs refresh failed: ${e.message}")
        }
    }

    /** Like or un-like on YouTube Music. The change shows immediately and is undone on failure. */
    suspend fun setLiked(videoId: String, liked: Boolean) {
        _liked.update { if (liked) it + videoId else it - videoId }
        try {
            Backend.post("/alexa/like/", JsonObject(mapOf(
                "video_id" to JsonPrimitive(videoId),
                "action" to JsonPrimitive(if (liked) "LIKE" else "INDIFFERENT")
            )))
        } catch (e: Exception) {
            _liked.update { if (liked) it - videoId else it + videoId }
            throw e
        }
    }

    fun clear() {
        _liked.value = emptySet()
    }
}
