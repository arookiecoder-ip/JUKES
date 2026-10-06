package com.example.juke.network

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Size
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** One HD verification, fallback order and retry budget for all playback surfaces. */
object ArtworkRepository {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private data class Flight(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val locks = mutableMapOf<String, Flight>()
    private val videos = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val failures = mutableMapOf<String, Pair<Int, Long>>()
    private val slots = Semaphore(2)
    fun register(url: String, videoId: String?) { if (!videoId.isNullOrBlank()) {
        if (videos.size > 512) videos.clear()
        videos[url] = videoId
    } }
    suspend fun load(context: Context, source: String, videoId: String? = videos[source]): Bitmap? {
        val key = videoId ?: source
        cache.get(key)?.let { return it }
        val flight = synchronized(locks) { locks.getOrPut(key) { Flight() }.also { it.users++ } }
        val lock = flight.mutex
        try {
            return lock.withLock {
                cache.get(key)?.let { return@withLock it }
                val failed = synchronized(failures) { failures[key] }
                if (failed != null && android.os.SystemClock.elapsedRealtime() < failed.second) return@withLock null
                slots.withPermit {
                    for (url in artworkCandidates(source, videoId, true)) {
                        val result = context.imageLoader.execute(ImageRequest.Builder(context).data(url)
                            .size(Size.ORIGINAL).allowHardware(false)
                            .memoryCachePolicy(if (failed != null) CachePolicy.WRITE_ONLY else CachePolicy.ENABLED)
                            .diskCachePolicy(if (failed != null) CachePolicy.WRITE_ONLY else CachePolicy.ENABLED).build())
                        if (result is SuccessResult && isHdArtwork(result.drawable.intrinsicWidth, result.drawable.intrinsicHeight)) {
                            val original = result.drawable.toBitmap()
                            val factor = minOf(1f, 1200f / maxOf(original.width, original.height))
                            val bitmap = if (factor < 1f) Bitmap.createScaledBitmap(original,
                                (original.width * factor).toInt(), (original.height * factor).toInt(), true) else original
                            cache.put(key, bitmap)
                            synchronized(failures) { failures.remove(key) }
                            return@withPermit bitmap
                        }
                    }
                    synchronized(failures) {
                        if (failures.size > 512) failures.clear()
                        val count = (failed?.first ?: 0) + 1
                        failures[key] = count to (android.os.SystemClock.elapsedRealtime() + if (count <= 2) 3_000 else 30_000)
                    }
                    null
                }
            }
        } finally { synchronized(locks) { flight.users--; if (flight.users == 0 && locks[key] === flight) locks.remove(key) } }
    }
}
