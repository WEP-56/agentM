package dev.agentm.app.config

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

class ProviderModelDiscovery(private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
    fun fetch(kind: String, source: String): JSONObject {
        require(kind in ProviderDocuments.kinds)
        val config = ProviderDocuments.parse(source)
        if (kind == "codex") {
            val projection = CodexDocuments.project(config, false)
            val provider = projection.table ?: error("官方模型列表由 Codex 原生登录提供；自定义提供商请填写请求地址与 API Key")
            require(provider.optString("wire_api", "responses") == "responses") { "Codex 原生仅支持 Responses" }
            require(listOf("env_key", "auth", "aws", "env_http_headers", "query_params").none { provider.has(it) }) { "此认证配置请手动添加模型；模型查询不会执行命令或读取环境变量" }
            return fetch("pi", JSONObject().put("api", "openai-responses").put("baseUrl", provider.optString("base_url"))
                .put("apiKey", CodexDocuments.key(config, projection.root)).put("headers", provider.optJSONObject("http_headers") ?: JSONObject()).toString())
        }
        val env = config.optJSONObject("env") ?: JSONObject()
        val connectionOptions = if (kind == "opencode") config.optJSONObject("options") ?: JSONObject() else config
        val base = (if (kind == "claude") env.optString(ClaudeSettings.BASE, "https://api.anthropic.com") else connectionOptions.optString(if (kind == "opencode") "baseURL" else "baseUrl")).trim().trimEnd('/')
        ProviderDocuments.url(base)
        val api = if (kind == "claude") "anthropic-messages" else if (kind == "opencode") when (config.optString("npm")) {
            "@ai-sdk/anthropic" -> "anthropic-messages"
            "@ai-sdk/google" -> "google-generative-ai"
            "@ai-sdk/amazon-bedrock" -> "bedrock-converse-stream"
            "@ai-sdk/openai", "@ai-sdk/openai-compatible" -> "openai-completions"
            else -> error("此 SDK 没有通用模型查询方式，请手动添加模型")
        } else config.optString("api")
        require(api != "bedrock-converse-stream") { "Amazon Bedrock 不提供通用 /models 接口，请手动添加模型" }
        val secret = if (kind == "claude") env.optString(ClaudeSettings.KEY).ifBlank { env.optString(ClaudeSettings.TOKEN) } else connectionOptions.optString("apiKey")
        require(secret.isNotBlank() && !secret.startsWith("!") && !secret.contains("{env:") && !secret.contains("{file:") && secret.none { it == '\r' || it == '\n' }) { "获取模型需要直接填写 API Key；不会执行密钥命令或读取变量引用" }
        val headers = linkedMapOf<String, String>()
        when (api) {
            "anthropic-messages" -> {
                headers["anthropic-version"] = "2023-06-01"
                if (kind == "claude" && env.optString(ClaudeSettings.TOKEN).isNotBlank()) headers["Authorization"] = "Bearer $secret" else headers["x-api-key"] = secret
            }
            "google-generative-ai" -> headers["x-goog-api-key"] = secret
            else -> headers["Authorization"] = "Bearer $secret"
        }
        if (kind != "claude") connectionOptions.optJSONObject("headers")?.let { extra -> extra.keys().forEach { headers[it] = extra.getString(it) } }
        else env.optString("ANTHROPIC_CUSTOM_HEADERS").lineSequence().filter { it.isNotBlank() }.forEach { line ->
            require(line.contains(':')) { "自定义请求头需为 Header: value" }
            headers[line.substringBefore(':').trim()] = line.substringAfter(':').trim()
        }
        for ((name, value) in headers) require(name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) && !value.contains("{env:") && !value.contains("{file:") && value.none { it == '\r' || it == '\n' } &&
            name.lowercase() !in setOf("host", "content-length", "transfer-encoding", "connection")) { "模型查询请求头无效" }
        val deadline = System.nanoTime() + 25_000_000_000L
        for (address in urls(base, api)) {
            check(System.nanoTime() < deadline) { "获取模型列表超时，请稍后重试" }
            val connection = connect(URL(address))
            connection.instanceFollowRedirects = false // Never forward credentials to a redirect target.
            connection.connectTimeout = 5000; connection.readTimeout = 8000
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            try {
                val code = connection.responseCode
                if (code in setOf(404, 405)) continue
                check(code == 200) { when (code) { 401, 403 -> "模型接口认证失败（HTTP $code），请检查密钥"; in 300..399 -> "模型接口返回重定向，请填写最终请求地址后重试"; else -> "模型接口返回 HTTP $code" } }
                val bytes = connection.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (true) { check(System.nanoTime() < deadline) { "获取模型列表超时" }; val count = input.read(buffer); if (count < 0) break; check(output.size() + count <= 2 * 1024 * 1024) { "模型列表过大" }; output.write(buffer, 0, count) }
                    output.toByteArray()
                }
                return JSONObject().put("models", parse(JSONObject(bytes.toString(Charsets.UTF_8)))).put("url", address)
            } finally { connection.disconnect() }
        }
        error("未找到 /models 接口；请检查请求地址，或手动填写模型")
    }
    companion object {
        fun urls(base: String, api: String): List<String> {
            ProviderDocuments.url(base)
            val path = URI(base).path.trimEnd('/')
            if (path.endsWith("/models")) return listOf(base)
            val version = if (api == "google-generative-ai") "v1beta" else "v1"
            val bases = linkedSetOf(base)
            if (path.endsWith("/anthropic")) bases.add(base.removeSuffix("/anthropic"))
            for (suffix in listOf("/messages", "/chat/completions", "/responses")) if (path.endsWith(suffix)) bases.add(base.removeSuffix(suffix))
            return bases.flatMap { candidate ->
                if (candidate.endsWith("/v1") || candidate.endsWith("/v1beta")) listOf("$candidate/models") else listOf("$candidate/models", "$candidate/$version/models")
            }.distinct()
        }
        fun parse(json: JSONObject): JSONArray {
            val array = json.optJSONArray("data") ?: json.optJSONArray("models") ?: error("模型接口返回了不支持的数据格式")
            val result = JSONArray(); val seen = mutableSetOf<String>()
            for (i in 0 until minOf(array.length(), 500)) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("id").ifBlank { item.optString("slug") }.ifBlank { item.optString("name").removePrefix("models/") }
                if (id.isBlank() || id.length > 256 || id.any { it.code < 32 } || !seen.add(id)) continue
                val model = JSONObject().put("id", id).put("name", item.optString("display_name").ifBlank { item.optString("displayName") }.ifBlank { id }.take(256))
                result.put(model)
            }
            check(result.length() > 0) { "模型接口未返回可用模型，可手动填写" }
            return result
        }
    }
}
