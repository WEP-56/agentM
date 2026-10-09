package dev.agentm.app.web

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/** Identity + authentication checks, never following redirects with a credential. */
object WebHealth {
    data class Reply(val code: Int, val body: String, val headers: Map<String?, List<String>>) {
        fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
    }
    fun get(port: Int, path: String, authorization: String? = null, cookie: String? = null): Reply {
        require(port in 1..65535 && path.startsWith('/') && !path.startsWith("//"))
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 2000; connection.readTimeout = 2000
        connection.setRequestProperty("Cache-Control", "no-store")
        if (authorization != null) connection.setRequestProperty("Authorization", authorization)
        if (cookie != null) connection.setRequestProperty("Cookie", cookie)
        return try {
            val code = connection.responseCode
            val body = (if (code < 400) connection.inputStream else connection.errorStream)?.bufferedReader()?.use {
                val buffer = CharArray(65536); val count = it.read(buffer); if (count < 0) "" else String(buffer, 0, count)
            } ?: ""
            Reply(code, body, connection.headerFields)
        } finally { connection.disconnect() }
    }
    fun basic(password: String): String = "Basic " + Base64.getEncoder().encodeToString("opencode:$password".toByteArray())
    fun openCode(port: Int, password: String, expectedVersion: String): Boolean {
        if (get(port, "/global/health").code != 401) return false
        val result = get(port, "/global/health", basic(password))
        if (result.code != 200) return false
        val json = JSONObject(result.body)
        return json.optBoolean("healthy") && json.optString("version") == expectedVersion
    }
    fun dsh(port: Int, token: String): String? {
        require(token.matches(Regex("[A-Za-z0-9_-]{43}")))
        if (get(port, "/").code != 401) return null
        val result = get(port, "/?token=$token")
        if (result.code != 303 || result.header("Location") !in listOf("/", "./")) return null
        val cookie = result.headers.entries.filter { it.key.equals("Set-Cookie", true) }.flatMap { it.value }.firstNotNullOfOrNull(WebPolicy::cookie) ?: return null
        val page = get(port, "/", cookie = cookie)
        return cookie.takeIf { page.code == 200 && page.body.contains("<html", true) }
    }
}
