package `in`.synthora.musicbox.services

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.io.EOFException
import androidx.media3.datasource.cache.Cache

/** Contains no response body, URL, credentials or server error details. */
internal class IncompleteAudioTransferException : EOFException("Audio transfer ended before its declared byte length")
internal fun hasIncompleteAudioTransfer(error: Throwable?): Boolean =
    generateSequence(error) { it.cause }.take(8).any { it is IncompleteAudioTransferException }

internal class InvalidAudioResponseException : IOException("The audio response is empty or contains an error document")

internal fun isAudioErrorDocument(prefix: ByteArray, length: Int = prefix.size): Boolean {
    if (length == 0) return true
    val text = String(prefix, 0, length, Charsets.UTF_8).removePrefix("\uFEFF").trimStart()
    return text.startsWith("{") || text.startsWith("[") || text.startsWith("<")
}
internal fun isAudioErrorContentType(type: String?): Boolean {
    val mime = type.orEmpty().substringBefore(';').trim().lowercase()
    return mime.startsWith("text/") || mime.contains("json") || mime.contains("xml")
}
internal fun hasInvalidAudioResponse(error: Throwable?): Boolean =
    generateSequence(error) { it.cause }.take(8).any { it is InvalidAudioResponseException }

internal fun sameAudioSource(first: String?, second: String?): Boolean {
    if (first == null || second == null) return false
    fun local(value: String): java.io.File? = runCatching {
        val uri = java.net.URI(value)
        when (uri.scheme?.lowercase()) {
            "file" -> java.io.File(uri).canonicalFile
            null -> java.io.File(value).canonicalFile
            else -> null
        }
    }.getOrNull()
    val a = local(first); val b = local(second)
    return if (a != null && b != null) a == b else first == second
}

internal fun validCompletedAudio(file: java.io.File?, bytes: Long, total: Long): Boolean =
    file != null && file.isFile && file.canRead() && bytes > 0 && file.length() == bytes &&
        (total <= 0 || bytes == total) && runCatching {
            file.inputStream().use { input ->
                val prefix = ByteArray(64)
                val count = input.read(prefix)
                count > 0 && !isAudioErrorDocument(prefix, count)
            }
        }.getOrDefault(false)

/** Inspect a small prefix before it can enter the cache. Replay every byte to Media3. */
@UnstableApi
internal class ValidatingAudioDataSource(private val upstream: DataSource, private val cache: Cache? = null) : DataSource by upstream {
    private var prefix = ByteArray(64)
    private var count = 0
    private var cursor = 0
    private var expected = C.LENGTH_UNSET.toLong()
    private var received = 0L
    private var key: String? = null
    private var position = 0L
    internal var audioGeneration: String? = null
        private set
    override fun open(spec: DataSpec): Long {
        count = 0; cursor = 0; received = 0; expected = C.LENGTH_UNSET.toLong()
        key = spec.key ?: spec.uri.toString(); position = spec.position; audioGeneration = null
        try {
            val pinned = cache?.let { audioRepresentationHeader(it, spec) }
            val length = upstream.open(if (pinned != null) spec.withAdditionalHeaders(mapOf("If-Range" to pinned)) else spec)
            expected = if (spec.length >= 0 && length >= 0) minOf(spec.length, length) else length
            // A valid range may end at EOF before the requested upper bound.
            // DefaultHttpDataSource reports the requested length in that case.
            val headers = upstream.responseHeaders
            fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
            if (!header("Content-Encoding").equals("gzip", true)) {
                val range = header("Content-Range")?.let { Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)").matchEntire(it.trim()) }
                val available = if (range != null) {
                    val start = range.groupValues[1].toLongOrNull()
                    val end = range.groupValues[2].toLongOrNull()
                    if (start != null && end != null && end >= start) end - start + 1 else null
                } else header("Content-Length")?.toLongOrNull()?.minus(spec.position)?.takeIf { it >= 0 }
                if (available != null) expected = if (expected < 0) available else minOf(expected, available)
            }
            audioGeneration = checkAudioRepresentation(cache, spec, upstream.responseHeaders)

            val type = upstream.responseHeaders.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value?.firstOrNull()
            var invalid = isAudioErrorContentType(type)
            if (!invalid && spec.position == 0L && spec.length != 0L) {
                val limit = if (spec.length >= 0) minOf(64L, spec.length).toInt() else 64
                while (count < limit) {
                    val read = upstream.read(prefix, count, limit - count)
                    if (read == C.RESULT_END_OF_INPUT) { requireCompleteTransfer(); break }
                    if (read == 0) break
                    count += read
                    received += read
                }
                invalid = isAudioErrorDocument(prefix, count)
            }
            PlaybackDiagnostics.recordAudioResponse(spec.uri.scheme in setOf("http", "https"), type, length, invalid, key, position)
            if (invalid) throw InvalidAudioResponseException()
            return expected
        } catch (error: Exception) {
            runCatching { upstream.close() }
            throw error
        }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (cursor < count) {
            val size = minOf(length, count - cursor)
            prefix.copyInto(buffer, offset, cursor, cursor + size)
            cursor += size
            return size
        }
        val read = upstream.read(buffer, offset, length)
        if (read == C.RESULT_END_OF_INPUT) requireCompleteTransfer()
        else if (read > 0) received += read
        return read
    }
    private fun requireCompleteTransfer() {
        if (expected >= 0 && received < expected) {
            PlaybackDiagnostics.recordTransferFailure("early_eof", expected, received, position, key)
            // Never let CacheDataSource shorten the resource's saved length on a broken socket.
            throw IncompleteAudioTransferException()
        }
    }
    // Java interface default methods are not forwarded by Kotlin delegation.
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
    override fun close() { count = 0; cursor = 0; received = 0; expected = -1; upstream.close() }
}
