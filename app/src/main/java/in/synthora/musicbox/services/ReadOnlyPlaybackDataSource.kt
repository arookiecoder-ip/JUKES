package `in`.synthora.musicbox.services

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata

/** Playback never combines partial cache spans with an independently opened HTTP representation. */
@UnstableApi
internal class ReadOnlyPlaybackDataSource(network: DataSource, private val cache: Cache) : DataSource {
    private val online = CachedAudioFallbackDataSource(network, cache)
    private val offline = CacheDataSource.Factory().setCache(cache)
        .setCacheReadDataSourceFactory { checkedCacheFileSource(cache) }
        .setCacheWriteDataSinkFactory(null).createDataSource()
    private var active: DataSource = online
    private var uri: Uri? = null
    private var sessionKey: String? = null
    private var sessionEtag: String? = null
    private var sessionGeneration: String? = null
    private var cachedTotal = -1L
    override fun addTransferListener(listener: TransferListener) {
        online.addTransferListener(listener)
        offline.addTransferListener(listener)
    }
    override fun open(spec: DataSpec): Long {
        uri = spec.uri
        val key = spec.key ?: spec.uri.toString()
        val total = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        val cachedGeneration = cache.getContentMetadata(key).get(AUDIO_GENERATION, "").orEmpty()
        val cachedEtag = audioRepresentationHeader(cache, spec.buildUpon().setPosition(1).build())
        val continuing = spec.position > 0 && sessionKey == key
        if (continuing && ((sessionEtag != null && cachedEtag != null && sessionEtag != cachedEtag) ||
                (sessionGeneration != null && cachedGeneration.isNotBlank() && sessionGeneration != cachedGeneration)))
            throw AudioRepresentationChangedException()
        active = if (spec.uri.scheme in setOf("http", "https") && total > 0 && cache.isCached(key, 0, total)) {
            checkCompletedAudioCache(cache, key)
            offline
        } else online
        val length = active.open(spec)
        val etag = active.responseHeaders.entries.firstOrNull { it.key.equals("ETag", true) }
            ?.value?.firstOrNull()?.takeIf { it.isNotBlank() && !it.startsWith("W/") }
            ?: if (active === offline) cachedEtag else null
        if (continuing && sessionEtag != null && etag != null && sessionEtag != etag) {
            runCatching { active.close() }
            throw AudioRepresentationChangedException()
        }
        val generation = if (active === offline) cachedGeneration else online.cacheGeneration
        if (continuing && sessionGeneration != null && generation != null && sessionGeneration != generation) {
            runCatching { active.close() }
            throw AudioRepresentationChangedException()
        }
        sessionKey = key
        sessionEtag = etag ?: if (continuing) sessionEtag else null
        sessionGeneration = generation
        cachedTotal = total
        return if (active === offline && total >= spec.position) minOf(length, total - spec.position) else length
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (active === offline) {
            val key = sessionKey ?: throw java.io.IOException("No open cache request")
            val metadata = cache.getContentMetadata(key)
            if (metadata.get(AUDIO_GENERATION, "").orEmpty() != sessionGeneration ||
                ContentMetadata.getContentLength(metadata) != cachedTotal)
                throw AudioRepresentationChangedException()
        }
        return active.read(buffer, offset, length)
    }
    override fun getUri(): Uri? = uri
    override fun getResponseHeaders(): Map<String, List<String>> = active.responseHeaders
    override fun close() { try { active.close() } finally { uri = null; active = online } }
}
