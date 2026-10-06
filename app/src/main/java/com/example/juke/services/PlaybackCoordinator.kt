package com.example.juke.services

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Application-owned remote state: UI and notification always observe the same snapshot. */
object PlaybackCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: EchoController? = null
    private val consumers = mutableMapOf<String, Boolean>()
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val errors = _errors.asSharedFlow()
    private val _signedOut = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val signedOut = _signedOut.asSharedFlow()

    fun echo(context: Context): EchoController = controller ?: EchoController(scope,
        context.applicationContext.getSharedPreferences("music_settings_prefs", Context.MODE_PRIVATE),
        onError = { _errors.tryEmit(it) }, onSignedOut = {
            consumers.clear(); controller?.stopPolling(); _signedOut.tryEmit(Unit)
        }).also { controller = it }

    fun observe(context: Context, consumer: String, active: Boolean, foreground: Boolean = false) {
        if (active) consumers[consumer] = foreground else consumers.remove(consumer)
        val echo = echo(context)
        if (consumers.isEmpty()) echo.stopPolling()
        else echo.startPolling(consumers.values.any { it })
    }

    fun dismiss(context: Context) {
        consumers.clear()
        echo(context).stopPolling()
    }
    fun clear(context: Context) { dismiss(context); echo(context).clear() }
}

/** State reads are separate from the short, mandatory phone ownership lease. */
fun remotePollDelayMs(foreground: Boolean, playing: Boolean, processing: Boolean, failures: Int): Long {
    val base = when {
        processing -> 3_000L
        foreground && playing -> 3_000L
        foreground -> 10_000L
        playing -> 10_000L
        else -> 30_000L
    }
    return (base * (1L shl failures.coerceIn(0, 4))).coerceAtMost(60_000L)
}
