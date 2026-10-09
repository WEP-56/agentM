package dev.agentm.app.config

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** CC Switch's native/direct projection: {auth, config, modelCatalog}; no protocol conversion. */
class CodexDocuments(private val template: JSONObject, private val officialModels: JSONArray, private val deepseekModels: JSONArray = JSONArray()) {
    fun catalog(source: JSONObject): JSONObject? {
        val specs = source.optJSONObject("modelCatalog")?.optJSONArray("models") ?: return null
        if (specs.length() == 0) return null
        val projection = project(source, false)
        val host = runCatching { URI(projection.table?.optString("base_url").orEmpty()).host.orEmpty().lowercase() }.getOrDefault("")
        val vendor = (host == "deepseek.com" || host.endsWith(".deepseek.com")) && deepseekModels.length() > 0
        val rows = JSONArray()
        fun candidates(array: JSONArray) = (0 until array.length()).map { array.getJSONObject(it) }
        for (i in 0 until specs.length()) {
            val spec = specs.getJSONObject(i); val model = spec.getString("model").trim()
            fun find(id: String) = candidates(officialModels).filter { it.optString("slug").isNotEmpty() && id.startsWith(it.getString("slug")) }.maxByOrNull { it.getString("slug").length }
            val official = find(model) ?: model.split('/').takeIf { it.size == 2 && it[0].matches(Regex("[A-Za-z0-9_-]+")) }?.let { find(it[1]) }
            val vendorMatch = if (vendor) candidates(deepseekModels).firstOrNull { it.optString("slug").equals(model, true) } else null
            val base = if (vendor) vendorMatch ?: deepseekModels.getJSONObject(0) else official ?: template
            val row = JSONObject(base.toString())
            if (!vendor && official != null) {
                if (row.optString("slug") != model) row.put("display_name", model)
                row.put("slug", model).put("priority", 1000 + i).put("visibility", "list").put("use_responses_lite", false)
            } else {
                row.put("slug", model)
                if (!vendor || vendorMatch == null) row.put("display_name", model).put("description", model).put("priority", 1000 + i)
                if (!vendor) {
                    for (key in listOf("apply_patch_tool_type", "web_search_tool_type", "tools", "model_messages")) row.remove(key)
                    row.put("shell_type", "shell_command")
                    row.put("context_window", projection.root.optLong("model_context_window", 128000))
                    row.put("max_context_window", row.get("context_window"))
                }
                if (spec.optString("displayName").isNotBlank()) row.put("display_name", spec.getString("displayName"))
                window(spec)?.let { row.put("context_window", it).put("max_context_window", it) }
                if (spec.has("supportsParallelToolCalls")) row.put("supports_parallel_tool_calls", spec.getBoolean("supportsParallelToolCalls"))
                if ((spec.optJSONArray("inputModalities")?.length() ?: 0) > 0) row.put("input_modalities", spec.getJSONArray("inputModalities"))
                else if (!vendor || vendorMatch == null) row.put("input_modalities", JSONArray(if (defaultImageInput(model)) listOf("text", "image") else listOf("text")))
                if (spec.optString("baseInstructions").isNotBlank()) row.put("base_instructions", spec.getString("baseInstructions"))
                spec.optJSONArray("reasoningLevels")?.takeIf { it.length() > 0 }?.let { declared ->
                    val levels = efforts.filter { effort -> (0 until declared.length()).any { declared.getString(it) == effort } }
                    row.put("supported_reasoning_levels", JSONArray(levels.map { JSONObject().put("effort", it).put("description", it) }))
                    row.put("default_reasoning_level", spec.optString("defaultReasoningLevel").takeIf { it in levels } ?: row.optString("default_reasoning_level").takeIf { it in levels } ?: levels.last())
                }
            }
            if (!vendor) row.put("service_tiers", JSONArray()).put("additional_speed_tiers", JSONArray()).put("availability_nux", JSONObject.NULL).put("upgrade", JSONObject.NULL)
            if (!row.has("base_instructions")) row.put("base_instructions", row.optJSONObject("model_messages")?.optString("instructions_template") ?: template.getString("base_instructions"))
            rows.put(row)
        }
        return JSONObject().put("models", rows)
    }
    fun apply(before: Map<String, ByteArray?>, desired: JSONObject, official: Boolean, previous: JSONObject = JSONObject()): Map<String, ByteArray?> {
        validate(desired, official)
        val projection = project(desired, official)
        var text = text(before["codexConfig"])
        val original = TomlDocument(text).root
        val prior = runCatching { project(previous, false).root }.getOrDefault(JSONObject())
        fun set(path: List<String>, value: Any?) { text = TomlDocument(text).set(path, value) }
        for (key in floor) set(listOf(key), if (key in top) projection.root.opt(key) else null)
        for (path in nested) set(path, at(projection.root, path))
        for (key in exclusive) {
            if (projection.root.has(key)) set(listOf(key), projection.root.get(key))
            else if (prior.has(key) && ProviderDocuments.equal(original.opt(key), prior.get(key))) set(listOf(key), null)
        }
        // Keep unrelated provider declarations. Reserved legacy tables must move to legal names.
        val providers = TomlDocument(text).root.optJSONObject("model_providers")
        for (id in reserved) if (providers?.has(id) == true) {
            val old = providers.get(id); var n = 1; var renamed = "agentm-legacy-$n"
            while (TomlDocument(text).root.optJSONObject("model_providers")?.has(renamed) == true) { n++; renamed = "agentm-legacy-$n" }
            set(listOf("model_providers", id), null); set(listOf("model_providers", renamed), old)
        }
        if (official || projection.selector == null) {
            // No dormant proxy route: official always uses the native openai provider.
            val old = TomlDocument(text).root.optJSONObject("model_providers")?.optJSONObject("custom")
            if (old != null) set(listOf("model_providers", "custom", "experimental_bearer_token"), null)
        } else if (projection.table != null) {
            val table = JSONObject(projection.table.toString())
            val auth = before["codexAuth"]?.let { ProviderDocuments.parse(CodexDocuments.text(it)) } ?: JSONObject()
            val store = original.optString("cli_auth_credentials_store", "file")
            val login = if (store == "ephemeral") false else if (store == "file") hasLogin(auth) else true
            table.put("requires_openai_auth", projection.ownCredential && login)
            set(listOf("model_providers", projection.selector), table)
        }
        if (projection.selector != null) set(listOf("model_provider"), projection.selector)
        val changes = linkedMapOf<String, ByteArray?>()
        val foreign = projection.root.optString("model_catalog_json").takeIf { it.isNotBlank() && it.substringAfterLast('/') != CATALOG }
        if (foreign != null) set(listOf("model_catalog_json"), foreign)
        else if (!official) catalog(desired)?.let { generated ->
            set(listOf("model_catalog_json"), "/root/.codex/$CATALOG")
            changes["codexCatalog"] = generated.toString(2).toByteArray()
            if (rejectWebSearch(projection, desired)) set(listOf("web_search"), "disabled")
        }
        // A foreign catalog file is never opened/rewritten. Detaching our pointer is sufficient on official.
        checkProfile(TomlDocument(text).root, projection.selector)
        changes["codexConfig"] = text.toByteArray()
        return changes
    }
    fun appliedSource(source: JSONObject): JSONObject = JSONObject(source.toString()).also { result ->
        val p = project(source, false)
        if (rejectWebSearch(p, source) && source.optJSONObject("modelCatalog")?.optJSONArray("models")?.length()?.let { it > 0 } == true)
            result.put("config", TomlDocument(source.getString("config")).set("web_search", "disabled"))
    }
    companion object {
        const val OFFICIAL = "codex-official"
        const val CATALOG = "agentm-model-catalog.json"
        val fileKeys = listOf("codexConfig", "codexAuth", "codexCatalog")
        val efforts = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")
        // Exact CC Switch registry, not prefixes: a new vision suffix remains image-capable.
        private val textOnly = setOf("ark-code-latest", "deepseek-chat", "deepseek-reasoner", "glm-5.1", "glm-5.2", "glm-5.3", "kat-coder", "kat-coder-pro", "kat-coder-pro v1", "kat-coder-pro v2", "kat-coder-pro-v1", "kat-coder-pro-v2", "ling-2.5-1t", "ling-2.6-1t", "longcat-2.0", "longcat-flash-chat", "minimax-m2.7", "minimax-m2.7-highspeed", "mimo-v2.5-pro", "qwen3-coder-480b", "qwen3-coder-480b-a35b-instruct", "qwen3-coder-flash", "qwen3-coder-next", "qwen3-coder-plus", "step-3.5-flash", "step-3.5-flash-2603", "us.deepseek.r1-v1")
        fun defaultImageInput(model: String) = model.trim().lowercase().removeSuffix("[1m]").trim().substringAfterLast('/') !in textOnly
        val top = listOf("model", "review_model", "model_reasoning_effort", "plan_mode_reasoning_effort", "disable_response_storage")
        val floor = top + listOf("model_provider", "openai_base_url", "model_catalog_json", "experimental_bearer_token", "base_url", "wire_api")
        val exclusive = listOf("web_search", "model_context_window", "model_auto_compact_token_limit", "model_supports_reasoning_summaries", "model_verbosity")
        val nested = listOf(listOf("agents", "default_subagent_model"), listOf("agents", "default_subagent_reasoning_effort"), listOf("memories", "extract_model"), listOf("memories", "consolidation_model"))
        val reserved = listOf("openai", "ollama", "lmstudio")
        private val builtins = reserved + listOf("amazon-bedrock", "amazon-bedrock-runtime")
        private fun window(spec: JSONObject): Long? = when (val value = spec.opt("contextWindow")) { is Number -> value.toLong(); is String -> value.trim().toLongOrNull(); else -> null }
        data class Projection(val root: JSONObject, val selector: String?, val table: JSONObject?, val ownCredential: Boolean)
        fun text(bytes: ByteArray?): String = bytes?.let { Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(it)).toString() } ?: ""
        private fun at(root: JSONObject, path: List<String>): Any? { var value: Any? = root; for (key in path) value = (value as? JSONObject)?.opt(key); return value }
        fun blank(official: Boolean = false): JSONObject = JSONObject().put("auth", JSONObject()).put("config", if (official) "" else "model_provider = \"custom\"\n\n[model_providers.custom]\nname = \"Custom\"\nbase_url = \"\"\nwire_api = \"responses\"\nrequires_openai_auth = false\n").put("modelCatalog", JSONObject().put("models", JSONArray()))
        fun key(source: JSONObject, root: JSONObject): String {
            val table = root.optJSONObject("model_providers")?.optJSONObject(root.optString("model_provider"))
            return (source.optJSONObject("auth")?.opt("OPENAI_API_KEY") as? String).orEmpty().trim().ifBlank { (table?.opt("experimental_bearer_token") as? String).orEmpty().trim() }.ifBlank { (root.opt("experimental_bearer_token") as? String).orEmpty().trim() }
        }
        fun project(source: JSONObject, official: Boolean): Projection {
            val root = TomlDocument(source.optString("config")).root
            if (official) return Projection(root, null, null, false)
            val selected = root.optString("model_provider").trim()
            val providers = root.optJSONObject("model_providers")
            var table = providers?.optJSONObject(selected)?.let { JSONObject(it.toString()) }
            if (selected in builtins && selected != "openai" && table == null) return Projection(root, selected, null, false)
            val redirected = table == null && (selected.isEmpty() || selected == "openai") && root.optString("openai_base_url").isNotBlank()
            if (redirected) table = JSONObject().put("base_url", root.getString("openai_base_url"))
            val token = key(source, root)
            if (table == null) {
                require(selected.isEmpty() && token.isEmpty()) { "Codex 自定义配置缺少所选 model_providers 表；API Key 不能放在顶层" }
                return Projection(root, null, null, false)
            }
            if (table.optString("name").isBlank()) table.put("name", selected.ifBlank { "Custom" })
            val env = table.optString("env_key").isNotBlank()
            val other = table.has("auth") || table.has("aws") || (!table.optBoolean("requires_openai_auth") && listOf("http_headers", "env_http_headers").any { h -> table.optJSONObject(h)?.keys()?.asSequence()?.any { it.equals("Authorization", true) } == true })
            if (!env && !other && token.isNotBlank()) table.put("experimental_bearer_token", token)
            val own = env || (!other && token.isNotBlank())
            require(own || (!table.optBoolean("requires_openai_auth") && !redirected)) { "缺少独立凭据，不能将官方登录回退发送到第三方地址；请填写 API Key 或关闭 requires_openai_auth" }
            if (!own) table.put("requires_openai_auth", false)
            table.put("wire_api", table.optString("wire_api", "responses"))
            return Projection(root, if (selected in listOf("amazon-bedrock", "amazon-bedrock-runtime")) selected else "custom", table, own)
        }
        fun validate(source: JSONObject, official: Boolean) {
            require(source.opt("auth") is JSONObject && source.opt("config") is String) { "Codex 配置需要 auth 对象与 config TOML 字符串" }
            val auth = source.getJSONObject("auth")
            require(auth.keys().asSequence().all { it == "OPENAI_API_KEY" }) { "提供商源码只保存 OPENAI_API_KEY；官方登录信息由 Codex 原生管理" }
            require(!auth.has("OPENAI_API_KEY") || auth.get("OPENAI_API_KEY") is String || auth.isNull("OPENAI_API_KEY")) { "OPENAI_API_KEY 必须为字符串" }
            val projection = project(source, official); val root = projection.root
            val token = key(source, root)
            require(token.length <= 8192 && token.none { it.isWhitespace() || it.code < 32 }) { "API Key 包含空白字符或过长" }
            if (official) require(token.isEmpty() && root.optString("model_provider") in listOf("", "openai") && root.optString("openai_base_url").isBlank() && !root.has("model_providers")) { "OpenAI Official 使用 Codex 原生登录和官方地址；自定义连接请新增提供商" }
            for (field in top + listOf("model_verbosity", "model_catalog_json", "web_search")) if (root.has(field) && field != "disable_response_storage") require(root.get(field) is String) { "$field 必须为字符串" }
            for (field in listOf("model_context_window", "model_auto_compact_token_limit")) if (root.has(field)) positive(root.get(field), field)
            for (field in listOf("model_reasoning_effort", "plan_mode_reasoning_effort")) if (root.has(field)) require(root.getString(field) in efforts) { "推理档位无效" }
            for (field in listOf("disable_response_storage", "model_supports_reasoning_summaries")) if (root.has(field)) require(root.get(field) is Boolean) { "$field 必须为布尔值" }
            projection.table?.let { table ->
                if (projection.selector == "custom") ProviderDocuments.url(table.optString("base_url"))
                require(table.optString("wire_api", "responses") == "responses") { "Codex 原生仅支持 Responses；本项目不提供协议转换" }
                for (field in listOf("http_headers", "env_http_headers", "query_params")) if (table.has(field)) {
                    val map = table.optJSONObject(field) ?: error("$field 必须为表")
                    for (name in map.keys()) require(name.isNotBlank() && map.get(name) is String && map.getString(name).none { it == '\r' || it == '\n' } && (field == "query_params" || name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")))) { "$field 名称或值无效" }
                }
                for (field in listOf("requires_openai_auth", "supports_websockets", "supports_standalone_web_search")) if (table.has(field)) require(table.get(field) is Boolean) { "$field 必须为布尔值" }
                for (field in listOf("name", "env_key", "experimental_bearer_token", "env_key_instructions", "model_catalog_url")) if (table.has(field)) require(table.get(field) is String) { "$field 必须为字符串" }
                if (table.has("env_key")) require(table.getString("env_key").matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "env_key 必须为环境变量名" }
                require(!(table.has("auth") && (table.has("env_key") || table.has("experimental_bearer_token") || table.optBoolean("requires_openai_auth")))) { "命令认证不能与其他认证字段混用" }
                for (field in listOf("request_max_retries", "stream_max_retries", "stream_idle_timeout_ms", "websocket_connect_timeout_ms")) if (table.has(field)) positive(table.get(field), field, allowZero = true)
            }
            val catalog = source.optJSONObject("modelCatalog")
            require(!source.has("modelCatalog") || catalog != null) { "modelCatalog 必须为对象" }
            val models = catalog?.optJSONArray("models")
            require(catalog == null || models != null) { "modelCatalog.models 必须为数组" }
            val seen = mutableSetOf<String>()
            if (models != null) {
                require(models.length() <= 200) { "模型数量不能超过 200" }
                for (i in 0 until models.length()) {
                    val m = models.getJSONObject(i); val id = m.optString("model").trim()
                    require(id.isNotBlank() && id.length <= 256 && id.none { it.code < 32 } && seen.add(id)) { "模型 ID 为空、重复或无效" }
                    for (field in listOf("displayName", "baseInstructions", "defaultReasoningLevel")) if (m.has(field)) require(m.get(field) is String) { "$field 必须为字符串" }
                    if (m.has("contextWindow") && !m.isNull("contextWindow") && m.opt("contextWindow") != "") {
                        val value = m.get("contextWindow")
                        if (value is String) positive(window(m) ?: error("contextWindow 必须为正整数"), "contextWindow") else positive(value, "contextWindow")
                    }
                    if (m.has("supportsParallelToolCalls")) require(m.get("supportsParallelToolCalls") is Boolean) { "并行工具能力必须为布尔值" }
                    for ((field, allowed) in listOf("reasoningLevels" to efforts, "inputModalities" to listOf("text", "image"))) if (m.has(field)) {
                        val a = m.optJSONArray(field)
                        require(a != null && (0 until a.length()).all { a.get(it) is String && a.getString(it) in allowed }) { "$field 取值无效" }
                    }
                    if (m.optString("defaultReasoningLevel").isNotBlank()) require(m.optJSONArray("reasoningLevels")?.let { a -> (0 until a.length()).any { a.getString(it) == m.getString("defaultReasoningLevel") } } == true) { "默认推理档位必须在支持的档位中" }
                }
                require(models.length() == 0 || root.optString("model").isBlank() || root.getString("model") in seen) { "默认模型不在模型目录中" }
            }
        }
        private fun positive(value: Any, field: String, allowZero: Boolean = false) { require(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toLong().toDouble() && value.toLong() in (if (allowZero) 0L else 1L)..100000000L) { "$field 必须为${if (allowZero) "非负" else "正"}整数" } }
        fun checkProfile(root: JSONObject, selector: String?) {
            val name = root.optString("profile"); val profile = root.optJSONObject("profiles")?.optJSONObject(name) ?: return
            require(profile.optString("model_provider").let { it.isBlank() || it == (selector ?: "openai") } && profile.optString("openai_base_url").isBlank() && profile.optString("experimental_bearer_token").isBlank()) { "当前 profile「$name」覆盖了提供商选路，请先修改原生 profile" }
        }
        fun hasLogin(auth: JSONObject): Boolean {
            fun has(key: String) = auth.has(key) && !auth.isNull(key) && auth.opt(key)?.toString()?.isNotBlank() == true
            val mode = if (has("auth_mode")) auth.optString("auth_mode") else when {
                has("personal_access_token") -> "personalAccessToken"; has("bedrock_api_key") -> "bedrockApiKey"; has("bedrock_access_keys") -> "bedrockAccessKeys"; has("OPENAI_API_KEY") -> "apikey"; else -> "chatgpt"
            }
            return when (mode) {
                "apikey" -> (auth.opt("OPENAI_API_KEY") as? String).orEmpty().isNotBlank()
                "chatgpt", "chatgptAuthTokens" -> auth.optJSONObject("tokens")?.let { t -> listOf("id_token", "access_token", "refresh_token").any { (t.opt(it) as? String).orEmpty().isNotBlank() } } == true
                "personalAccessToken" -> has("personal_access_token")
                "agentIdentity" -> has("agent_identity")
                else -> false
            }
        }
        private fun rejectWebSearch(p: Projection, source: JSONObject): Boolean {
            if (source.optJSONObject("modelCatalog")?.optJSONArray("models")?.length() == 0) return false
            val host = runCatching { URI(p.table?.optString("base_url").orEmpty()).host.orEmpty().lowercase() }.getOrDefault("")
            val hosts = listOf("xiaomimimo.com", "longcat.chat", "minimax.io", "minimax.cn", "minimaxi.com", "stepfun.com", "stepfun.ai", "qianfan.baidubce.com", "xf-yun.com", "bigmodel.cn", "z.ai")
            return hosts.any { host == it || host.endsWith(".$it") } || listOf("mimo", "longcat", "minimax", "qwen3-coder", "glm").any { p.root.optString("model").substringAfterLast('/').lowercase().startsWith(it) }
        }
        fun imported(config: String, auth: String): JSONObject {
            val root = TomlDocument(config).root
            val selected = root.optString("model_provider")
            val official = selected in listOf("", "openai") && root.optString("openai_base_url").isBlank() && root.optJSONObject("model_providers")?.has("openai") != true
            var extracted = ""
            for (field in floor + exclusive) if (root.has(field)) extracted = TomlDocument(extracted).set(field, root.get(field))
            for (path in nested) at(root, path)?.let { extracted = TomlDocument(extracted).set(path, it) }
            if (!official) root.optJSONObject("model_providers")?.optJSONObject(selected)?.let { extracted = TomlDocument(extracted).set(listOf("model_providers", selected), it) }
            val source = blank(official).put("config", extracted)
            if (!official && auth.isNotBlank()) {
                val current = ProviderDocuments.parse(auth)
                if ((current.opt("OPENAI_API_KEY") as? String).orEmpty().isNotBlank() && key(source, root).isBlank()) source.getJSONObject("auth").put("OPENAI_API_KEY", current.getString("OPENAI_API_KEY"))
            }
            return source
        }
    }
}
