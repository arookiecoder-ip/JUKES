package com.example.juke.network

import io.ktor.http.HttpMethod
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Tests audio access only; account/library authentication is a separate flow. */
data class DownloadCookieResult(val valid: Boolean, val message: String)

class DownloadCookies(
    private val request: suspend (HttpMethod, JsonObject?, Boolean) -> JsonElement =
        { method, body, force -> Backend.downloadCookieRequest(method, body, force) }
) {
    suspend fun check(): DownloadCookieResult = result(requestWithRetry(HttpMethod.Get, null, true).objectOrEmpty())

    suspend fun replace(export: String): DownloadCookieResult {
        require(export.isNotBlank()) { "Paste a Netscape-format cookies.txt export." }
        require(export.toByteArray(Charsets.UTF_8).size <= 128 * 1024) { "Cookie export must be smaller than 128 KB." }
        val normalized = normalizeExport(export)
        val response = requestWithRetry(HttpMethod.Post, JsonObject(mapOf("cookies" to JsonPrimitive(normalized))), false).objectOrEmpty()
        check(response.flag("success") && response.flag("valid")) { "The audio test failed. Existing cookies were kept." }
        return result(response)
    }

    // Canceling a background client request does not stop an already running server probe.
    private suspend fun requestWithRetry(method: HttpMethod, body: JsonObject?, force: Boolean): JsonElement {
        repeat(3) {
            try { return request(method, body, force) }
            catch (e: BackendHttpException) {
                if (e.statusCode != 429) throw e
                delay(2_000)
            }
        }
        return request(method, body, force)
    }

    private fun normalizeExport(export: String): String {
        val text = export.removePrefix("\uFEFF").replace("\r\n", "\n").replace("\r", "\n").trim()
        val rows = text.lineSequence().filter { it.isNotBlank() && (!it.startsWith("#") || it.startsWith("#HttpOnly_")) }.toList()
        require(rows.isNotEmpty() && rows.all { it.split('\t').size == 7 }) {
            "Use a Netscape cookies.txt export with seven tab-separated columns. JSON or browser request headers cannot be used. Choose the original file if copying changed its tabs or line breaks."
        }
        return if (text.startsWith("# Netscape HTTP Cookie File") || text.startsWith("# HTTP Cookie File")) text
            else "# Netscape HTTP Cookie File\n$text"
    }

    private fun result(response: JsonObject) = DownloadCookieResult(response.flag("valid"), response.text("message"))
}
