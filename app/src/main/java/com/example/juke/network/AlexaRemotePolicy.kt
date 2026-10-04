package com.example.juke.network

import java.net.URI

/** Native remote configuration and trusted account sign-in origins. */
object AlexaRemotePolicy {
    const val DEFAULT_SERVER = "https://alexa.synthora.in"

    fun server(value: String): String {
        val uri = URI(value.trim())
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
            "Enter the HTTPS address you use in your browser."
        }
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")) {
            "Use the server address without a path, username, key or query."
        }
        require(uri.port == -1 || uri.port in 1..65535) { "Invalid server port." }
        return URI("https", null, uri.host.lowercase(), uri.port, null, null, null).toASCIIString()
    }

    fun trusted(serverAddress: String, url: String?): Boolean = runCatching {
        val root = URI(server(serverAddress))
        val target = URI(requireNotNull(url))
        fun port(uri: URI) = if (uri.port < 0) 443 else uri.port
        target.scheme.equals("https", ignoreCase = true) && target.rawUserInfo == null &&
            target.host.equals(root.host, ignoreCase = true) && port(target) == port(root)
    }.getOrDefault(false)

    fun startUrl(serverAddress: String): String = server(serverAddress) + "/remote/"
}
