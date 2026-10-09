package `in`.synthora.musicbox.services

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import `in`.synthora.musicbox.MainActivity
import `in`.synthora.musicbox.R

/** Keeps the explicitly opened device reachable when its media session is paused. */
class DeviceConnectionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Device connection", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
        })
    }
    private fun promoteForeground(): Boolean {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return try {
            startForeground(ID, NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.media3_notification_small_icon)
                .setContentTitle("Music Box")
                .setContentText("Available for playback from your other devices")
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).build())
            true
        } catch (error: RuntimeException) {
            `in`.synthora.musicbox.utils.SafeLog.w("DeviceConnection", "Foreground promotion denied: ${error.javaClass.simpleName}")
            false
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every startForegroundService call creates a deadline, including starts
        // of an existing instance. Satisfy it before auth, model or network work.
        if (!promoteForeground()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!`in`.synthora.musicbox.network.Backend.hasSession()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent == null) {
            // A sticky restart has no Activity. Recreate the same application-owned
            // command handler the screen uses, without selecting an idle device.
            val model = (application as `in`.synthora.musicbox.JukeApplication).playbackModel()
            model.setForeground(false)
            model.onSignedIn()
        }
        return START_STICKY
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        MobileDeviceConnection.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    companion object {
        private const val CHANNEL = "device_connection"
        private const val ID = 1003
        fun startWhileVisible(context: android.content.Context) {
            if (!ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
            try { ContextCompat.startForegroundService(context, Intent(context, DeviceConnectionService::class.java)) }
            catch (error: RuntimeException) { `in`.synthora.musicbox.utils.SafeLog.w("DeviceConnection", "Connection service unavailable: ${error.javaClass.simpleName}") }
        }
        fun stop(context: android.content.Context) { context.stopService(Intent(context, DeviceConnectionService::class.java)) }
    }
}
