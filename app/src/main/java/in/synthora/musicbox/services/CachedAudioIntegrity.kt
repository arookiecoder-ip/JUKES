package `in`.synthora.musicbox.services

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import java.io.IOException

internal class InvalidCachedAudioException : IOException("The completed audio cache contains an incomplete container")
internal const val AUDIO_INTEGRITY_LENGTH = "custom_musicbox_integrity_length"
internal fun hasInvalidCachedAudio(error: Throwable?): Boolean =
    generateSequence(error) { it.cause }.take(8).any { it is InvalidCachedAudioException }

/** Check complete MP4 box boundaries, not just saved byte coverage. Skip media payloads. */
@UnstableApi
internal fun checkCompletedAudioCache(cache: Cache, key: String) {
    val metadata = cache.getContentMetadata(key)
    val total = ContentMetadata.getContentLength(metadata)
    if (total <= 0 || !cache.isCached(key, 0, total)) return
    // Cached span metadata alone cannot certify a file that was truncated or removed.
    val spansIntact = synchronized(cache) {
        // Cache reads may rename span files to update their access timestamp.
        // Keep that operation from racing the physical-file check.
        val spans = cache.getCachedSpans(key)
        spans.isNotEmpty() && spans.all { span -> span.file?.let { it.canRead() && it.length() == span.length } == true }
    }
    if (!spansIntact) {
        PlaybackDiagnostics.recordTransferFailure("cached_span_incomplete", total, -1, 0, key)
        throw InvalidCachedAudioException()
    }
    if (metadata.get(AUDIO_INTEGRITY_LENGTH, -1L) == total) return
    val source = CacheDataSource.Factory().setCache(cache).createDataSource()
    var windowStart = -1L
    var window = byteArrayOf()
    fun bytes(position: Long, size: Int): ByteArray {
        if (position >= windowStart && position - windowStart + size <= window.size) {
            val start = (position - windowStart).toInt()
            return window.copyOfRange(start, start + size)
        }
        // Read ahead for adjacent fragment headers. Large media boxes are
        // still skipped, and a long fragmented song needs no arbitrary box cap.
        val result = ByteArray(minOf(4_096L, total - position).toInt())
        try {
            source.open(DataSpec.Builder().setUri("cache-audio:").setKey(key).setPosition(position).setLength(result.size.toLong()).build())
            var count = 0
            while (count < result.size) {
                val read = source.read(result, count, result.size - count)
                if (read < 0) throw InvalidCachedAudioException()
                count += read
            }
        } finally { source.close() }
        windowStart = position; window = result
        return result.copyOfRange(0, size)
    }
    fun unsigned(data: ByteArray, start: Int, size: Int): Long {
        var value = 0L
        for (i in start until start + size) value = (value shl 8) or (data[i].toLong() and 255)
        return value
    }
    val prefix = bytes(0, minOf(64, total).toInt())
    var valid = !isAudioErrorDocument(prefix)
    if (valid && prefix.size >= 8 && String(prefix, 4, 4, Charsets.US_ASCII) == "ftyp") {
        var position = 0L
        var moov = false
        var media = false
        while (position < total && valid) {
            if (total - position < 8) { valid = false; break }
            val header = bytes(position, 8)
            val type = String(header, 4, 4, Charsets.US_ASCII)
            val declared = unsigned(header, 0, 4)
            val headerSize = if (declared == 1L) 16L else 8L
            val size = when (declared) {
                0L -> total - position
                1L -> if (total - position >= 16) unsigned(bytes(position + 8, 8), 0, 8) else -1
                else -> declared
            }
            if (size < headerSize || size > total - position) { valid = false; break }
            if (type == "moov") moov = true
            if (type == "mdat" && size > headerSize) media = true
            position += size
        }
        valid = valid && position == total && moov && media
    }
    if (!valid) {
        PlaybackDiagnostics.recordTransferFailure("cached_container_incomplete", total, -1, 0, key)
        throw InvalidCachedAudioException()
    }
    // One small structural scan per completed representation, on the loader/writer thread.
    cache.applyContentMetadataMutations(key, ContentMetadataMutations().set(AUDIO_INTEGRITY_LENGTH, total))
}

@UnstableApi
internal fun checkedCacheFileSource(cache: Cache): DataSource {
    val file = FileDataSource()
    return object : DataSource by file {
        override fun open(spec: DataSpec): Long {
            spec.key?.let { checkCompletedAudioCache(cache, it) }
            return file.open(spec)
        }
    }
}
