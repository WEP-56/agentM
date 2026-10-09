package dev.agentm.app.config

import org.json.JSONObject
import java.net.URI

/** Native file formats, without protocol translation or a local proxy. */
object ProviderDocuments {
    val kinds = setOf("claude", "pi", "opencode", "codex")
    val piApis = setOf("openai-completions", "openai-responses", "anthropic-messages", "google-generative-ai", "bedrock-converse-stream")
    val claudeKeys = ClaudeSettings.fields + listOf("ANTHROPIC_CUSTOM_HEADERS", "CLAUDE_CODE_SUBAGENT_MODEL") +
        listOf("SONNET", "OPUS", "FABLE", "HAIKU").flatMap { listOf("ANTHROPIC_DEFAULT_${it}_MODEL", "ANTHROPIC_DEFAULT_${it}_MODEL_NAME") }
    const val MAX_SOURCE = 48 * 1024
    fun parse(source: String): JSONObject {
        require(source.toByteArray().size <= 1024 * 1024) { "配置文件超过 1 MiB" }
        require(JsonDocument(source).root.kind == '{') { "配置必须是 JSON 对象" }
        return JSONObject(source.removePrefix("\uFEFF"))
    }
    fun url(value: String) {
        val uri = runCatching { URI(value) }.getOrNull()
        require(value.length <= 2048 && uri != null && uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.query == null && uri.fragment == null) { "请求地址必须是完整 HTTP(S) URL，不含凭据、查询参数或片段" }
    }
    fun validate(kind: String, source: String, providerKey: String = "", official: Boolean = false): JSONObject {
        require(kind in kinds)
        require(source.toByteArray().size <= MAX_SOURCE) { "单个提供商配置不能超过 48 KiB" }
        val json = parse(source)
        if (kind in setOf("pi", "opencode")) require(providerKey.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}"))) { "供应商标识需为 1–80 位字母、数字、点、下划线或连字符" }
        if (kind == "codex") {
            CodexDocuments.validate(json, official)
        } else if (kind == "opencode") {
            OpenCodeDocuments.validate(json)
        } else if (kind == "claude") {
            require(!json.has("env") || json.get("env") is JSONObject) { "env 必须是对象" }
            val env = json.optJSONObject("env") ?: JSONObject()
            for (key in claudeKeys) if (env.has(key)) require(env.get(key) is String) { "$key 必须是字符串" }
            val key = env.optString(ClaudeSettings.KEY); val token = env.optString(ClaudeSettings.TOKEN)
            require(key.isBlank() || token.isBlank()) { "API Key 与 Auth Token 只能设置一种" }
            val base = env.optString(ClaudeSettings.BASE)
            if (base.isNotBlank()) url(base)
            require(!official || (base.isBlank() && key.isBlank() && token.isBlank())) { "Claude Official 使用原生登录；自定义连接请新增提供商" }
            for (secret in listOf(key, token)) require(secret.length <= 8192 && secret.none { it.isWhitespace() || it.code < 32 }) { "密钥包含空白字符或过长" }
        } else {
            require(providerKey.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}"))) { "供应商标识需为 1–80 位字母、数字、点、下划线或连字符" }
            require(json.optString("api") in piApis) { "请选择 Pi 接口格式" }
            url(json.optString("baseUrl"))
            require(!json.has("apiKey") || json.get("apiKey") is String) { "apiKey 必须是字符串" }
            for (field in listOf("headers", "compat")) require(!json.has(field) || json.get(field) is JSONObject) { "$field 必须是对象" }
            json.optJSONObject("headers")?.let { headers -> headers.keys().forEach { name ->
                require(name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) && headers.get(name) is String && headers.getString(name).none { it == '\r' || it == '\n' }) { "请求头名称或值无效" }
            } }
            val models = json.optJSONArray("models") ?: error("请配置至少一个模型")
            require(models.length() in 1..200) { "模型数量需为 1–200" }
            val ids = mutableSetOf<String>()
            for (i in 0 until models.length()) {
                val model = models.getJSONObject(i)
                val id = model.optString("id")
                require(id.isNotBlank() && id.length <= 256 && id.none { it.code < 32 } && ids.add(id)) { "模型 ID 为空、重复或无效" }
                require(model.optString("name").isNotBlank()) { "请填写模型显示名称" }
                require(!model.has("reasoning") || model.get("reasoning") is Boolean) { "reasoning 必须是布尔值" }
                val input = model.optJSONArray("input")
                require(input != null && input.length() > 0 && (0 until input.length()).all { input.getString(it) in setOf("text", "image") }) { "模型输入仅支持 text/image" }
                for (key in listOf("contextWindow", "maxTokens")) {
                    val value = model.opt(key)
                    require(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toLong().toDouble() && value.toLong() in 1..100000000) { "$key 必须是正整数" }
                }
            }
        }
        return json
    }
    fun edit(source: String, key: String, value: Any?): String {
        val doc = JsonDocument(source)
        return doc.editObject(doc.root, key, value?.let { if (it is String) JsonDocument.quote(it) else it.toString() })
    }
    fun claude(current: String, desired: JSONObject, previous: JSONObject = JSONObject(), removeRootFields: Boolean = true): String {
        val original = parse(current)
        require(!original.has("env") || original.get("env") is JSONObject) { "当前 env 不是对象" }
        var result = current
        if (!original.has("env")) result = edit(result, "env", JSONObject())
        val env = desired.optJSONObject("env") ?: JSONObject()
        val oldEnv = previous.optJSONObject("env") ?: JSONObject()
        for (key in oldEnv.keys()) if (!env.has(key) && key !in claudeKeys && equal(original.optJSONObject("env")?.opt(key), oldEnv.get(key))) {
            val doc = JsonDocument(result)
            result = doc.editObject(doc.root.member("env")!!.value, key, null)
        }
        // Replace provider connection/model fields, preserving unrelated settings and original spans.
        for (key in (claudeKeys + env.keys().asSequence().toList()).distinct()) {
            val doc = JsonDocument(result)
            result = doc.editObject(doc.root.member("env")!!.value, key, if (env.has(key)) env.get(key).let { if (it is String) JsonDocument.quote(it) else it.toString() } else null)
        }
        for (key in desired.keys()) if (key != "env") result = edit(result, key, desired.get(key))
        for (key in previous.keys()) if (key != "env" && (removeRootFields || key in setOf("model", "apiKeyHelper")) && !desired.has(key) && equal(original.opt(key), previous.get(key))) result = edit(result, key, null)
        return result
    }
    fun piModels(current: String, key: String, provider: JSONObject): String {
        val root = parse(current)
        require(!root.has("providers") || root.get("providers") is JSONObject) { "providers 必须是对象" }
        var result = if (root.has("providers")) current else edit(current, "providers", JSONObject())
        val doc = JsonDocument(result)
        result = doc.editObject(doc.root.member("providers")!!.value, key, provider.toString(2))
        return result
    }
    fun piDefaults(current: String, key: String, model: String): String = edit(edit(current, "defaultProvider", key), "defaultModel", model)
    fun removePiProvider(current: String, key: String): String {
        parse(current)
        val doc = JsonDocument(current)
        val providers = doc.root.member("providers")?.value ?: return current
        require(providers.kind == '{') { "providers 必须是对象" }
        return doc.editObject(providers, key, null)
    }
    fun equal(a: Any?, b: Any?): Boolean {
        if (a is JSONObject && b is JSONObject) return a.keys().asSequence().toSet() == b.keys().asSequence().toSet() && a.keys().asSequence().all { equal(a.get(it), b.get(it)) }
        if (a is org.json.JSONArray && b is org.json.JSONArray) return a.length() == b.length() && (0 until a.length()).all { equal(a.get(it), b.get(it)) }
        if (a is Number && b is Number) return a.toDouble() == b.toDouble()
        return a == b
    }
}
