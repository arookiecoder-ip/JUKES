package com.example.juke.utils

/** Redact credentials from URLs, headers and exception text before any log sink sees them. */
fun redactSecrets(message: String, apiKey: String = ""): String {
    var safe = message
    if (apiKey.isNotBlank()) {
        safe = safe.replace(apiKey, "[REDACTED]")
        safe = safe.replace(java.net.URLEncoder.encode(apiKey, "UTF-8"), "[REDACTED]")
    }
    safe = safe.replace(Regex("(?i)([?&](?:key|api_key|token)=)[^&\\s\"<>]*"), "$1[REDACTED]")
    safe = safe.replace(Regex("(?im)((?:X-Api-Key|Authorization|Cookie|Set-Cookie)\\s*:\\s*)[^\\r\\n]+"), "$1[REDACTED]")
    return safe
}
