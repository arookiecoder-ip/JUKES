package `in`.synthora.musicbox.services

import android.content.Context
import android.util.AtomicFile
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import `in`.synthora.musicbox.BuildConfig
import `in`.synthora.musicbox.utils.redactSecrets
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File

/** One process-wide consumer; no polling, wake locks, or writes for ordinary playback. */
internal object PlaybackErrorRecorder {
    private const val LIMIT = 24_000
    private val diskLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = Channel<Pair<File, String>>(Channel.CONFLATED)
    @Volatile private var destination: File? = null

    init {
        scope.launch {
            for ((file, report) in pending) writeReport(file, report)
        }
    }

    fun init(context: Context) {
        destination = File(context.applicationContext.filesDir, "last-audio-error.txt")
    }

    fun capture(error: PlaybackException) {
        // Diagnostics must not prevent the existing recovery path from running.
        runCatching {
            val file = destination ?: return
            val causes = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
            val parser = causes.filterIsInstance<ParserException>().firstOrNull()
            val key = runCatching { `in`.synthora.musicbox.network.Backend.apiKey }.getOrDefault("")
            val report = buildString {
                appendLine("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("Saved at: ${System.currentTimeMillis()}")
                appendLine("Error code: ${error.errorCode}")
                appendLine("Causes: ${causes.map { it.javaClass.simpleName }}")
                parser?.let { appendLine("Parser message: ${safeParserMessage(it.message, key)}") }
                causes.filter { it is java.io.IOException }.take(4).forEach { cause ->
                    appendLine("IO message (${cause.javaClass.simpleName}): ${safeParserMessage(cause.message, key)}")
                    cause.stackTrace.take(10).forEach { frame ->
                        appendLine("  at ${safeParserMessage(frame.toString(), key)}")
                    }
                }
                appendLine("Playback events:")
                PlaybackDiagnostics.recentPlaybackEvents().forEach { appendLine(it) }
                appendLine("Audio responses:")
                PlaybackDiagnostics.recentAudioResponses().forEach { appendLine(it) }
                appendLine("Audio transfers:")
                PlaybackDiagnostics.recentAudioTransfers().forEach { appendLine(it) }
            }.take(LIMIT)
            pending.trySend(file to report)
        }
    }

    suspend fun savedReport(context: Context): String = withContext(Dispatchers.IO) {
        readReport(File(context.applicationContext.filesDir, "last-audio-error.txt"))
    }

    internal fun safeParserMessage(message: String?, key: String = ""): String =
        redactSecrets(message.orEmpty().take(2_000), key)
            .replace(Regex("""(?i)(?:https?|file|content)://[^\s]+"""), "[URI]")
            .replace(Regex("""/[A-Za-z0-9_.~-]+(?:/[^\s]*)?"""), "[PATH]")
            .replace('\n', ' ').replace('\r', ' ').take(600)

    internal fun writeReport(file: File, report: String) = synchronized(diskLock) {
        val atomic = AtomicFile(file)
        var stream: java.io.FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(report.take(LIMIT).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (_: Exception) {
            stream?.let { runCatching { atomic.failWrite(it) } }
        }
    }

    internal fun readReport(file: File): String = synchronized(diskLock) { runCatching {
        AtomicFile(file).openRead().use { input ->
            // InputStream.readNBytes requires newer Android APIs; use a bounded buffer instead.
            val bytes = ByteArray(LIMIT * 4)
            var count = 0
            while (count < bytes.size) {
                val n = input.read(bytes, count, bytes.size - count)
                if (n <= 0) break
                count += n
            }
            String(bytes, 0, count, Charsets.UTF_8).take(LIMIT)
        }
    }.getOrDefault("(none recorded)") }
}
