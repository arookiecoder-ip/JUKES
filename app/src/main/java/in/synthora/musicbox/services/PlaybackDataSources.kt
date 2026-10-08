package `in`.synthora.musicbox.services

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.*
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import `in`.synthora.musicbox.network.Backend

/** The production audio pipeline is shared with the native playback regression tests. */
@UnstableApi
fun playbackDataSources(context: Context, cache: Cache, audioBaseUrl: String = Backend.audioBaseUrl,
    apiKey: String = Backend.apiKey, transferListener: TransferListener? = null, prefetch: Boolean = false): DataSource.Factory {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(`in`.synthora.musicbox.network.DEVICE_AUDIO_READ_TIMEOUT_MS)
            .setAllowCrossProtocolRedirects(true)
        transferListener?.let(httpDataSourceFactory::setTransferListener)
        // Keep credentials out of media URIs (including Media3/system error logs).
        val authenticatedAudio = androidx.media3.datasource.ResolvingDataSource.Factory(httpDataSourceFactory) { spec ->
            if (`in`.synthora.musicbox.network.isBackendAudioRequest(audioBaseUrl, spec.uri.toString())) {
                val credential = if (apiKey.isNotBlank()) "X-Api-Key" to apiKey else
                    kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) { `in`.synthora.musicbox.network.AudioCredentials.header() }
                spec.withAdditionalHeaders(mapOf(credential) + if (prefetch) mapOf("X-MusicBox-Prefetch" to "1") else emptyMap())
            } else spec
        }
        val upstreamDataSourceFactory =
            DefaultDataSource.Factory(context, authenticatedAudio)
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamDataSourceFactory)
            // Cache errors are non-fatal — fall through to the network.
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return cacheDataSourceFactory
}
