package `in`.synthora.musicbox.services

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import java.io.IOException

/** Do not join old cache spans to a different encoding of the same song. */
internal class AudioRepresentationChangedException : IOException("The saved audio and server audio differ")
internal fun hasChangedAudioRepresentation(error: Throwable?): Boolean =
    generateSequence(error) { it.cause }.take(8).any { it is AudioRepresentationChangedException }

private const val AUDIO_ETAG = "custom_musicbox_audio_etag"
private const val AUDIO_LENGTH = "custom_musicbox_audio_length"

@UnstableApi
internal fun audioRepresentationHeader(cache: Cache, spec: DataSpec): String? =
    if (spec.position == 0L) null else cache.getContentMetadata(spec.key ?: spec.uri.toString())
        .get(AUDIO_ETAG, "").orEmpty().takeIf { it.isNotBlank() && !it.startsWith("W/") }

@UnstableApi
internal fun clearAudioRepresentation(cache: Cache, key: String) {
    val mutations = ContentMetadataMutations().remove(AUDIO_ETAG).remove(AUDIO_LENGTH).remove(AUDIO_INTEGRITY_LENGTH)
    ContentMetadataMutations.setContentLength(mutations, -1)
    ContentMetadataMutations.setRedirectedUri(mutations, null)
    cache.applyContentMetadataMutations(key, mutations)
}

@UnstableApi
internal fun checkAudioRepresentation(cache: Cache?, spec: DataSpec, headers: Map<String, List<String>>) {
    if (cache == null || spec.uri.scheme !in setOf("http", "https")) return
    fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
    val etag = header("ETag")?.takeIf { it.length <= 512 && !it.startsWith("W/") }.orEmpty()
    val range = header("Content-Range")
    val match = range?.let { Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)").matchEntire(it.trim()) }
    val start = match?.groupValues?.get(1)?.toLongOrNull()
    val end = match?.groupValues?.get(2)?.toLongOrNull()
    val rangeTotal = match?.groupValues?.get(3)?.toLongOrNull()
    // Content-Length on a compressed response counts encoded wire bytes, not
    // the decompressed bytes consumed by Media3 and saved on disk.
    val compressed = header("Content-Encoding")?.let { !it.equals("identity", true) } ?: false
    val total = if (compressed) null else if (range == null) header("Content-Length")?.toLongOrNull() else rangeTotal
    // DefaultHttpDataSource accepts a 200 for Range and skips its prefix. That
    // is safe only if it is still the same representation, checked below.
    val invalidRange = range != null && (match == null || start != spec.position ||
        end == null || start == null || end < start || (rangeTotal != null && end >= rangeTotal))
    val key = spec.key ?: spec.uri.toString()
    synchronized(cache) {
        val saved = cache.getContentMetadata(key)
        val oldEtag = saved.get(AUDIO_ETAG, "").orEmpty()
        val oldLength = saved.get(AUDIO_LENGTH, ContentMetadata.getContentLength(saved))
        val cached = cache.getCachedSpans(key).isNotEmpty()
        // Legacy partial spans have no representation identity. A new response
        // must not silently certify those old bytes as part of its own file.
        val legacyPartial = cached && oldEtag.isBlank() && etag.isNotBlank()
        if (invalidRange || (oldEtag.isNotBlank() && etag != oldEtag) ||
            (oldLength > 0 && total != null && total != oldLength) || legacyPartial) {
            PlaybackDiagnostics.recordTransferFailure("representation_changed", oldLength, total ?: -1)
            throw AudioRepresentationChangedException()
        }
        val mutations = ContentMetadataMutations()
        if (etag.isNotBlank()) mutations.set(AUDIO_ETAG, etag)
        if (total != null && total > 0) mutations.set(AUDIO_LENGTH, total)
        cache.applyContentMetadataMutations(key, mutations)
    }
}
