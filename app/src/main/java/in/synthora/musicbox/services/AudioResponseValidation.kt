package `in`.synthora.musicbox.services

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException

/** Contains no response body, URL, credentials or server error details. */
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
internal class ValidatingAudioDataSource(private val upstream: DataSource) : DataSource by upstream {
    private var prefix = ByteArray(64)
    private var count = 0
    private var cursor = 0
    override fun open(spec: DataSpec): Long {
        count = 0; cursor = 0
        try {
            val length = upstream.open(spec)
            val type = upstream.responseHeaders.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value?.firstOrNull()
            var invalid = isAudioErrorContentType(type)
            if (!invalid && spec.position == 0L && spec.length != 0L) {
                val limit = if (spec.length >= 0) minOf(64L, spec.length).toInt() else 64
                while (count < limit) {
                    val read = upstream.read(prefix, count, limit - count)
                    if (read == C.RESULT_END_OF_INPUT || read == 0) break
                    count += read
                }
                invalid = isAudioErrorDocument(prefix, count)
            }
            PlaybackDiagnostics.recordAudioResponse(spec.uri.scheme in setOf("http", "https"), type, length, invalid)
            if (invalid) throw InvalidAudioResponseException()
            return length
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
        return upstream.read(buffer, offset, length)
    }
    override fun close() { count = 0; cursor = 0; upstream.close() }
}
