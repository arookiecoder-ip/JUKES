package com.example.juke.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.juke.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/** A download notification has its own channel/actions; it never behaves like media playback. */
class DownloadService : Service() {
    companion object {
        const val OPEN_DOWNLOADS = "open_music_downloads"
        internal const val CANCEL = "cancel_music_downloads"
        internal const val CHANNEL = "music_downloads"
        internal const val ACTIVE_ID = 1201
        internal const val FINISHED_ID = 1202
        fun start(context: Context) {
            try { ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java)) }
            catch (_: Exception) { /* OS transfers remain managed by DownloadManager; retry on next foreground start. */ }
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing: Job? = null
    private val manager get() = getSystemService(NotificationManager::class.java)
    override fun onCreate() {
        super.onCreate()
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Music downloads", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val downloads = DownloadRepository.get(this)
        startForeground(ACTIVE_ID, downloadNotification(this, downloads.status.value, true))
        if (intent?.action == CANCEL) downloads.cancelAll()
        if (observing?.isActive != true) observing = scope.launch {
            downloads.status.collect { state ->
                if (state.active > 0) manager.notify(ACTIVE_ID, downloadNotification(this@DownloadService, state, true))
                else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    if (state.completed > 0 || state.failed > 0) manager.notify(FINISHED_ID, downloadNotification(this@DownloadService, state, false))
                    stopSelf(); cancel()
                }
            }
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        DownloadRepository.get(this).cancelAll(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

internal fun downloadDestinationIntent(context: Context): Intent = Intent(context, MainActivity::class.java)
    .putExtra(DownloadService.OPEN_DOWNLOADS, true)
    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

internal fun downloadNotification(context: Context, state: DownloadStatus, ongoing: Boolean): android.app.Notification {
        val open = PendingIntent.getActivity(context, 1201, downloadDestinationIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, DownloadService.CHANNEL)
            .setSmallIcon(if (ongoing) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_download_done)
            .setContentTitle(if (ongoing) "Downloading music" else if (state.failed > 0) "Some downloads failed" else "Downloads complete")
            .setContentText(if (ongoing) "${state.title} · ${state.completed}/${state.total} complete"
                else "${state.completed} downloaded" + if (state.failed > 0) " · ${state.failed} failed" else "")
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(ongoing).setAutoCancel(!ongoing)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .apply {
                if (ongoing) {
                    setProgress(100, state.percent.coerceAtLeast(0), state.percent < 0)
                    val cancel = PendingIntent.getService(context, 1202,
                        Intent(context, DownloadService::class.java).setAction(DownloadService.CANCEL),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancel)
                }
            }.build()
    }
