package `in`.synthora.musicbox.services

import android.net.Uri
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Keep the next five streams warm without creating Library downloads or notifications. */
@UnstableApi
class UpcomingAudioPreloader(private val factory: DataSource.Factory, private val scope: CoroutineScope, private val context: android.content.Context? = null,
    private val policyOverride: ((Boolean) -> PreloadPolicy)? = null,
    private val nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val demandFactory: DataSource.Factory = factory) {
    private data class Work(val writer: CacheWriter, val source: CacheDataSource, val job: Job)
    private val work = mutableMapOf<String, Work>()
    private val completed = mutableMapOf<String, Long>()
    private val slots = Semaphore(1)
    private val cache by lazy { (demandFactory.createDataSource() as CacheDataSource).cache }
    private fun fullyCached(url: String): Boolean {
        val length = androidx.media3.datasource.cache.ContentMetadata.getContentLength(cache.getContentMetadata(url))
        return length > 0 && cache.isCached(url, 0, length)
    }
    private val retries = mutableMapOf<String, BackgroundRetry>()
    fun update(urls: List<String>, buffering: Boolean = false, currentUrl: String? = null) {
        val connectivity = context?.getSystemService(android.net.ConnectivityManager::class.java)
        val policy = policyOverride?.invoke(buffering) ?: preloadPolicy(`in`.synthora.musicbox.network.NetworkFeedback.online.value,
            connectivity?.isActiveNetworkMetered ?: true,
            context?.getSharedPreferences("music_settings_prefs", android.content.Context.MODE_PRIVATE)?.getBoolean("prefetch_mobile_data", false) ?: false, buffering)
        val current = currentUrl?.takeIf { Uri.parse(it).scheme in listOf("https", "http") }
        val currentComplete = current != null && fullyCached(current)
        val budget = context?.getSharedPreferences("music_settings_prefs", android.content.Context.MODE_PRIVATE)
            ?.getInt("stream_cache_mb", 256)?.coerceIn(128, 1024)?.toLong()?.times(1024 * 1024) ?: Long.MAX_VALUE
        val currentLength = current?.let { androidx.media3.datasource.cache.ContentMetadata.getContentLength(cache.getContentMetadata(it)) }?.coerceAtLeast(0) ?: 0
        var remaining = (budget - currentLength).coerceAtLeast(0)
        val upcoming = urls.distinct().filter { it != current && Uri.parse(it).scheme in listOf("https", "http") }
            .take(policy.tracks).filter { url ->
                val length = androidx.media3.datasource.cache.ContentMetadata.getContentLength(cache.getContentMetadata(url))
                if (length > remaining) false else { if (length > 0) remaining -= length; true }
            }.toSet()
        val wanted = if (current != null && (!currentComplete || buffering)) setOf(current) else upcoming + listOfNotNull(current)
        (work.keys - wanted).forEach { key -> work.remove(key)?.let { obsolete ->
            obsolete.writer.cancel(); obsolete.job.cancel()
            scope.launch(Dispatchers.IO + NonCancellable) { runCatching { obsolete.source.close() } }
        } }
        completed.keys.retainAll(wanted)
        completed.entries.removeAll { (url, length) ->
            if (length < 0) !fullyCached(url) else !cache.isCached(url, 0, length)
        }
        retries.keys.retainAll(wanted)
        wanted.filter { it !in work && (it !in completed || (it == current && !currentComplete)) &&
            retries[it]?.ready(nowMs()) != false }.forEach { url ->
            val targetLength = if (url == current) androidx.media3.common.C.LENGTH_UNSET.toLong() else policy.bytesPerTrack
            val source = (if (url == current) demandFactory else factory).createDataSource() as CacheDataSource
            val writer = CacheWriter(source, DataSpec.Builder().setUri(Uri.parse(url)).setLength(targetLength).build(), null, null)
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    slots.withPermit { withContext(Dispatchers.IO) { writer.cache() } }
                    if (work[url]?.writer === writer) {
                        val stored = if (targetLength < 0) fullyCached(url) else cache.isCached(url, 0, targetLength)
                        if (!stored) throw java.io.IOException("Audio read completed without a complete saved cache")
                        completed[url] = targetLength
                        retries.remove(url)
                    }
                } catch (e: CancellationException) { throw e }
                catch (error: Exception) {
                    val limited = generateSequence<Throwable>(error) { it.cause }.take(8)
                        .filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>()
                        .firstOrNull()?.responseCode == 429
                    if (work[url]?.writer === writer) retries.getOrPut(url) { BackgroundRetry() }.failed(nowMs(), if (limited) 60_000 else 0)
                } finally {
                    withContext(Dispatchers.IO + NonCancellable) { runCatching { source.close() } }
                    if (work[url]?.writer === writer) work.remove(url)
                }
            }
            work[url] = Work(writer, source, job); job.start()
        }
    }
    fun clear() { update(emptyList()); completed.clear() }
}

@UnstableApi
fun upcomingAudioUrls(player: Player): List<String> {
    if (player.repeatMode == Player.REPEAT_MODE_ONE || player.currentMediaItemIndex !in 0 until player.mediaItemCount) return emptyList()
    val timeline = player.currentTimeline
    val seen = mutableSetOf(player.currentMediaItemIndex)
    var index = player.currentMediaItemIndex
    val urls = mutableListOf<String>()
    var inspected = 0
    while (inspected++ < 5) {
        index = timeline.getNextWindowIndex(index, player.repeatMode, player.shuffleModeEnabled)
        if (index < 0 || !seen.add(index)) break
        player.getMediaItemAt(index).localConfiguration?.uri?.toString()?.let {
            if (Uri.parse(it).scheme in listOf("http", "https")) urls += it
        }
    }
    return urls
}
