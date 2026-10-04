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
            (uri.rawPath.isNullOrEmpty() || uri.rawPath.matches(Regex("(/[A-Za-z0-9_-]+)*/?")))) {
            "Use an HTTPS server address without a username, key or query."
        }
        require(uri.port == -1 || uri.port in 1..65535) { "Invalid server port." }
        return URI("https", null, uri.host.lowercase(), uri.port, uri.path.orEmpty().trimEnd('/').takeIf(String::isNotBlank), null, null).toASCIIString()
    }

    fun trusted(serverAddress: String, url: String?): Boolean = runCatching {
        val root = URI(server(serverAddress))
        val target = URI(requireNotNull(url))
        fun port(uri: URI) = if (uri.port < 0) 443 else uri.port
        target.scheme.equals("https", ignoreCase = true) && target.rawUserInfo == null &&
            target.path.orEmpty().split('/').none { it == "." || it == ".." } &&
            target.host.equals(root.host, ignoreCase = true) && port(target) == port(root) &&
            (root.path.isNullOrEmpty() || target.path == root.path || target.path.startsWith(root.path + "/"))
    }.getOrDefault(false)

}
