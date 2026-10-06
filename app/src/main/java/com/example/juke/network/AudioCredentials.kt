package com.example.juke.network

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** No server-wide key in new APKs. Tokens are audio-only and tied to a revocable login. */
object AudioCredentials {
    private val lock = Mutex()
    private var token = ""
    private var expiresAt = 0L
    suspend fun header(download: Boolean = false): Pair<String, String> = lock.withLock {
        val now = System.currentTimeMillis()
        if (download || token.isBlank() || now >= expiresAt) {
            val response = Backend.post("/api/app/audio-token/", buildJsonObject { put("download", download) }).objectOrEmpty()
            val value = response.text("token")
            check(value.isNotBlank()) { "Server returned no audio credential" }
            if (download) return@withLock "X-MusicBox-Audio-Token" to value
            token = value
            expiresAt = now + (response.number("expires_in") * 1000 - 60_000).coerceAtLeast(0)
        }
        "X-MusicBox-Audio-Token" to token
    }
    fun clear() { token = ""; expiresAt = 0 }
}
