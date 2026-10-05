package com.example.juke.network

import io.ktor.http.HttpMethod
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Tests audio access only; account/library authentication is a separate flow. */
data class DownloadCookieResult(val valid: Boolean, val message: String)

class DownloadCookies(
    private val request: suspend (HttpMethod, JsonObject?, Boolean) -> JsonElement =
        { method, body, force -> Backend.downloadCookieRequest(method, body, force) }
) {
    suspend fun check(): DownloadCookieResult = result(request(HttpMethod.Get, null, true).objectOrEmpty())

    suspend fun replace(export: String): DownloadCookieResult {
        require(export.isNotBlank()) { "Paste a Netscape-format cookies.txt export." }
        require(export.toByteArray(Charsets.UTF_8).size <= 128 * 1024) { "Cookie export must be smaller than 128 KB." }
        val response = request(HttpMethod.Post, JsonObject(mapOf("cookies" to JsonPrimitive(export))), false).objectOrEmpty()
        check(response.flag("success") && response.flag("valid")) { "The audio test failed. Existing cookies were kept." }
        return result(response)
    }

    private fun result(response: JsonObject) = DownloadCookieResult(response.flag("valid"), response.text("message"))
}
