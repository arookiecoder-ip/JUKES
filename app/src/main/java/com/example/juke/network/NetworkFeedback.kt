package com.example.juke.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val connectivityState = MutableStateFlow(true)
    val online = connectivityState.asStateFlow()

    fun refresh(context: Context) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
        connectivityState.value = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }

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
        refresh(context)
        var disconnected = !connectivityState.value
        fun connected() = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { refresh(context) }
            override fun onLost(network: Network) {
                refresh(context)
                if (!connected()) {
                    disconnected = true
                    notify("Connection lost. Check your internet connection and retry.")
                }
            }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                connectivityState.value = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (disconnected && connectivityState.value) {
                    disconnected = false
                    notify("Connection restored. You can retry loading.")
                }
                disconnected = !connectivityState.value
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

/** A feature endpoint rejecting access does not prove that the account session expired. */
internal suspend fun confirmSessionExpired(checkSession: suspend () -> Unit): Boolean = try {
    checkSession()
    false
} catch (error: CancellationException) {
    throw error
} catch (error: BackendAuthException) {
    true
} catch (error: Exception) {
    // Keep the session during outages or unrelated server failures.
    false
}
