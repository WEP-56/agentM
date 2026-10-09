package dev.agentm.app.config

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

/** Self-contained templates: a missing credential is never borrowed from the live settings file. */
class ClaudeProfileStore(private val directory: File) {
    data class Profile(val id: String, val revision: String, val name: String, val fields: Map<String, String>, val updatedAt: Long) {
        fun json(): JSONObject = JSONObject().put("id", id).put("revision", revision).put("name", name)
            .put("fields", JSONObject(fields)).put("updatedAt", updatedAt)
    }
    private data class Library(val revision: String, val profiles: List<Profile>)
    private val storage get() = AtomicFile(File(directory, "claude-profiles.json"))
    private val crypto = EncryptedBackup("dev.agentm.claude-profiles.v1", "agentM/claude/profiles-v1")

    private fun paths() {
        if (Files.isSymbolicLink(directory.toPath())) throw ConfigFailure("PROFILE_STORAGE", "模板目录不能是符号链接")
        directory.mkdirs()
        for (name in listOf("claude-profiles.json", "claude-profiles.json.bak", "claude-profiles.json.new", "claude-profiles.lock"))
            if (Files.isSymbolicLink(File(directory, name).toPath())) throw ConfigFailure("PROFILE_STORAGE", "模板存储路径无效，未读取或覆盖")
    }
    private fun <T> locked(action: (Library) -> T): T {
        paths()
        return FileChannel.open(File(directory, "claude-profiles.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
            channel.lock().use { action(load()) }
        }
    }
    private fun load(): Library {
        if (!storage.baseFile.exists() && !File(directory, "claude-profiles.json.bak").exists()) return Library("missing", emptyList())
        try {
            val bytes = storage.openRead().use { input ->
                val buffer = ByteArray(MAX_BYTES + 1)
                var count = 0
                while (count < buffer.size) { val size = input.read(buffer, count, buffer.size - count); if (size < 0) break; count += size }
                require(count <= MAX_BYTES)
                buffer.copyOf(count)
            }
            val clear = crypto.decrypt(JSONObject(bytes.toString(Charsets.UTF_8)))
            val document = try { JSONObject(clear.toString(Charsets.UTF_8)) } finally { clear.fill(0) }
            require(document.getInt("schemaVersion") == 1)
            val array = document.getJSONArray("profiles")
            require(array.length() <= MAX_PROFILES)
            val profiles = (0 until array.length()).map { index ->
                val row = array.getJSONObject(index)
                val values = row.getJSONObject("fields")
                val fields = values.keys().asSequence().associateWith { key ->
                    require(values.get(key) is String)
                    values.getString(key)
                }
                validateFields(fields)
                Profile(row.getString("id"), row.getString("revision"), checkedName(row.getString("name")), fields, row.getLong("updatedAt")).also {
                    require(it.id.matches(ID) && it.revision.matches(ID))
                }
            }
            require(profiles.map { it.id }.toSet().size == profiles.size)
            return Library(digest(bytes), profiles)
        } catch (_: Exception) { throw ConfigFailure("PROFILE_STORAGE", "无法读取或解密模板库；现有文件未覆盖") }
    }
    private fun write(profiles: List<Profile>): Library {
        val clear = JSONObject().put("schemaVersion", 1).put("profiles", JSONArray(profiles.map { it.json() })).toString().toByteArray()
        val bytes = try { crypto.encrypt(clear).toString().toByteArray() } finally { clear.fill(0) }
        require(bytes.size <= MAX_BYTES)
        val stream = storage.startWrite()
        try { stream.write(bytes); storage.finishWrite(stream) }
        catch (error: Exception) { storage.failWrite(stream); throw error }
        return Library(digest(bytes), profiles)
    }
    private fun requireRevision(library: Library, expected: String) {
        if (library.revision != expected) throw ConfigFailure("PROFILE_CONFLICT", "模板库已变化；请刷新后重新打开编辑，未覆盖现有模板")
    }
    private fun summary(library: Library, current: Map<String, String>?): JSONObject = JSONObject()
        .put("revision", library.revision).put("canCompare", current != null).put("limit", MAX_PROFILES)
        .put("profiles", JSONArray(library.profiles.map { profile -> JSONObject()
            .put("id", profile.id).put("revision", profile.revision).put("name", profile.name).put("updatedAt", profile.updatedAt)
            .put("baseUrl", profile.fields[ClaudeSettings.BASE].orEmpty()).put("model", profile.fields[ClaudeSettings.MODEL].orEmpty())
            .put("authMode", mode(profile.fields)).put("hasSecret", profile.fields.containsKey(ClaudeSettings.KEY) || profile.fields.containsKey(ClaudeSettings.TOKEN))
            .put("matchesCurrent", current != null && profile.fields == current)
        }))
    fun snapshot(current: Map<String, String>?): JSONObject = locked { summary(it, current) }
    internal fun exportForMigration(): List<Profile> = locked { it.profiles }

    fun save(params: JSONObject, current: Map<String, String>?, capture: Map<String, String>? = null): JSONObject = locked { library ->
        requireRevision(library, params.getString("libraryRevision"))
        val id = params.optString("id")
        val previous = library.profiles.find { it.id == id }
        if (id.isNotEmpty() && previous == null) throw ConfigFailure("PROFILE_CONFLICT", "模板已被删除，请刷新列表")
        if (previous == null && library.profiles.size >= MAX_PROFILES) throw ConfigFailure("PROFILE_LIMIT", "最多保存 $MAX_PROFILES 个模板，请先删除不再需要的模板")
        val name = checkedName(params.optString("name"))
        if (library.profiles.any { it.id != id && it.name.equals(name, ignoreCase = true) }) throw ConfigFailure("PROFILE_INPUT", "已有同名模板，请换一个名称")
        val fields = try {
            capture ?: ClaudeSettings.desired(source(previous?.fields ?: emptyMap()), ClaudeSettings.Input(
                params.optString("baseUrl"), params.optString("model"), params.optString("authMode"), params.optString("secretAction", "keep"), params.optString("secret")))
        } catch (error: IllegalArgumentException) { throw ConfigFailure("PROFILE_INPUT", error.message ?: "请检查模板字段") }
        validateFields(fields)
        val next = Profile(previous?.id ?: UUID.randomUUID().toString(), UUID.randomUUID().toString(), name, fields.toMap(), System.currentTimeMillis())
        val rows = if (previous == null) library.profiles + next else library.profiles.map { if (it.id == id) next else it }
        summary(write(rows), current).put("savedId", next.id)
    }
    fun delete(params: JSONObject, current: Map<String, String>?): JSONObject = locked { library ->
        requireRevision(library, params.getString("libraryRevision"))
        val id = params.getString("id")
        if (library.profiles.none { it.id == id }) throw ConfigFailure("PROFILE_CONFLICT", "模板已被删除，请刷新列表")
        summary(write(library.profiles.filterNot { it.id == id }), current)
    }
    fun <T> withSelected(id: String, revision: String, action: (Profile) -> T): T = locked { library ->
        val selected = library.profiles.find { it.id == id && it.revision == revision }
            ?: throw ConfigFailure("PROFILE_CONFLICT", "该模板已修改或删除，请刷新后重新预览")
        action(selected)
    }
    companion object {
        private const val MAX_BYTES = 1024 * 1024
        private const val MAX_PROFILES = 32
        private val ID = Regex("[a-f0-9-]{36}")
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun checkedName(value: String): String = value.trim().also {
            if (it.isEmpty() || it.length > 80 || it.any { c -> c.code < 32 }) throw ConfigFailure("PROFILE_INPUT", "模板名称需为 1–80 个字符，不能包含控制字符")
        }
        private fun mode(fields: Map<String, String>) = when {
            fields.containsKey(ClaudeSettings.KEY) -> "apiKey"
            fields.containsKey(ClaudeSettings.TOKEN) -> "authToken"
            else -> "native"
        }
        private fun source(fields: Map<String, String>): String = JSONObject().put("env", JSONObject(fields)).toString()
        private fun validateFields(fields: Map<String, String>) {
            try {
                require(fields.keys.all { it in ClaudeSettings.fields })
                val normalized = ClaudeSettings.desired(source(fields), ClaudeSettings.Input(fields[ClaudeSettings.BASE].orEmpty(),
                    fields[ClaudeSettings.MODEL].orEmpty(), mode(fields), "keep", ""))
                require(fields == normalized)
            } catch (_: Exception) { throw ConfigFailure("PROFILE_INPUT", "配置包含冲突密钥、空字段或当前模板不支持的连接方式，请先调整配置") }
        }
    }
}
