package `in`.synthora.musicbox.network

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
    // True while any Activity is at least STARTED. Background polling failures and
    // transient Doze/VPN handovers must update `online` silently, never toast.
    @Volatile var isForeground = false
        private set

    fun setForeground(foreground: Boolean) {
        isForeground = foreground
    }

    /** Toasts are only useful while the user can see them. Background callers still
     *  get an exception, but no global toast is emitted. */
    @Volatile var suppressBackgroundToasts = true

    private fun hasInternet(capabilities: NetworkCapabilities?): Boolean =
        capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    fun refresh(context: Context) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
        connectivityState.value = hasInternet(capabilities)
    }

    @Synchronized
    fun notify(message: String, foregroundOnly: Boolean = true) {
        if (foregroundOnly && suppressBackgroundToasts && !isForeground) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (message == lastMessage && now - lastAt < 30_000) return
        lastMessage = message; lastAt = now
        events.tryEmit(message)
    }

    fun observe(context: Context) {
        if (observing) return
        observing = true
        val appContext = context.applicationContext
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
        refresh(appContext)
        var disconnected = !connectivityState.value
        // Debounce transient background handovers (Doze / WiFi power-save / VPN):
        // only declare offline after the loss persists, and never toast while backgrounded.
        var pendingOfflineAt = 0L
        fun connected() = hasInternet(connectivity.getNetworkCapabilities(connectivity.activeNetwork))
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { refresh(appContext); pendingOfflineAt = 0L }
            override fun onLost(network: Network) {
                refresh(appContext)
                if (!connected()) {
                    pendingOfflineAt = android.os.SystemClock.elapsedRealtime()
                    connectivityState.value = false
                    // Post a delayed offline notice; cancelled if the network returns quickly
                    // or if the app is in the background.
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (!connected()) {
                            disconnected = true
                            pendingOfflineAt = 0L
                            notify("Connection lost. Check your internet connection and retry.")
                        }
                    }, 4000)
                }
            }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val hasInternetNow = hasInternet(capabilities)
                connectivityState.value = hasInternetNow
                if (hasInternetNow) {
                    pendingOfflineAt = 0L
                    if (disconnected) {
                        disconnected = false
                        notify("Connection restored. You can retry loading.")
                    }
                } else if (disconnected) {
                    // Already offline: don't re-toast on every capability flap.
                } else {
                    // Transient VALIDATED loss (captive-portal probe blocked in Doze) is not
                    // a real offline event — update state silently, toast only via onLost path.
                    disconnected = false
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
        causes.any { it is androidx.media3.common.ParserException } -> "The audio data could not be read. Retrying a fresh copy may help."
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
