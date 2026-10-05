package com.example.juke.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException as KtorSocketTimeoutException

/** Shared network messages for account, browsing and playback; repeated polls are coalesced. */
object NetworkFeedback {
    private val events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = events.asSharedFlow()
    private var lastMessage = ""
    private var lastAt = 0L
    private var observing = false

    @Synchronized
    fun notify(message: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (message == lastMessage && now - lastAt < 30_000) return
        lastMessage = message; lastAt = now
        events.tryEmit(message)
    }

    fun observe(context: Context) {
        if (observing) return
        observing = true
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        var disconnected = false
        fun connected() = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                if (!connected()) {
                    disconnected = true
                    notify("Connection lost. Check your internet connection and retry.")
                }
            }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (disconnected && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    disconnected = false
                    notify("Connection restored. You can retry loading.")
                }
            }
        })
    }
}

fun networkErrorMessage(error: Throwable): String? {
    if (error is CancellationException) return null
    val causes = generateSequence(error) { it.cause }.take(8).toList()
    return when {
        causes.any { it is SocketTimeoutException || it is HttpRequestTimeoutException || it is ConnectTimeoutException || it is KtorSocketTimeoutException } ->
            "The connection is taking too long. Please try again."
        causes.any { it is IOException } -> "Couldn't connect. Check your internet connection and try again."
        else -> null
    }
}
