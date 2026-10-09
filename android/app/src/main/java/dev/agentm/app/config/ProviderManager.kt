package dev.agentm.app.config

import dev.agentm.app.AgentMApplication
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class ProviderManager(
    private val app: AgentMApplication,
    private val home: File = app.linux.runtime.home,
    private val directory: File = File(app.filesDir, "config-state"),
) {
    private val crypto = EncryptedBackup("dev.agentm.providers.v1", "agentM/providers/v1")
    private val codex by lazy {
        fun asset(name: String) = app.assets.open(name).bufferedReader().use { JSONObject(it.readText()) }
        CodexDocuments(asset("codex-native-responses-template.json"), asset("codex-models-0.161.0.json").getJSONArray("models"), asset("codex-deepseek-catalog-template.json").getJSONArray("models"))
    }
    private fun keyed(kind: String) = kind == "pi" || kind == "opencode"
    private val files = ProviderFiles(home, directory,
        { crypto.encrypt(it).toString().toByteArray() }, { crypto.decrypt(JSONObject(it.toString(Charsets.UTF_8))) }, syncDirectory = { parent ->
            val fd = android.system.Os.open(parent.absolutePath, android.system.OsConstants.O_RDONLY or android.system.OsConstants.O_NOFOLLOW, 0)
            try { android.system.Os.fsync(fd) } finally { android.system.Os.close(fd) }
        })
    @Volatile var busy = false; private set
    @Volatile var recoveryError: String? = null; private set
    fun recover() = synchronized(app.maintenance) {
        recoveryError = runCatching { files.recover() }.exceptionOrNull()?.let { "提供商配置恢复未完成，请先在配置页处理冲突" }
    }
    private fun healthy() { files.recover(); recoveryError = null }
    private fun keys(kind: String): List<String> = when (kind) {
        "claude" -> listOf("claude")
        "pi" -> listOf("piModels", "piSettings")
        "opencode" -> OpenCodeDocuments.fileKeys
        "codex" -> CodexDocuments.fileKeys
        else -> error("不支持此 Agent 的提供商管理")
    }
    private fun openCodeFiles() = keys("opencode").associateWith { files.read(it) }
    private fun nativeProviders(kind: String): JSONObject? = if (kind == "opencode") OpenCodeDocuments.merged(openCodeFiles()).optJSONObject("provider")
        else ProviderDocuments.parse(files.text("piModels")).optJSONObject("providers")
    private fun importOpenCode(library: JSONObject, kind: String): JSONObject {
        if (kind != "opencode" || library.optBoolean("openCodeImported")) return library
        val revision = libraryRevision()
        val before = openCodeFiles()
        val native = OpenCodeDocuments.merged(before)
        require(!native.has("provider") || native.get("provider") is JSONObject) { "OpenCode provider 必须是对象" }
        val providers = native.optJSONObject("provider")
        providers?.keys()?.forEach { key ->
            val source = providers.optJSONObject(key) ?: error("OpenCode 提供商必须是对象")
            if (rows(library).none { it.optString("kind") == "opencode" && it.optString("providerKey") == key })
                library.getJSONArray("providers").put(row("opencode", source.optString("name").ifBlank { key }, key, source.toString(2)))
        }
        library.put("openCodeImported", true)
        commit(library, expectedNative = before.mapValues { ProviderFiles.digest(it.value) }, expectedLibrary = revision)
        return library
    }
    private fun importCodex(library: JSONObject, kind: String): JSONObject {
        if (kind != "codex" || library.optBoolean("codexImported")) return library
        val revision = libraryRevision()
        val before = keys(kind).associateWith { files.read(it) }
        val config = CodexDocuments.text(before["codexConfig"])
        val native = TomlDocument(config).root
        val official = native.optString("model_provider") in listOf("", "openai") && native.optString("openai_base_url").isBlank() && native.optJSONObject("model_providers")?.has("openai") != true
        val source = CodexDocuments.imported(config, CodexDocuments.text(before["codexAuth"]))
        val records = library.getJSONArray("providers")
        if (rows(library).none { it.getString("id") == CodexDocuments.OFFICIAL }) records.put(row("codex", "OpenAI Official", "", (if (official) source else CodexDocuments.blank(true)).toString(2), CodexDocuments.OFFICIAL).put("official", true))
        if (!official) records.put(row("codex", "当前 Codex 配置", "", source.toString(2)))
        library.put("codexImported", true)
        commit(library, expectedNative = before.mapValues { ProviderFiles.digest(it.value) }, expectedLibrary = revision)
        return library
    }
    private fun load(kind: String): JSONObject {
        healthy()
        files.readLibrary()?.let { require(it.getInt("schemaVersion") == 1); return importCodex(importOpenCode(it, kind), kind) }
        val rows = JSONArray().put(JSONObject().put("id", OFFICIAL).put("kind", "claude").put("name", "Claude Official")
            .put("official", true).put("source", "{\n  \"env\": {}\n}\n").put("providerKey", ""))
        if (File(directory, "claude-profiles.json").exists() || File(directory, "claude-profiles.json.bak").exists()) {
            for (old in ClaudeProfileStore(directory).exportForMigration()) rows.put(row("claude", old.name, "", JSONObject().put("env", JSONObject(old.fields)).toString(2), old.id))
        }
        val claude = ProviderDocuments.parse(files.text("claude"))
        val env = claude.optJSONObject("env") ?: JSONObject()
        val managed = JSONObject()
        ProviderDocuments.claudeKeys.filter { env.has(it) }.forEach { managed.put(it, env.get(it)) }
        if (managed.length() > 0 && (0 until rows.length()).none { ProviderDocuments.equal(JSONObject(rows.getJSONObject(it).getString("source")).optJSONObject("env"), managed) })
            rows.put(row("claude", "当前 Claude 配置", "", JSONObject().put("env", managed).toString(2)))
        val pi = ProviderDocuments.parse(files.text("piModels")).optJSONObject("providers")
        pi?.keys()?.forEach { key -> if (pi.optJSONObject(key) != null) rows.put(row("pi", pi.getJSONObject(key).optString("name", key), key, pi.getJSONObject(key).toString(2))) }
        require(rows.length() <= 64) { "现有提供商过多，请先整理配置" }
        val library = JSONObject().put("schemaVersion", 1).put("providers", rows)
        files.commit(mapOf("library" to "missing"), mapOf("library" to files.encodeLibrary(library)))
        return importCodex(importOpenCode(library, kind), kind)
    }
    private fun row(kind: String, name: String, key: String, source: String, id: String = UUID.randomUUID().toString()) =
        JSONObject().put("id", id).put("kind", kind).put("name", name).put("providerKey", key).put("source", source).put("official", false)
    private fun rows(library: JSONObject) = library.getJSONArray("providers").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    private fun selected(library: JSONObject, kind: String, id: String): JSONObject = rows(library).find { it.getString("kind") == kind && it.getString("id") == id } ?: error("提供商已不存在，请刷新")
    private fun libraryRevision() = ProviderFiles.digest(files.read("library"))
    private fun requireRevision(expected: String) { check(libraryRevision() == expected) { "提供商列表已变化，请重新读取后再操作" } }
    private fun active(row: JSONObject, previous: JSONObject, sameProvider: Boolean): Boolean = runCatching {
        val source = ProviderDocuments.parse(row.getString("source"))
        if (row.getString("kind") == "claude") {
            val current = files.text("claude")
            ProviderDocuments.equal(ProviderDocuments.parse(current), ProviderDocuments.parse(ProviderDocuments.claude(current, source, previous, sameProvider)))
        } else if (row.getString("kind") == "codex") {
            val before = keys("codex").associateWith { files.read(it) }
            codex.apply(before, source, row.optBoolean("official"), previous).all { (key, value) ->
                if (key == "codexConfig") ProviderDocuments.equal(TomlDocument(CodexDocuments.text(before[key])).root, TomlDocument(CodexDocuments.text(value)).root)
                else value.contentEquals(before[key])
            }
        } else if (row.getString("kind") == "opencode") {
            val current = OpenCodeDocuments.merged(openCodeFiles())
            val key = row.getString("providerKey")
            val model = current.optString("model").removePrefix("$key/")
            OpenCodeDocuments.enabled(current, key) && current.optString("model").startsWith("$key/") && source.optJSONObject("models")?.has(model) == true &&
                ProviderDocuments.equal(current.optJSONObject("provider")?.optJSONObject(key), source)
        } else {
            val defaults = ProviderDocuments.parse(files.text("piSettings"))
            val current = ProviderDocuments.parse(files.text("piModels")).optJSONObject("providers")?.optJSONObject(row.getString("providerKey"))
            defaults.optString("defaultProvider") == row.getString("providerKey") && ProviderDocuments.equal(current, source)
        }
    }.getOrDefault(false)
    private fun summary(kind: String, library: JSONObject): JSONObject {
        val records = rows(library).filter { it.getString("kind") == kind }
        val preferred = library.optJSONObject("active")?.optString(kind)
        val previous = library.optJSONObject(if (kind == "codex") "lastCodexSource" else "lastClaudeSource") ?: JSONObject()
        val current = records.firstOrNull { it.optString("id") == preferred && active(it, previous, true) } ?: records.firstOrNull { active(it, previous, it.optString("id") == preferred) }
        return JSONObject().put("kind", kind).put("revision", libraryRevision()).put("nativeRevision", files.revision(keys(kind)))
            .put("providers", JSONArray(records.map { record ->
                val source = ProviderDocuments.parse(record.getString("source"))
                JSONObject().put("id", record.getString("id")).put("name", record.getString("name")).put("providerKey", record.optString("providerKey"))
                    .put("official", record.optBoolean("official")).put("active", current === record)
                    .put("baseUrl", if (record.optBoolean("official")) { if (kind == "codex") "https://chatgpt.com/codex" else "https://www.anthropic.com/claude-code" } else if (kind == "codex") runCatching { CodexDocuments.project(source, false).table?.optString("base_url").orEmpty() }.getOrDefault("") else if (kind == "claude") source.optJSONObject("env")?.optString(ClaudeSettings.BASE).orEmpty() else if (kind == "opencode") source.optJSONObject("options")?.optString("baseURL").orEmpty() else source.optString("baseUrl"))
            })).put("hasCurrent", current != null)
    }
    fun list(kind: String): JSONObject = synchronized(app.maintenance) { keys(kind); summary(kind, load(kind)) }
    fun read(kind: String, id: String): JSONObject = synchronized(app.maintenance) {
        keys(kind)
        val library = load(kind)
        val record = if (id.isNotEmpty()) selected(library, kind, id) else if (kind == "codex") row(kind, "", "", CodexDocuments.blank().toString(2)) else if (kind == "claude") row(kind, "", "", "{\n  \"env\": {\n    \"ANTHROPIC_BASE_URL\": \"\",\n    \"ANTHROPIC_AUTH_TOKEN\": \"\"\n  }\n}") else if (kind == "opencode") row(kind, "", "", JSONObject().put("npm", "@ai-sdk/openai-compatible").put("options", JSONObject().put("baseURL", "").put("apiKey", "")).put("models", JSONObject()).toString(2)) else row(kind, "", "", "{\n  \"api\": \"openai-completions\",\n  \"baseUrl\": \"\",\n  \"apiKey\": \"\",\n  \"models\": []\n}")
        JSONObject(record.toString()).put("id", id).put("revision", libraryRevision()).put("nativeRevision", files.revision(keys(kind)))
            .put("defaultModel", if (kind == "pi") ProviderDocuments.parse(files.text("piSettings")).optString("defaultModel") else "")
    }
    private fun updates(kind: String, record: JSONObject, before: Map<String, ByteArray?>, previous: JSONObject, model: String = "", sameProvider: Boolean = false): Map<String, ByteArray?> {
        fun sourceOf(key: String) = before[key]?.toString(Charsets.UTF_8) ?: "{\n}\n"
        val source = ProviderDocuments.validate(kind, record.getString("source"), record.optString("providerKey"), record.optBoolean("official"))
        return if (kind == "codex") codex.apply(before, source, record.optBoolean("official"), previous) else if (kind == "claude") mapOf("claude" to ProviderDocuments.claude(sourceOf("claude"), source, previous, sameProvider).toByteArray()) else if (kind == "opencode") {
            val models = source.getJSONObject("models")
            val chosen = model.ifBlank { models.keys().next() }
            require(models.has(chosen)) { "默认模型不属于此提供商" }
            require(source.optJSONArray("blacklist")?.let { a -> (0 until a.length()).none { a.optString(it) == chosen } } != false &&
                source.optJSONArray("whitelist")?.let { a -> (0 until a.length()).any { a.optString(it) == chosen } } != false) { "默认模型被此提供商的模型筛选规则排除" }
            OpenCodeDocuments.apply(before, record.getString("providerKey"), source, chosen)
        } else {
            val key = record.getString("providerKey")
            val models = source.getJSONArray("models")
            val chosen = model.ifBlank { models.getJSONObject(0).getString("id") }
            require((0 until models.length()).any { models.getJSONObject(it).getString("id") == chosen }) { "默认模型不属于此提供商" }
            mapOf("piModels" to ProviderDocuments.piModels(sourceOf("piModels"), key, source).toByteArray(),
                "piSettings" to ProviderDocuments.piDefaults(sourceOf("piSettings"), key, chosen).toByteArray())
        }
    }
    private fun commit(library: JSONObject, native: Map<String, ByteArray?> = emptyMap(), expectedNative: Map<String, String> = emptyMap(), expectedLibrary: String = libraryRevision()) {
        check(!app.packages.busy && !app.linux.busy && !app.configs.busy) { "环境或软件管理进行中，请稍后保存配置" }
        require(native.values.all { it == null || it.size <= 1024 * 1024 }) { "更新后的原生配置超过 1 MiB，请精简后重试" }
        val changes = native + ("library" to files.encodeLibrary(library))
        busy = true
        try { files.commit(expectedNative + ("library" to expectedLibrary), changes) }
        catch (failure: Exception) { recover(); throw failure }
        finally { busy = false }
    }
    fun save(params: JSONObject): JSONObject = synchronized(app.maintenance) {
        val kind = params.getString("kind"); keys(kind)
        val library = load(kind); requireRevision(params.getString("revision"))
        val id = params.optString("id")
        val old = if (id.isEmpty()) null else selected(library, kind, id)
        val name = params.getString("name").trim()
        require(name.isNotBlank() && name.length <= 80 && name.none { it.code < 32 }) { "请填写 1–80 字的提供商名称" }
        val key = params.optString("providerKey").trim()
        if (keyed(kind) && old != null) require(old.getString("providerKey") == key) { "已有供应商标识不可改名；可复制后编辑" }
        val source = params.getString("source")
        ProviderDocuments.validate(kind, source, key, old?.optBoolean("official") == true)
        val records = rows(library)
        require(records.none { it.optString("id") != id && it.getString("kind") == kind && (it.getString("name").equals(name, true) || (keyed(kind) && it.optString("providerKey") == key)) }) { "提供商名称或供应商标识已存在" }
        if (keyed(kind) && old == null) require(nativeProviders(kind)?.has(key) != true) { "此标识已在原生配置中存在，请换一个标识" }
        require(old != null || records.count { it.getString("kind") == kind } < 32) { "每类最多保存 32 个提供商" }
        val next = row(kind, if (old?.optBoolean("official") == true) { if (kind == "codex") "OpenAI Official" else "Claude Official" } else name, key, source, old?.getString("id") ?: UUID.randomUUID().toString()).put("official", old?.optBoolean("official") == true)
        library.put("providers", JSONArray(if (old == null) records + next else records.map { if (it === old) next else it }))
        // Saving a draft never silently switches live credentials; selection remains an explicit confirmed action.
        commit(library, expectedLibrary = params.getString("revision"))
        summary(kind, library).put("savedId", next.getString("id"))
    }
    fun copy(params: JSONObject): JSONObject = synchronized(app.maintenance) {
        val kind = params.getString("kind"); keys(kind)
        val library = load(kind); requireRevision(params.getString("revision"))
        val old = selected(library, kind, params.getString("id"))
        val records = rows(library)
        require(records.count { it.getString("kind") == kind } < 32) { "每类最多保存 32 个提供商" }
        var n = 1; var name: String; var key: String
        val nativeKeys = if (keyed(kind)) nativeProviders(kind) else null
        do {
            name = old.getString("name").take(65) + " 副本 $n"
            key = old.optString("providerKey").take(65) + "-copy-$n"; n++
        } while (records.any { it.getString("kind") == kind && (it.getString("name") == name || (keyed(kind) && it.optString("providerKey") == key)) } || nativeKeys?.has(key) == true)
        val copy = row(kind, name, if (keyed(kind)) key else "", old.getString("source"))
        library.getJSONArray("providers").put(copy); commit(library, expectedLibrary = params.getString("revision"))
        summary(kind, library).put("savedId", copy.getString("id"))
    }
    fun delete(params: JSONObject): JSONObject = synchronized(app.maintenance) {
        val kind = params.getString("kind"); keys(kind)
        val library = load(kind); requireRevision(params.getString("revision"))
        val old = selected(library, kind, params.getString("id"))
        require(!old.optBoolean("official")) { "Official 默认提供商不能删除" }
        val before = keys(kind).associateWith { files.read(it) }
        val expected = before.mapValues { ProviderFiles.digest(it.value) }
        check(ProviderFiles.digest(keys(kind).joinToString("|") { "$it:${expected.getValue(it)}" }.toByteArray()) == params.getString("nativeRevision")) { "Agent 配置已变化，请刷新后重新确认删除" }
        fun sourceOf(key: String) = before[key]?.toString(Charsets.UTF_8) ?: "{\n}\n"
        val changes = linkedMapOf<String, ByteArray?>()
        if (kind == "codex") {
            val previous = library.optJSONObject("lastCodexSource") ?: JSONObject()
            val applied = JSONObject(old.toString()).put("source", previous.toString())
            val visible = summary(kind, library).getJSONArray("providers")
            val selected = (0 until visible.length()).any { visible.getJSONObject(it).optString("id") == old.getString("id") && visible.getJSONObject(it).optBoolean("active") }
            if (selected || (library.optJSONObject("active")?.optString(kind) == old.getString("id") && previous.has("config") && active(applied, previous, true))) {
                val official = selected(library, kind, CodexDocuments.OFFICIAL)
                changes.putAll(updates(kind, official, before, previous))
                (library.optJSONObject("active") ?: JSONObject().also { library.put("active", it) }).put(kind, CodexDocuments.OFFICIAL)
                library.put("lastCodexSource", ProviderDocuments.parse(official.getString("source")))
            }
        } else if (kind == "opencode") {
            changes.putAll(OpenCodeDocuments.remove(before, old.getString("providerKey")))
        } else if (kind == "pi") {
            val key = old.getString("providerKey")
            if (ProviderDocuments.parse(sourceOf("piModels")).optJSONObject("providers")?.has(key) == true)
                changes["piModels"] = ProviderDocuments.removePiProvider(sourceOf("piModels"), key).toByteArray()
            if (ProviderDocuments.parse(sourceOf("piSettings")).optString("defaultProvider") == key)
                changes["piSettings"] = ProviderDocuments.edit(ProviderDocuments.edit(sourceOf("piSettings"), "defaultProvider", null), "defaultModel", null).toByteArray()
        } else {
            val visible = summary(kind, library).getJSONArray("providers")
            val last = library.optJSONObject("lastClaudeSource")
            val stillApplied = last != null && library.optJSONObject("active")?.optString("claude") == old.getString("id") &&
                ProviderDocuments.equal(ProviderDocuments.parse(sourceOf("claude")), ProviderDocuments.parse(ProviderDocuments.claude(sourceOf("claude"), last, last)))
            val isCurrent = stillApplied || (0 until visible.length()).any { visible.getJSONObject(it).optString("id") == old.getString("id") && visible.getJSONObject(it).optBoolean("active") }
            if (isCurrent) {
                val official = selected(library, kind, OFFICIAL)
                changes.putAll(updates(kind, official, before, library.optJSONObject("lastClaudeSource") ?: JSONObject()))
                (library.optJSONObject("active") ?: JSONObject().also { library.put("active", it) }).put(kind, OFFICIAL)
                library.put("lastClaudeSource", ProviderDocuments.parse(official.getString("source")))
            }
        }
        library.put("providers", JSONArray(rows(library).filter { it !== old }))
        commit(library, changes, expected, params.getString("revision"))
        summary(kind, library)
    }
    fun switch(params: JSONObject): JSONObject = synchronized(app.maintenance) {
        val kind = params.getString("kind"); keys(kind)
        val library = load(kind); requireRevision(params.getString("revision"))
        val before = keys(kind).associateWith { files.read(it) }
        val expected = before.mapValues { ProviderFiles.digest(it.value) }
        check(ProviderFiles.digest(keys(kind).joinToString("|") { "$it:${expected.getValue(it)}" }.toByteArray()) == params.getString("nativeRevision")) { "Agent 配置已变化，请刷新列表后重新确认切换" }
        check(app.linux.ready) { "请先准备 Ubuntu" }
        val record = selected(library, kind, params.getString("id"))
        val active = library.optJSONObject("active") ?: JSONObject().also { library.put("active", it) }
        val sameProvider = active.optString(kind) == record.getString("id")
        active.put(kind, record.getString("id"))
        val changes = updates(kind, record, before, library.optJSONObject(if (kind == "codex") "lastCodexSource" else "lastClaudeSource") ?: JSONObject(), params.optString("model"), sameProvider)
        if (kind == "claude") library.put("lastClaudeSource", ProviderDocuments.parse(record.getString("source")))
        if (kind == "codex") library.put("lastCodexSource", if (record.optBoolean("official")) ProviderDocuments.parse(record.getString("source")) else codex.appliedSource(ProviderDocuments.parse(record.getString("source"))))
        commit(library, changes, expected, params.getString("revision"))
        app.logs.add("config", "$kind 提供商已切换；未记录密钥或配置内容")
        summary(kind, library)
    }
    companion object { const val OFFICIAL = "claude-official" }
}
