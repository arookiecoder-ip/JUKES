package `in`.synthora.musicbox.services

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock

/** Offloaded audio may sleep the CPU before its independent cache writer finishes. */
interface AudioDownloadGuard : AutoCloseable {
    fun progress()
}

internal fun currentAudioDownloadGuard(context: Context): AudioDownloadGuard {
    val lock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "musicbox:current-audio-cache")
    lock.setReferenceCounted(false)
    val started = SystemClock.elapsedRealtime()
    var renewed = started
    lock.acquire(120_000)
    return object : AudioDownloadGuard {
        override fun progress() {
            val now = SystemClock.elapsedRealtime()
            // Renew only on real download progress; no indefinite lock during an outage.
            val remaining = 10 * 60_000L - (now - started)
            if (now - renewed >= 60_000 && remaining > 0) {
                lock.acquire(minOf(120_000, remaining))
                renewed = now
            }
        }
        override fun close() { if (lock.isHeld) lock.release() }
    }
}
