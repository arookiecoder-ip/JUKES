package com.example.juke.services

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList

/** Deliver delayed artwork only for the live output, on Media3's main application looper. */
@UnstableApi
internal class GuardedMediaNotificationProvider(
    private val delegate: MediaNotification.Provider,
    private val active: () -> Boolean,
    private val onDenied: () -> Unit
) : MediaNotification.Provider {
    private val main = Handler(Looper.getMainLooper())
    override fun createNotification(session: MediaSession, buttons: ImmutableList<CommandButton>,
        actions: MediaNotification.ActionFactory, callback: MediaNotification.Provider.Callback): MediaNotification =
        delegate.createNotification(session, buttons, actions) { notification ->
            main.post {
                if (active()) deliverNotificationUpdate({ callback.onNotificationChanged(notification) },
                    { Build.VERSION.SDK_INT >= 31 && it is android.app.ForegroundServiceStartNotAllowedException }, onDenied)
            }
        }
    override fun handleCustomCommand(session: MediaSession, action: String, extras: Bundle): Boolean =
        delegate.handleCustomCommand(session, action, extras)
}
