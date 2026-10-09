package dev.agentm.app.web

import java.net.URI

/** Closed origins and startup grammars. Credentials never appear in a workbench snapshot. */
object WebPolicy {
    val kinds = setOf("opencode", "dsh")
    /** User-entered navigation is stricter than the HTTP/WebSocket resource policy. */
    fun navigationUrl(input: String, port: Int): String? = runCatching {
        val text = input.trim()
        val uri = when {
            text.startsWith('/') && !text.startsWith("//") -> URI("http://127.0.0.1:$port/").resolve(text)
            text.startsWith("127.0.0.1:") -> URI("http://$text")
            else -> URI(text)
        }
        uri.toASCIIString().takeIf { uri.scheme == "http" && sameOrigin(it, port) }
    }.getOrNull()
    /** Toolbar/history handoff never displays bootstrap tokens or URL credentials. */
    fun displayUrl(url: String, port: Int): String = runCatching {
        val uri = URI(url)
        if (uri.scheme != "http" || !sameOrigin(url, port)) return@runCatching "http://127.0.0.1:$port/"
        "http://127.0.0.1:$port${uri.rawPath.orEmpty().ifEmpty { "/" }}"
    }.getOrDefault("http://127.0.0.1:$port/")
    fun desktopUserAgent(mobile: String): String = mobile
        .replace(Regex("\\([^)]*Android[^)]*\\)"), "(X11; Linux x86_64)")
        .replace(" Version/4.0", "").replace(" Mobile", "")
    fun sameOrigin(url: String, port: Int): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme in setOf("http", "ws") && uri.host == "127.0.0.1" && uri.port == port && uri.rawUserInfo == null
    }.getOrDefault(false)
    fun dshStartup(text: String): Pair<Int, String>? {
        val matches = Regex("(?m)^dsh web: http://127\\.0\\.0\\.1:([0-9]{1,5})/\\?token=([A-Za-z0-9_-]{43})[ \\t]*$").findAll(text).toList()
        if (matches.isEmpty()) return null
        val values = matches.map { it.groupValues[1].toInt() to it.groupValues[2] }.distinct()
        return values.singleOrNull()?.takeIf { it.first in 1..65535 }
    }
    fun openCodePort(text: String): Int? = Regex("http://127\\.0\\.0\\.1:([0-9]{1,5})(?:[/ \\r\\n]|$)")
        .findAll(text).map { it.groupValues[1].toInt() }.filter { it in 1..65535 }.distinct().toList().singleOrNull()
    fun redact(text: String): String = text
        .replace(Regex("(?i)(token=)[^\\s&#]+"), "$1[redacted]")
        .replace(Regex("(?i)((?:authorization|cookie|password|api[_-]?key|secret)\\s*[:=]\\s*)[^\\r\\n]+"), "$1[redacted]")
        .takeLast(3000)
    fun cookie(header: String): String? = header.substringBefore(';').trim().takeIf {
        it.matches(Regex("dsh-auth-[A-Za-z0-9_-]{43}=v1\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]{43}"))
    }
}
