package dev.agentm.app.config

import org.json.JSONObject

/** OpenCode 1.18 native provider fragments and the three global JSON/JSONC config layers. */
object OpenCodeDocuments {
    val fileKeys = listOf("opencodeLegacy", "opencodeJson", "opencodeJsonc")
    private fun decode(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    fun enabled(root: JSONObject, key: String): Boolean =
        root.optJSONArray("disabled_providers")?.let { a -> (0 until a.length()).none { a.optString(it) == key } } != false &&
            root.optJSONArray("enabled_providers")?.let { a -> (0 until a.length()).any { a.optString(it) == key } } != false
    private fun normalized(source: String): String {
        require(source.toByteArray().size <= 1024 * 1024) { "配置文件超过 1 MiB" }
        val chars = source.toCharArray()
        var i = 0
        while (i < chars.size) {
            when {
                chars[i] == '"' -> {
                    i++
                    while (i < chars.size) { if (chars[i] == '\\') i += 2 else if (chars[i++] == '"') break }
                }
                i + 1 < chars.size && chars[i] == '/' && chars[i + 1] == '/' -> {
                    while (i < chars.size && chars[i] != '\n' && chars[i] != '\r') chars[i++] = ' '
                }
                i + 1 < chars.size && chars[i] == '/' && chars[i + 1] == '*' -> {
                    chars[i++] = ' '; chars[i++] = ' '
                    while (i + 1 < chars.size && !(chars[i] == '*' && chars[i + 1] == '/')) {
                        if (chars[i] !in "\n\r") chars[i] = ' '
                        i++
                    }
                    require(i + 1 < chars.size) { "JSONC 注释未闭合" }
                    chars[i++] = ' '; chars[i++] = ' '
                }
                else -> i++
            }
        }
        i = 0
        while (i < chars.size) {
            if (chars[i] == '"') {
                i++
                while (i < chars.size) { if (chars[i] == '\\') i += 2 else if (chars[i++] == '"') break }
            } else {
                if (chars[i] == ',') {
                    var next = i + 1; while (next < chars.size && chars[next] in " \t\r\n") next++
                    var previous = i - 1; while (previous >= 0 && chars[previous] in " \t\r\n") previous--
                    if (next < chars.size && chars[next] in "}]" && previous >= 0 && chars[previous] !in "{[,:" ) chars[i] = ' '
                }
                i++
            }
        }
        return String(chars)
    }
    fun parse(source: String): JSONObject = ProviderDocuments.parse(normalized(source))
    private fun document(source: String) = JsonDocument(source, normalized(source))
    private fun edit(source: String, key: String, value: Any?): String = document(source).let {
        it.editObject(it.root, key, value?.let { v -> if (v is String) JsonDocument.quote(v) else v.toString() })
    }
    private fun provider(source: String, key: String, value: JSONObject?): String {
        val root = parse(source)
        require(!root.has("provider") || root.get("provider") is JSONObject) { "OpenCode provider 必须是对象" }
        if (!root.has("provider") && value == null) return source
        val result = if (root.has("provider")) source else edit(source, "provider", JSONObject())
        val doc = document(result)
        return doc.editObject(doc.root.member("provider")!!.value, key, value?.toString(2))
    }
    fun merged(before: Map<String, ByteArray?>): JSONObject {
        fun merge(a: JSONObject, b: JSONObject): JSONObject {
            for (key in b.keys()) {
                val next = b.get(key); val old = a.opt(key)
                a.put(key, if (old is JSONObject && next is JSONObject) merge(old, next) else next)
            }
            return a
        }
        return fileKeys.fold(JSONObject()) { root, key -> merge(root, parse(before[key]?.let(::decode) ?: "{}")) }
    }
    fun apply(before: Map<String, ByteArray?>, key: String, desired: JSONObject, model: String): Map<String, ByteArray?> {
        val effective = merged(before)
        require(enabled(effective, key)) { "此提供商被 OpenCode enabled_providers / disabled_providers 规则排除，请先从原生配置启用" }
        val target = fileKeys.lastOrNull { before[it] != null } ?: "opencodeJson"
        val changes = linkedMapOf<String, ByteArray?>()
        for (file in fileKeys) {
            if (before[file] == null && file != target) continue
            val original = before[file]?.let(::decode) ?: "{\n}\n"
            // Remove lower-layer copies so fields explicitly deleted in the editor cannot reappear on deep merge.
            var next = provider(original, key, if (file == target) desired else null)
            if (file == target) next = edit(next, "model", "$key/$model")
            if (next != original) changes[file] = next.toByteArray()
        }
        return changes
    }
    fun remove(before: Map<String, ByteArray?>, key: String): Map<String, ByteArray?> {
        val changes = linkedMapOf<String, ByteArray?>()
        for (file in fileKeys) {
            val original = before[file]?.let(::decode) ?: continue
            var next = provider(original, key, null)
            for (field in listOf("model", "small_model")) if (parse(next).optString(field).startsWith("$key/")) next = edit(next, field, null)
            if (next != original) changes[file] = next.toByteArray()
        }
        return changes
    }
    fun validate(json: JSONObject) {
        fun string(o: JSONObject, field: String) { require(!o.has(field) || o.get(field) is String) { "$field 必须是字符串" } }
        fun obj(o: JSONObject, field: String): JSONObject? {
            require(!o.has(field) || o.get(field) is JSONObject) { "$field 必须是对象" }
            return o.optJSONObject(field)
        }
        fun bool(o: JSONObject, field: String) { require(!o.has(field) || o.get(field) is Boolean) { "$field 必须是布尔值" } }
        fun number(o: JSONObject, field: String) { require(o.opt(field) is Number && (o.get(field) as Number).toDouble().isFinite()) { "$field 必须是有限数值" } }
        fun headers(o: JSONObject) { o.keys().forEach { name -> require(name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) && o.get(name) is String && o.getString(name).none { it == '\r' || it == '\n' }) { "请求头名称或值无效" } } }
        for (field in listOf("npm", "name", "api", "id")) string(json, field)
        // Built-in providers may omit npm/baseURL and use OpenCode's own defaults/authentication.
        obj(json, "options")?.let { options ->
            for (field in listOf("apiKey", "baseURL", "enterpriseUrl")) string(options, field)
            options.optString("baseURL").takeIf { it.isNotBlank() && !it.startsWith("{env:") && !it.startsWith("{file:") }?.let(ProviderDocuments::url)
            bool(options, "setCacheKey")
            for (field in listOf("timeout", "headerTimeout", "chunkTimeout")) if (options.has(field) && options.get(field) != false) {
                val n = options.opt(field)
                require(n is Number && n.toDouble().isFinite() && n.toDouble() > 0 && n.toDouble() == n.toLong().toDouble()) { "$field 必须为正整数或 false" }
            }
            obj(options, "headers")?.let(::headers)
            require(options.keys().asSequence().none { it.isBlank() }) { "SDK 选项名称不能为空" }
        }
        for (field in listOf("env", "whitelist", "blacklist")) if (json.has(field)) {
            val a = json.optJSONArray(field)
            require(a != null && (0 until a.length()).all { a.get(it) is String }) { "$field 必须是字符串数组" }
        }
        val models = obj(json, "models") ?: error("请配置至少一个模型")
        require(models.length() in 1..200) { "模型数量需为 1–200" }
        for (id in models.keys()) {
            require(id.isNotBlank() && id.length <= 256 && id.none { it.code < 32 }) { "模型 ID 为空或无效" }
            val model = obj(models, id)!!
            for (field in listOf("id", "name", "family", "release_date")) string(model, field)
            for (field in listOf("reasoning", "attachment", "temperature", "tool_call", "experimental")) bool(model, field)
            if (model.has("status")) require(model.optString("status") in setOf("alpha", "beta", "deprecated", "active")) { "模型 status 无效" }
            obj(model, "limit")?.let { limit -> number(limit, "context"); number(limit, "output"); if (limit.has("input")) number(limit, "input") }
            obj(model, "modalities")?.let { m -> for (field in listOf("input", "output")) if (m.has(field)) {
                val a = m.optJSONArray(field)
                require(a != null && (0 until a.length()).all { a.get(it) is String && a.getString(it) in setOf("text", "image", "audio", "video", "pdf") }) { "modalities.$field 格式无效" }
            } }
            obj(model, "options"); obj(model, "headers")?.let(::headers)
            obj(model, "provider")?.let { string(it, "npm"); string(it, "api") }
            obj(model, "variants")?.let { variants -> for (variant in variants.keys()) bool(obj(variants, variant)!!, "disabled") }
            obj(model, "cost")?.let { cost ->
                fun costs(o: JSONObject) { number(o, "input"); number(o, "output"); for (f in listOf("cache_read", "cache_write")) if (o.has(f)) number(o, f) }
                costs(cost); obj(cost, "context_over_200k")?.let(::costs)
            }
            if (model.has("interleaved")) {
                val value = model.get("interleaved")
                require(value is Boolean || value is String || (value is JSONObject && value.opt("field") is String)) { "interleaved 格式无效" }
            }
            require(model.keys().asSequence().none { it.isBlank() }) { "模型属性名称不能为空" }
        }
    }
}
