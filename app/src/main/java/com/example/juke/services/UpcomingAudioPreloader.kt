package com.example.juke.services

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
class UpcomingAudioPreloader(private val factory: DataSource.Factory, private val scope: CoroutineScope, private val context: android.content.Context? = null) {
    private data class Work(val writer: CacheWriter, val source: CacheDataSource, val job: Job)
    private val work = mutableMapOf<String, Work>()
    private val completed = mutableSetOf<String>()
    private val slots = Semaphore(2)
    fun update(urls: List<String>, buffering: Boolean = false) {
        val connectivity = context?.getSystemService(android.net.ConnectivityManager::class.java)
        val policy = preloadPolicy(com.example.juke.network.NetworkFeedback.online.value,
            connectivity?.isActiveNetworkMetered ?: true,
            context?.getSharedPreferences("music_settings_prefs", android.content.Context.MODE_PRIVATE)?.getBoolean("prefetch_mobile_data", false) ?: false, buffering)
        val wanted = urls.distinct().filter { Uri.parse(it).scheme in listOf("https", "http") }.take(policy.tracks).toSet()
        (work.keys - wanted).forEach { key -> work.remove(key)?.let { obsolete ->
            obsolete.writer.cancel(); obsolete.job.cancel()
            scope.launch(Dispatchers.IO + NonCancellable) { runCatching { obsolete.source.close() } }
        } }
        completed.retainAll(wanted)
        wanted.filter { it !in work && it !in completed }.forEach { url ->
            val source = factory.createDataSource() as CacheDataSource
            val writer = CacheWriter(source, DataSpec.Builder().setUri(Uri.parse(url)).setLength(policy.bytesPerTrack).build(), null, null)
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    slots.withPermit { withContext(Dispatchers.IO) { writer.cache() } }
                    completed.add(url)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Playback may still stream; a later queue update retries preloading. */ }
                finally { if (work[url]?.writer === writer) work.remove(url) }
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
