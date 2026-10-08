package com.example.juke.services

import android.content.Context
import android.content.ContextWrapper
import android.content.ServiceConnection
import android.util.Log

/** Media3 releases service bindings on a posted callback, outside releaseFuture's stack. */
internal class ControllerBindingContext(context: Context) : ContextWrapper(context) {
    override fun unbindService(connection: ServiceConnection) {
        releaseControllerBinding(
            unbind = { super.unbindService(connection) },
            alreadyUnbound = { Log.w("PlaybackManager", "Controller binding already removed") }
        )
    }
}
