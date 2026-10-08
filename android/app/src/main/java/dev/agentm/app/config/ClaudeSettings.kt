package dev.agentm.app.config

import java.net.URI

/** These four env fields are the entire ownership boundary of the first adapter. */
object ClaudeSettings {
    const val BASE = "ANTHROPIC_BASE_URL"
    const val MODEL = "ANTHROPIC_MODEL"
    const val KEY = "ANTHROPIC_API_KEY"
    const val TOKEN = "ANTHROPIC_AUTH_TOKEN"
    val fields = listOf(BASE, MODEL, KEY, TOKEN)
    data class Input(val baseUrl: String, val model: String, val authMode: String, val secretAction: String, val secret: String)
    fun values(source: String): Map<String, String> {
        val document = JsonDocument(source)
        require(document.root.kind == '{') { "Claude 配置根节点必须是对象" }
        val env = document.root.member("env")?.value ?: return emptyMap()
        require(env.kind == '{') { "env 必须是对象" }
        return fields.mapNotNull { key -> env.member(key)?.let {
            require(it.value.kind == '"') { "受管 env 字段必须是字符串" }
            key to it.value.text!!
        } }.toMap()
    }
    fun desired(source: String, input: Input): Map<String, String> {
        val current = values(source)
        require(input.authMode in setOf("native", "apiKey", "authToken")) { "请选择认证方式" }
        require(input.secretAction in setOf("keep", "replace")) { "无效的密钥操作" }
        val base = input.baseUrl.trim()
        val model = input.model.trim()
        require(model.length <= 256 && model.none { it.code < 32 }) { "模型名称过长或包含控制字符" }
        if (base.isNotEmpty()) {
            val uri = runCatching { URI(base) }.getOrNull()
            require(base.length <= 2048 && uri != null && uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() &&
                uri.userInfo == null && uri.query == null && uri.fragment == null) { "端点需要完整 HTTP(S) URL，不能包含凭据、查询参数或片段" }
        }
        val next = mutableMapOf<String, String>()
        if (model.isNotEmpty()) next[MODEL] = model
        if (input.authMode != "native") {
            if (base.isNotEmpty()) next[BASE] = base
            val key = if (input.authMode == "apiKey") KEY else TOKEN
            val secret = if (input.secretAction == "replace") input.secret else current[key].orEmpty()
            require(secret.isNotBlank() && secret.length <= 8192 && secret.none { it.isWhitespace() || it.code < 32 }) { "请输入该认证方式的密钥；留空仅可保留已有同类密钥" }
            next[key] = secret
        }
        return next
    }
    fun patch(source: String, desired: Map<String, String>): String {
        values(source)
        require(desired.keys.all { it in fields })
        var result = source
        if (desired.isNotEmpty() && JsonDocument(result).root.member("env") == null) {
            val document = JsonDocument(result)
            result = document.editObject(document.root, "env", "{}")
        }
        for (key in fields) {
            val document = JsonDocument(result)
            val env = document.root.member("env")?.value ?: continue
            // Preserve escaped strings exactly when the semantic value did not change.
            if (env.member(key)?.value?.text == desired[key]) continue
            result = document.editObject(env, key, desired[key]?.let(JsonDocument::quote))
        }
        values(result)
        return result
    }
}
