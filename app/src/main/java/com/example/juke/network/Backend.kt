package com.example.juke.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import android.content.Context
import android.content.SharedPreferences
import com.example.juke.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** The web-remote session expired or was never opened: the user must sign in again. */
class BackendAuthException(message: String) : Exception(message)
class BackendHttpException(val statusCode: Int, message: String) : IllegalStateException(message)

/** Result of the first login step. */
sealed interface LoginStep {
    data object SignedIn : LoginStep
    data class NeedsCode(val token: String) : LoginStep
}

/**
 * The self-hosted server behind the web remote. Account, library, Home and Echo calls use the
 * owner's web session (username + password + authenticator code), stored in a persistent cookie
 * jar so the app stays signed in. Only the audio stream endpoints use the build's API key.
 */
object Backend {

    private const val DEFAULT_SERVER = "https://alexa.synthora.in"

    val audioBaseUrl: String get() = BuildConfig.ALEXA_BASE_URL.trim().trimEnd('/').ifBlank { DEFAULT_SERVER }
    val baseUrl: String get() = BuildConfig.ALEXA_SESSION_BASE_URL.trim().trimEnd('/').ifBlank { audioBaseUrl }
    val apiKey: String get() = BuildConfig.ALEXA_API_KEY.trim()

    private lateinit var jar: PersistentCookieJar

    fun init(context: Context) {
        if (!::jar.isInitialized) {
            jar = PersistentCookieJar(context.getSharedPreferences("backend_session", Context.MODE_PRIVATE))
        }
    }

    private val client by lazy {
        HttpClient(OkHttp) {
            expectSuccess = false
            followRedirects = false
            engine {
                config {
                    cookieJar(jar)
                    followRedirects(false)
                    followSslRedirects(false)
                    retryOnConnectionFailure(true)
                }
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 45_000
                connectTimeoutMillis = 20_000
                socketTimeoutMillis = 30_000
            }
        }
    }

    /** True while a web-session cookie is stored (it may still have been revoked server-side). */
    fun hasSession(): Boolean = jar.hasCookies()

    fun clearSession() = jar.clear()

    suspend fun get(path: String, query: Map<String, String> = emptyMap()): JsonElement =
        call(HttpMethod.Get, path, query, null)

    suspend fun post(path: String, body: JsonObject = JsonObject(emptyMap())): JsonElement =
        call(HttpMethod.Post, path, emptyMap(), body)

    suspend fun patch(path: String, body: JsonObject): JsonElement =
        call(HttpMethod.Patch, path, emptyMap(), body)

    // Session writes must be JSON (the server's CSRF guard), so DELETE always carries a body.
    suspend fun delete(path: String, body: JsonObject = JsonObject(emptyMap())): JsonElement =
        call(HttpMethod.Delete, path, emptyMap(), body)

    /**
     * Step 1 of the web login. Returns [LoginStep.NeedsCode] when the owner account has an
     * authenticator, otherwise the session is open.
     */
    suspend fun login(username: String, password: String): LoginStep {
        val result = post("/login/", JsonObject(mapOf(
            "username" to JsonPrimitive(username),
            "password" to JsonPrimitive(password)
        ))).objectOrEmpty()
        return if (result.flag("totp_required")) LoginStep.NeedsCode(result.text("token"))
        else LoginStep.SignedIn
    }

    /** Step 2 of the web login: the 6-digit authenticator code. */
    suspend fun verifyCode(token: String, code: String) {
        post("/login/", JsonObject(mapOf(
            "step" to JsonPrimitive("totp"),
            "token" to JsonPrimitive(token),
            "code" to JsonPrimitive(code.trim())
        )))
    }

    suspend fun logout() {
        try { post("/logout/") } finally { clearSession() }
    }

