package `in`.synthora.musicbox.services

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.util.TreeSet

/** Upcoming downloads may evict old audio, never the currently selected stream. */
@UnstableApi
internal class PlayingAudioCacheEvictor(private val maxBytes: Long) : CacheEvictor {
    @Volatile var playingKey: String? = null
    private var bytes = 0L
    private val spans = TreeSet<CacheSpan>(compareBy<CacheSpan> { it.lastTouchTimestamp }.thenComparator { a, b -> a.compareTo(b) })
    override fun requiresCacheSpanTouches() = true
    override fun onCacheInitialized() = Unit
    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        evict(cache, if (length == C.LENGTH_UNSET.toLong()) 0 else length)
    }
    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        spans.add(span); bytes += span.length
        evict(cache, 0)
    }
    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        if (spans.remove(span)) bytes -= span.length
    }
    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan); onSpanAdded(cache, newSpan)
    }
    private fun evict(cache: Cache, incoming: Long) {
        while (bytes + incoming > maxBytes) {
            val candidate = spans.firstOrNull { it.key != playingKey } ?: break
            cache.removeSpan(candidate)
        }
    }
}
