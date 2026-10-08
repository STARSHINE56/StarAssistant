package com.yunx.app.util

import java.net.URI

/** Redacts capability-bearing URL paths, queries, fragments and user info. */
object LogRedactor {
    private val absoluteUrl = Regex("""https?://[^\s\"'<>]+""", RegexOption.IGNORE_CASE)
    private val secretAssignment = Regex(
        """(?i)\b(cookie|authorization|access[_-]?token|refresh[_-]?token|captcha[_-]?token|app[_-]?token|token|password|passwd|pwd|device[_-]?sign|bduss|stoken|__puus|__pus|rmkey|signature|sign|secret[_-]?key)\b(\s*[=:]\s*)([^\s,;]+)"""
    )

    fun url(value: Any?): String {
        val raw = value?.toString()?.takeIf { it.isNotBlank() } ?: return "<none>"
        return runCatching {
            val uri = URI(raw)
            val scheme = uri.scheme?.lowercase() ?: return@runCatching "<relative-url>"
            val host = uri.host?.lowercase() ?: return@runCatching "<$scheme-url>"
            val defaultPort = (scheme == "https" && uri.port == 443) || (scheme == "http" && uri.port == 80)
            val port = if (uri.port >= 0 && !defaultPort) ":${uri.port}" else ""
            "$scheme://$host$port"
        }.getOrDefault("<invalid-url>")
    }

    private val jsonSecret = Regex("""(?i)(["'](?:cookie|authorization|access[_-]?token|refresh[_-]?token|captcha[_-]?token|app[_-]?token|token|password|passwd|pwd|device[_-]?sign|bduss|stoken|__puus|__pus|rmkey|signature|sign|secret[_-]?key)["']\s*:\s*)"(?:\\.|[^"\\])*"""")
    private val authHeader = Regex("""(?i)\b(cookie|authorization)(\s*[=:]\s*)(.*?)(?=\s+\w*token\s*=|\s+password\s*=|[|\r\n]|$)""")
    private val quotedSecretAssignment = Regex("""(?i)\b(cookie|authorization|access[_-]?token|refresh[_-]?token|captcha[_-]?token|app[_-]?token|token|password|passwd|pwd|device[_-]?sign|bduss|stoken|__puus|__pus|rmkey|signature|sign|secret[_-]?key)\b(\s*[=:]\s*)("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')""")
    fun line(value: String): String {
        val quoted = quotedSecretAssignment.replace(value) { it.groupValues[1] + it.groupValues[2] + "<redacted>" }
        val json = jsonSecret.replace(quoted) { it.groupValues[1] + "\"<redacted>\"" }
        val headers = authHeader.replace(json) { it.groupValues[1] + it.groupValues[2] + "<redacted>" }
        val withoutUrls = absoluteUrl.replace(headers) { match -> url(match.value) }
        return secretAssignment.replace(withoutUrls) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}<redacted>"
        }
    }
}