    private suspend fun call(method: HttpMethod, path: String, query: Map<String, String>, body: JsonObject?): JsonElement = coroutineScope {
        val slowNotice = launch {
            delay(8_000)
            NetworkFeedback.notify("Taking longer than usual to load. Please wait…")
        }
        try {
            performCall(method, path, query, body)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val message = networkErrorMessage(error)
            if (message != null) {
                NetworkFeedback.notify(message)
                throw IOException(message, error)
            }
            throw error
        } finally {
            slowNotice.cancel()
        }
    }

    private suspend fun performCall(method: HttpMethod, path: String, query: Map<String, String>, body: JsonObject?): JsonElement {
        require(path.startsWith("/") && !path.startsWith("//"))
        val response = client.request(baseUrl + path) {
            this.method = method
            header(HttpHeaders.Accept, "application/json")
            query.forEach { (name, value) -> parameter(name, value) }
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        }
        val text = response.bodyAsText()
        val parsed = runCatching { Json.parseToJsonElement(text) }.getOrNull()
        val code = response.status.value
        if (code == 401) {
            throw BackendAuthException(errorMessage(parsed) ?: "Your session has ended. Sign in again.")
        }
        // Without a session the server redirects browser-style requests to its login page.
        if (code in 300..399) {
            if (path == "/login/") {
                throw IllegalStateException("Account login is unavailable on this server. Update the app or contact the server administrator.")
            }
            throw BackendAuthException("Sign in to continue.")
        }
        if (code !in 200..299) {
            val message = when (code) {
                408, 504 -> "The server took too long to respond. Please try again."
                429 -> "Too many requests. Please wait a moment and try again."
                in 500..599 -> "The server is temporarily unavailable. Please try again."
                else -> errorMessage(parsed) ?: "Request failed ($code). Please try again."
            }
            if (code >= 500 || code == 408 || code == 429) NetworkFeedback.notify(message)
            throw BackendHttpException(code, message)
        }
        return parsed ?: throw IllegalStateException("The server returned an unreadable response. Please try again.")
    }

    private fun errorMessage(parsed: JsonElement?): String? {
        val error = parsed.objectOrEmpty()["error"]
        return ((error as? JsonPrimitive)?.contentOrNull ?: error.objectOrEmpty().text("message"))
            .takeIf { it.isNotBlank() }
    }

    /** Cookie headers for the account sign-in browser, so it opens with the same session. */
    fun cookieHeaders(): List<String> = jar.all().map { cookie ->
        // Path "/" so a staging base path still sends the session to every sign-in page.
        "${cookie.name}=${cookie.value}; Path=/; Secure"
    }
}

/** Keeps the web session across app restarts. */
internal class PersistentCookieJar(private val prefs: SharedPreferences) : CookieJar {
    private val cookies = linkedMapOf<String, Cookie>()

    init {
        prefs.getStringSet(KEY, emptySet()).orEmpty().forEach { stored ->
            val (url, header) = stored.split('\n', limit = 2).takeIf { it.size == 2 } ?: return@forEach
            val parsed = url.toHttpUrlOrNull()?.let { Cookie.parse(it, header) } ?: return@forEach
            cookies[key(parsed)] = parsed
        }
    }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        cookies.forEach { cookie ->
            if (cookie.expiresAt < now) this.cookies.remove(key(cookie)) else this.cookies[key(cookie)] = cookie
        }
        persist()
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        if (cookies.values.removeAll { it.expiresAt < now }) persist()
        return cookies.values.filter { it.matches(url) }
    }

    @Synchronized
    fun hasCookies(): Boolean = cookies.values.any { it.expiresAt >= System.currentTimeMillis() }

    @Synchronized
    fun all(): List<Cookie> = cookies.values.toList()

    @Synchronized
    fun clear() {
        cookies.clear()
        persist()
    }

    private fun persist() {
        prefs.edit().putStringSet(KEY, cookies.values.map { cookie ->
            "https://${cookie.domain}${cookie.path}\n$cookie"
        }.toSet()).apply()
    }

    private fun key(cookie: Cookie) = "${cookie.name}|${cookie.domain}|${cookie.path}"

    companion object {
        private const val KEY = "cookies"
    }
}
