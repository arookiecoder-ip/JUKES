package com.example.juke.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.juke.network.Backend
import com.example.juke.network.DownloadCookies
import com.example.juke.network.BackendAuthException
import com.example.juke.network.LoginStep
import com.example.juke.network.flag
import com.example.juke.network.objectOrEmpty
import com.example.juke.network.text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class AuthStage { CHECKING, SIGNED_OUT, NEEDS_CODE, SIGNED_IN }

/** The server's view of the linked accounts (GET /api/profile_status/). */
data class AccountStatus(
    val amazonConnected: Boolean = false,
    /** YouTube Music browser headers work: personalized Home, library, likes. */
    val youtubeLibrary: Boolean = false,
    /** An actual audio sample download passed on the configured audio server. */
    val youtubeCookies: Boolean = false,
    val youtubeCookiesPresent: Boolean = false,
    val youtubeBrowserAvailable: Boolean = false,
    val youtubeReconnectRequired: Boolean = false
) {
    val allConnected get() = amazonConnected && youtubeLibrary && youtubeCookies
}

data class AccountUiState(
    val stage: AuthStage = AuthStage.CHECKING,
    val busy: Boolean = false,
    val cookieBusy: Boolean = false,
    val cookieMessage: String? = null,
    val cookieSaved: Long = 0,
    val error: String? = null,
    /** Shown once after signing in, while the accounts are checked. */
    val showAccountCheck: Boolean = false,
    val status: AccountStatus? = null,
    val checkingStatus: Boolean = false,
    val amazonMessage: String? = null,
    val youtubeMessage: String? = null,
    /** A vendor sign-in page to open in the account browser. */
    val browserUrl: String? = null
)

/**
 * Web-remote sign-in (username, password, authenticator code) and the account checks the web
 * app runs after it: Amazon for the Echo, YouTube Music for personalized music and playback.
 */
class AccountViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(AccountUiState())
    val state: StateFlow<AccountUiState> = _state.asStateFlow()

    /** Fires when Amazon or YouTube was (re)connected, so Home, library and Echo reload. */
    private val _accountsChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val accountsChanged: SharedFlow<Unit> = _accountsChanged.asSharedFlow()

    private var codeToken = ""
    private var busyJob: Job? = null
    private var statusJob: Job? = null
    private var accountPoll: Job? = null
    private val downloadCookies = DownloadCookies()
    private var cookieJob: Job? = null
    private var browserFlow: String? = null

    init {
        checkSession()
    }

    private fun checkSession() {
        if (!Backend.hasSession()) {
            _state.value = AccountUiState(stage = AuthStage.SIGNED_OUT)
            return
        }
        // A saved session can open cached screens while validation runs in the background.
        _state.update { it.copy(stage = AuthStage.SIGNED_IN) }
        viewModelScope.launch {
            val stage = try {
                Backend.get("/alexa/status/")
                AuthStage.SIGNED_IN
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackendAuthException) {
                Backend.clearSession()
                AuthStage.SIGNED_OUT
            } catch (e: Exception) {
                // Offline or server unreachable: keep the saved session and let screens retry.
                AuthStage.SIGNED_IN
            }
            _state.update { it.copy(stage = stage) }
            if (stage == AuthStage.SIGNED_IN) refreshStatus()
        }
    }

    fun login(username: String, password: String) {
        if (username.isBlank() || password.isEmpty()) {
            _state.update { it.copy(error = "Enter your username and password") }
            return
        }
        runBusy {
            when (val step = Backend.login(username.trim(), password)) {
                is LoginStep.NeedsCode -> {
                    codeToken = step.token
                    _state.update { it.copy(stage = AuthStage.NEEDS_CODE) }
                }
                LoginStep.SignedIn -> onSignedIn()
            }
        }
    }

    fun verifyCode(code: String) {
        val digits = code.filter { it.isDigit() }
        if (digits.length != 6) {
            _state.update { it.copy(error = "Enter the 6-digit code from your authenticator app") }
            return
        }
        runBusy {
            try {
                Backend.verifyCode(codeToken, digits)
            } catch (e: BackendAuthException) {
                // An expired login must start over; a wrong code can be retried.
                if (e.message?.contains("timed out", ignoreCase = true) == true) {
                    codeToken = ""
                    _state.update { it.copy(stage = AuthStage.SIGNED_OUT) }
                }
                throw e
            }
            codeToken = ""
            onSignedIn()
        }
    }

    /** Back from the code step to username and password. */
    fun restartLogin() {
        codeToken = ""
        _state.update { it.copy(stage = AuthStage.SIGNED_OUT, error = null) }
    }

    private fun onSignedIn() {
        _state.update { it.copy(stage = AuthStage.SIGNED_IN, showAccountCheck = true, status = null, error = null) }
        refreshStatus()
    }

    /** Leave the account check and open the app. */
    fun continueToApp() {
        _state.update { it.copy(showAccountCheck = false) }
    }

    /** Check Amazon and YouTube on the server (the YouTube cookie check takes a few seconds). */
    fun refreshStatus(checkAudio: Boolean = false) {
        if (cookieJob?.isActive == true) return
        statusJob?.cancel()
        statusJob = viewModelScope.launch {
            _state.update { it.copy(checkingStatus = true) }
            try {
                val profile = Backend.get("/api/profile_status/", mapOf("audio_check" to "0")).objectOrEmpty()
                val status = AccountStatus(
                    amazonConnected = profile.flag("amazon_connected"),
                    youtubeLibrary = profile.flag("youtube_auth_working"),
                    youtubeCookies = _state.value.status?.youtubeCookies ?: false,
                    youtubeCookiesPresent = profile.flag("youtube_cookies_present"),
                    youtubeBrowserAvailable = profile.flag("youtube_browser_available"),
                    youtubeReconnectRequired = profile.flag("youtube_browser_reconnect_required")
                )
                _state.update { it.copy(status = status) }
                if (checkAudio) {
                    val result = downloadCookies.check()
                    _state.update { it.copy(status = (it.status ?: AccountStatus()).copy(youtubeCookies = result.valid), cookieMessage = result.message) }
                }
                // Audio probes remain explicit, never on startup.
                // Everything is connected: carry on into the app without a tap.
                if (_state.value.status?.allConnected == true && _state.value.showAccountCheck) {
                    delay(1_200)
                    _state.update { it.copy(showAccountCheck = false) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackendAuthException) {
                sessionEnded()
            } catch (e: Exception) {
                _state.update { it.copy(error = "Couldn't check your accounts: ${com.example.juke.network.networkErrorMessage(e) ?: e.message}") }
            } finally {
                _state.update { it.copy(checkingStatus = false) }
            }
        }
    }

    fun testDownloadCookies() = cookieAction {
        val result = downloadCookies.check()
        _state.update { it.copy(status = (it.status ?: AccountStatus()).copy(youtubeCookies = result.valid), cookieMessage = result.message) }
    }

    fun replaceDownloadCookies(export: String) = cookieAction {
        val result = downloadCookies.replace(export)
        _state.update { it.copy(status = (it.status ?: AccountStatus()).copy(youtubeCookies = result.valid, youtubeCookiesPresent = true),
            cookieMessage = "Audio download passed. New cookies saved.", cookieSaved = it.cookieSaved + 1) }
    }

    private fun cookieAction(block: suspend () -> Unit) {
        if (cookieJob?.isActive == true) return
        // An explicit cookie action takes precedence over the background account check.
        statusJob?.cancel()
        _state.update { it.copy(checkingStatus = false) }
        cookieJob = viewModelScope.launch {
            _state.update { it.copy(cookieBusy = true, cookieMessage = "Downloading an audio sample…") }
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(cookieMessage = e.message ?: "Cookie test failed. Existing cookies were kept.") } }
            finally { _state.update { it.copy(cookieBusy = false) } }
        }
    }

    // ---------- Amazon (Echo control) ----------

    fun connectAmazon(email: String, password: String) {
        if (email.isBlank() || password.isEmpty()) {
            _state.update { it.copy(amazonMessage = "Enter your Amazon email and password") }
            return
        }
        runBusy {
            val result = Backend.post("/alexa/proxy_login/", JsonObject(mapOf(
                "email" to JsonPrimitive(email.trim()),
                "password" to JsonPrimitive(password),
                // Reconnecting replaces the server's single Amazon session on purpose.
                "force" to JsonPrimitive(true)
            ))).objectOrEmpty()
            val url = result.text("login_url")
            require(url.isNotBlank()) { "The server didn't return an Amazon sign-in page" }
            browserFlow = "amazon"
            _state.update { it.copy(browserUrl = absolute(url), amazonMessage = "Finish signing in to Amazon…") }
            pollAccount("amazon")
        }
    }

    fun disconnectAmazon() {
        runBusy {
            Backend.post("/alexa/amazon_signout/")
            _accountsChanged.tryEmit(Unit)
            refreshStatus()
        }
    }

    // ---------- YouTube Music ----------

    fun connectYouTube(retry: Boolean = false) {
        runBusy {
            val data = Backend.post(
                if (retry) "/api/youtube/browser-session/retry" else "/api/youtube/browser-session/start"
            ).objectOrEmpty()
            val url = data.text("url")
            browserFlow = "youtube"
            _state.update {
                it.copy(
                    youtubeMessage = data.text("message").ifBlank { youtubeLabel(data.text("state")) },
                    browserUrl = url.takeIf { u -> u.isNotBlank() }?.let(::absolute)
                )
            }
            pollAccount("youtube")
        }
    }

    /** The account browser was closed. An unfinished YouTube session is stopped on the server. */
    fun browserClosed() {
        val flow = browserFlow
        _state.update { it.copy(browserUrl = null) }
        if (flow == "youtube" && accountPoll?.isActive == true) {
            accountPoll?.cancel()
            browserFlow = null
            viewModelScope.launch {
                runCatching { Backend.post("/api/youtube/browser-session/stop") }
                _state.update { it.copy(youtubeMessage = null) }
                refreshStatus()
            }
        }
    }

    /** Poll the sign-in like the web app: Amazon every 2.5 s, YouTube every 1.5 s, up to 120 times. */
    private fun pollAccount(flow: String) {
        accountPoll?.cancel()
        accountPoll = viewModelScope.launch {
            repeat(120) {
                delay(if (flow == "amazon") 2_500 else 1_500)
                if (!isActive) return@launch
                try {
                    if (flow == "amazon") {
                        val result = Backend.get("/alexa/proxy_check/").objectOrEmpty()
                        if (result.flag("logged_in")) {
                            finishAccountFlow(amazon = "Amazon connected")
                            return@launch
                        }
                        val error = result.text("error")
                        if (error.isNotBlank()) {
                            browserFlow = null
                            _state.update { it.copy(amazonMessage = error, browserUrl = null) }
                            return@launch
                        }
                    } else {
                        val result = Backend.get("/api/youtube/browser-session/status").objectOrEmpty()
                        val state = result.text("state")
                        _state.update { it.copy(youtubeMessage = result.text("message").ifBlank { youtubeLabel(state) }) }
                        when (state) {
                            "connected" -> {
                                finishAccountFlow(youtube = "YouTube Music connected")
                                return@launch
                            }
                            "reconnect_required", "unavailable", "idle" -> {
                                browserFlow = null
                                return@launch
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: BackendAuthException) {
                    sessionEnded()
                    return@launch
                } catch (_: Exception) {
                    // Keep polling through a dropped request.
                }
            }
            browserFlow = null
            _state.update { it.copy(browserUrl = null, amazonMessage = null, youtubeMessage = "Sign-in timed out. Try again.") }
        }
    }

    private fun finishAccountFlow(amazon: String? = null, youtube: String? = null) {
        browserFlow = null
        _state.update {
            it.copy(
                browserUrl = null,
                amazonMessage = amazon ?: it.amazonMessage,
                youtubeMessage = youtube ?: it.youtubeMessage
            )
        }
        _accountsChanged.tryEmit(Unit)
        refreshStatus()
    }

    private fun youtubeLabel(state: String): String = when (state) {
        "connecting" -> "Starting the secure browser…"
        "waiting_for_login" -> "Sign in to YouTube Music in the browser…"
        "captured", "capture_requested" -> "Signed in. Saving…"
        "settling_session" -> "Saving the signed-in browser profile…"
        "validating", "validating_login" -> "Checking your YouTube Music account…"
        "refreshing" -> "Refreshing the saved session…"
        "connected" -> "Connected"
        "reconnect_required" -> "Google needs you to sign in again or finish a challenge."
        "unavailable" -> "The sign-in browser is unavailable on the server."
        else -> ""
    }

    fun signOut() {
        busyJob?.cancel()
        statusJob?.cancel()
        cookieJob?.cancel()
        accountPoll?.cancel()
        codeToken = ""
        browserFlow = null
        _state.value = AccountUiState(stage = AuthStage.SIGNED_OUT, busy = true)
        busyJob = viewModelScope.launch {
            try { Backend.logout() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Local session is cleared even if the server is offline. */ }
            finally { _state.update { it.copy(busy = false) } }
        }
    }

    /** The server no longer accepts the session (expired, revoked or signed out elsewhere). */
    fun sessionEnded() {
        if (_state.value.stage == AuthStage.SIGNED_OUT) return
        Backend.clearSession()
        cookieJob?.cancel()
        busyJob?.cancel()
        codeToken = ""
        browserFlow = null
        accountPoll?.cancel()
        statusJob?.cancel()
        _state.value = AccountUiState(stage = AuthStage.SIGNED_OUT, error = "Your session ended. Sign in again.")
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    private fun absolute(url: String) = if (url.startsWith("/")) Backend.baseUrl + url else url

    private fun runBusy(block: suspend () -> Unit) {
        if (busyJob?.isActive == true) return
        busyJob = viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackendAuthException) {
                val stage = _state.value.stage
                if (stage == AuthStage.SIGNED_IN) sessionEnded()
                else _state.update { it.copy(error = com.example.juke.network.networkErrorMessage(e) ?: e.message ?: "Sign-in failed") }
            } catch (e: Exception) {
                _state.update { it.copy(error = com.example.juke.network.networkErrorMessage(e) ?: e.message ?: "Something went wrong") }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }
}
