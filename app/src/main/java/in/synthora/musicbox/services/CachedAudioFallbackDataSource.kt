package `in`.synthora.musicbox.services

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import java.io.IOException

/** Finish an already opened HTTP read from completed disk audio, without resetting the player. */
@UnstableApi
internal class CachedAudioFallbackDataSource(private val network: DataSource, private val cache: Cache) : DataSource {
    private var active: DataSource = network
    private var request: DataSpec? = null
    private var delivered = 0L
    private var resolvedLength = C.LENGTH_UNSET.toLong()
    private var openedEtag: String? = null
    private var networkOpened = false
    private var openedTotal: Long? = null
    internal var cacheGeneration: String? = null
        private set
    private var cacheRejected = false
    private var savedTotal = -1L
    private var prefixDigest = java.security.MessageDigest.getInstance("SHA-256")
    private val listeners = mutableListOf<TransferListener>()
    override fun addTransferListener(listener: TransferListener) { listeners += listener; network.addTransferListener(listener) }
    override fun open(spec: DataSpec): Long {
        request = spec; delivered = 0; resolvedLength = -1; active = network
        openedEtag = null; networkOpened = false; openedTotal = null; cacheGeneration = null; cacheRejected = false; prefixDigest.reset()
        try {
            resolvedLength = network.open(spec)
            networkOpened = true
            cacheGeneration = (network as? ValidatingAudioDataSource)?.audioGeneration
            fun header(name: String) = network.responseHeaders.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
            openedEtag = header("ETag")?.takeIf { it.isNotBlank() && !it.startsWith("W/") }
            openedTotal = if (header("Content-Encoding")?.let { !it.equals("identity", true) } == true) null else
                header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: header("Content-Length")?.toLongOrNull()
        }
        catch (error: IOException) {
            if (!useCompletedCache(error)) throw error
        }
        return resolvedLength
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        // Once the warmer has saved the whole resource, stop depending on the
        // socket even if it has not reported the connectivity loss yet.
        if (active === network) useCompletedCache(null)
        requireUnchangedCache()
        val read = try { active.read(buffer, offset, length) }
        catch (error: IOException) {
            if (!useCompletedCache(error)) throw error
            requireUnchangedCache()
            active.read(buffer, offset, length)
        }
        requireUnchangedCache()
        if (read > 0) {
            if (active === network && !cacheRejected) prefixDigest.update(buffer, offset, read)
            delivered += read
        }
        return read
    }
    private fun requireUnchangedCache() {
        if (active === network) return
        val spec = request ?: throw IOException("No open audio request")
        val metadata = cache.getContentMetadata(spec.key ?: spec.uri.toString())
        if (metadata.get(AUDIO_GENERATION, "").orEmpty() != cacheGeneration ||
            ContentMetadata.getContentLength(metadata) != savedTotal)
            throw AudioRepresentationChangedException()
    }
    private fun useCompletedCache(error: IOException?): Boolean {
        val spec = request ?: return false
        if (active !== network || cacheRejected || spec.uri.scheme !in setOf("http", "https") ||
            hasChangedAudioRepresentation(error) || hasInvalidAudioResponse(error)) return false
        val key = spec.key ?: spec.uri.toString()
        val metadata = cache.getContentMetadata(key)
        val total = ContentMetadata.getContentLength(metadata)
        val generation = metadata.get(AUDIO_GENERATION, "").orEmpty()
        if (total <= 0 || !cache.isCached(key, 0, total)) return false
        // An extractor may already have parsed the old moov/sample table. A cache
        // repair finishing concurrently must not replace its remaining bytes.
        if (networkOpened) {
            val savedEtag = audioRepresentationHeader(cache, spec.buildUpon().setPosition(1).build())
            val savedGeneration = cache.getContentMetadata(key).get(AUDIO_GENERATION, "").orEmpty()
            if (openedEtag == null || savedEtag != openedEtag || (openedTotal != null && openedTotal != total) ||
                (cacheGeneration != null && savedGeneration != cacheGeneration)) return false
        }
        try { checkCompletedAudioCache(cache, key) }
        catch (_: IOException) { cacheRejected = true; return false }
        val position = spec.position + delivered
        val remaining = if (spec.length < 0) total - position else minOf(spec.length - delivered, total - position)
        if (remaining <= 0) return false
        val saved = CacheDataSource.Factory().setCache(cache).setCacheWriteDataSinkFactory(null)
            .setCacheReadDataSourceFactory { checkedCacheFileSource(cache) }.createDataSource()
        listeners.forEach(saved::addTransferListener)
        // Some older servers used a size-based ETag. Verify the bytes already
        // delivered as well, so equal-size encodings cannot alter parsed headers.
        if (networkOpened && delivered > 0 && !matchesDeliveredPrefix(spec, delivered)) {
            cacheRejected = true
            PlaybackDiagnostics.recordTransferFailure("cache_identity_mismatch", total, delivered, spec.position, key)
            return false
        }
        try {
            saved.open(spec.buildUpon().setPosition(position).setLength(remaining).build())
        } catch (_: IOException) { cacheRejected = true; runCatching { saved.close() }; return false }
        val latest = cache.getContentMetadata(key)
        if (latest.get(AUDIO_GENERATION, "").orEmpty() != generation || ContentMetadata.getContentLength(latest) != total) {
            cacheRejected = true; runCatching { saved.close() }; return false
        }
        runCatching { network.close() }
        cacheGeneration = generation
        savedTotal = total
        active = saved
        if (resolvedLength < 0) resolvedLength = delivered + remaining
        PlaybackDiagnostics.recordTransferFailure("cache_fallback", total, delivered, spec.position, key)
        return true
    }
    private fun matchesDeliveredPrefix(spec: DataSpec, length: Long): Boolean {
        val source = CacheDataSource.Factory().setCache(cache).setCacheWriteDataSinkFactory(null)
            .setCacheReadDataSourceFactory { checkedCacheFileSource(cache) }.createDataSource()
        return try {
            val expected = prefixDigest.digest()
            val actual = java.security.MessageDigest.getInstance("SHA-256")
            source.open(spec.buildUpon().setLength(length).build())
            val buffer = ByteArray(32 * 1024)
            var remaining = length
            while (remaining > 0) {
                val n = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (n <= 0) return false
                actual.update(buffer, 0, n); remaining -= n
            }
            java.security.MessageDigest.isEqual(expected, actual.digest())
        } catch (_: Exception) { false }
        finally { runCatching { source.close() } }
    }
    override fun getUri(): Uri? = if (active === network) network.uri else request?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = active.responseHeaders
    override fun close() { try { active.close() } finally { active = network; request = null; delivered = 0; resolvedLength = -1; openedEtag = null; networkOpened = false } }
}
