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
    private val listeners = mutableListOf<TransferListener>()
    override fun addTransferListener(listener: TransferListener) { listeners += listener; network.addTransferListener(listener) }
    override fun open(spec: DataSpec): Long {
        request = spec; delivered = 0; resolvedLength = -1; active = network
        try { resolvedLength = network.open(spec) }
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
        val read = try { active.read(buffer, offset, length) }
        catch (error: IOException) {
            if (!useCompletedCache(error)) throw error
            active.read(buffer, offset, length)
        }
        if (read > 0) delivered += read
        return read
    }
    private fun useCompletedCache(error: IOException?): Boolean {
        val spec = request ?: return false
        if (active !== network || spec.uri.scheme !in setOf("http", "https") ||
            hasChangedAudioRepresentation(error) || hasInvalidAudioResponse(error)) return false
        val key = spec.key ?: spec.uri.toString()
        val total = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        if (total <= 0 || !cache.isCached(key, 0, total)) return false
        checkCompletedAudioCache(cache, key)
        val position = spec.position + delivered
        val remaining = if (spec.length < 0) total - position else minOf(spec.length - delivered, total - position)
        if (remaining <= 0) return false
        val saved = CacheDataSource.Factory().setCache(cache).setCacheWriteDataSinkFactory(null)
            .createDataSource()
        listeners.forEach(saved::addTransferListener)
        try {
            saved.open(spec.buildUpon().setPosition(position).setLength(remaining).build())
        } catch (_: IOException) { runCatching { saved.close() }; return false }
        runCatching { network.close() }
        active = saved
        if (resolvedLength < 0) resolvedLength = delivered + remaining
        PlaybackDiagnostics.recordTransferFailure("cache_fallback", total, delivered)
        return true
    }
    override fun getUri(): Uri? = if (active === network) network.uri else request?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = active.responseHeaders
    override fun close() { try { active.close() } finally { active = network; request = null; delivered = 0; resolvedLength = -1 } }
}
