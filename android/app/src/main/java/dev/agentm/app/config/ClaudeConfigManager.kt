package dev.agentm.app.config

import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import dev.agentm.app.AgentMApplication
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

class ConfigFailure(val code: String, message: String) : IllegalStateException(message)

class ClaudeConfigManager(
    private val app: AgentMApplication,
    private val home: File = app.linux.runtime.home,
    private val workspace: File = app.linux.runtime.workspace,
    private val stateDirectory: File = File(app.filesDir, "config-state"),
) {
    private data class NativeFile(val bytes: ByteArray?, val revision: String) {
        fun source(): String = bytes?.let { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(it)).toString() } ?: "{\n}\n"
    }
    private data class ProfileRef(val id: String, val revision: String)
    private data class Plan(val token: String, val revision: String, val next: ByteArray?, val expires: Long, val profile: ProfileRef? = null)
    private val plans = linkedMapOf<String, Plan>()
    private val backup get() = AtomicFile(File(stateDirectory, "claude-backup.json"))
    private val secrets = EncryptedBackup()
    private val profiles = ClaudeProfileStore(stateDirectory)
    @Volatile var busy: Boolean = false; private set

    private fun target(): File {
        val directory = File(home, ".claude")
        val file = File(directory, "settings.json")
        if (listOf(home, directory, file).any { Files.isSymbolicLink(it.toPath()) } ||
            file.canonicalFile != File(home.canonicalFile, ".claude/settings.json"))
            throw ConfigFailure("CONFIG_PATH", "配置路径包含符号链接，未读取或写入")
        return file
    }
    private fun nativeFile(): NativeFile {
        val file = target()
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return NativeFile(null, "missing")
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) throw ConfigFailure("CONFIG_PATH", "配置路径不是普通文件")
        val bytes = FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            if (channel.size() > MAX_BYTES) throw ConfigFailure("CONFIG_SIZE", "配置文件超过 1 MiB，请在终端中处理")
            val buffer = ByteBuffer.allocate(MAX_BYTES + 1)
            while (channel.read(buffer) > 0) if (!buffer.hasRemaining()) throw ConfigFailure("CONFIG_SIZE", "配置文件超过大小限制")
            buffer.flip(); ByteArray(buffer.remaining()).also { buffer.get(it) }
        }
        return NativeFile(bytes, revision(bytes))
    }
    private fun values(file: NativeFile): Map<String, String> = try { ClaudeSettings.values(file.source()) }
        catch (_: Exception) { throw ConfigFailure("CONFIG_INVALID", "配置不是可安全编辑的 JSON 对象，或存在重复键、无效 env 字段；原文件保持不变") }
    private fun backupRecord(): JSONObject? = runCatching { JSONObject(backup.openRead().bufferedReader().use { it.readText() }) }.getOrNull()
    private fun warnings(): JSONArray {
        val paths = listOf(
            File(workspace, ".claude/settings.json") to "/workspace/.claude/settings.json",
            File(workspace, ".claude/settings.local.json") to "/workspace/.claude/settings.local.json",
            File(app.linux.runtime.rootfs, "etc/claude-code/managed-settings.json") to "/etc/claude-code/managed-settings.json",
        )
        return JSONArray(paths.filter { Files.exists(it.first.toPath(), LinkOption.NOFOLLOW_LINKS) }.map { it.second })
    }
    @Synchronized fun read(): JSONObject {
        expire()
        val file = nativeFile()
        val current = values(file)
        val hasKey = !current[ClaudeSettings.KEY].isNullOrEmpty()
        val hasToken = !current[ClaudeSettings.TOKEN].isNullOrEmpty()
        return JSONObject().put("revision", file.revision).put("exists", file.bytes != null).put("path", "~/.claude/settings.json")
            .put("baseUrl", current[ClaudeSettings.BASE].orEmpty()).put("model", current[ClaudeSettings.MODEL].orEmpty())
            .put("authMode", if (hasKey && hasToken) "conflict" else if (hasToken) "authToken" else if (hasKey) "apiKey" else "native")
            .put("hasApiKey", hasKey).put("hasAuthToken", hasToken).put("canRestore", backupRecord()?.optString("afterRevision") == file.revision)
            .put("overrides", warnings()).put("busy", app.packages.busy || app.terminals.session?.isRunning == true || busy)
    }
    @Synchronized fun preview(params: JSONObject): JSONObject {
        val before = nativeFile()
        checkRevision(before, params.getString("revision"))
        val input = ClaudeSettings.Input(params.optString("baseUrl"), params.optString("model"), params.optString("authMode"), params.optString("secretAction", "keep"), params.optString("secret"))
        val source = before.source()
        values(before)
        val after = try {
            val desired = ClaudeSettings.desired(source, input)
            if (before.bytes == null && desired.isEmpty()) null else ClaudeSettings.patch(source, desired).toByteArray()
        }
            catch (error: IllegalArgumentException) { throw ConfigFailure("CONFIG_INPUT", error.message ?: "请检查配置字段") }
        return plan(before, after, "save")
    }
    private fun currentFieldsOrNull(): Map<String, String>? = runCatching { values(nativeFile()) }.getOrNull()
    @Synchronized fun listProfiles(): JSONObject = profiles.snapshot(currentFieldsOrNull())
    @Synchronized fun saveProfile(params: JSONObject): JSONObject {
        val capture = if (params.optBoolean("captureCurrent")) {
            val current = nativeFile()
            checkRevision(current, params.getString("nativeRevision"))
            values(current)
        } else null
        return profiles.save(params, currentFieldsOrNull(), capture)
    }
    @Synchronized fun deleteProfile(params: JSONObject): JSONObject = profiles.delete(params, currentFieldsOrNull())
    @Synchronized fun previewProfile(params: JSONObject): JSONObject {
        val before = nativeFile()
        checkRevision(before, params.getString("revision"))
        values(before)
        return profiles.withSelected(params.getString("id"), params.getString("profileRevision")) { selected ->
            val after = if (before.bytes == null && selected.fields.isEmpty()) null else ClaudeSettings.patch(before.source(), selected.fields).toByteArray()
            plan(before, after, "profile", ProfileRef(selected.id, selected.revision)).put("profileName", selected.name)
        }
    }
    @Synchronized fun previewRestore(expectedRevision: String): JSONObject {
        val before = nativeFile()
        checkRevision(before, expectedRevision)
        val saved = backupRecord() ?: throw ConfigFailure("CONFIG_NO_BACKUP", "没有可恢复的配置备份")
        if (saved.optString("afterRevision") != before.revision) throw ConfigFailure("CONFIG_CONFLICT", "配置在上次保存后发生变化，不能覆盖恢复；请重新读取")
        val contents = try { JSONObject(secrets.decrypt(saved.getJSONObject("encrypted")).toString(Charsets.UTF_8)) }
            catch (_: Exception) { throw ConfigFailure("CONFIG_BACKUP", "无法解密备份，原文件保持不变") }
        val after = if (contents.getBoolean("exists")) android.util.Base64.decode(contents.getString("bytes"), android.util.Base64.NO_WRAP) else null
        values(NativeFile(after, revision(after)))
        return plan(before, after, "restore")
    }
    private fun plan(before: NativeFile, next: ByteArray?, action: String, profile: ProfileRef? = null): JSONObject {
        if (next != null && next.size > MAX_BYTES) throw ConfigFailure("CONFIG_SIZE", "修改后的配置超过 1 MiB")
        expire()
        val token = UUID.randomUUID().toString()
        val expiry = System.currentTimeMillis() + 120000
        val current = values(before)
        val desired = values(NativeFile(next, revision(next)))
        val changes = JSONArray()
        for (key in ClaudeSettings.fields) if (current[key] != desired[key]) {
            fun display(value: String?): String = if (value == null) "未设置" else if (key in setOf(ClaudeSettings.KEY, ClaudeSettings.TOKEN)) "已设置（隐藏）" else value
            changes.put(JSONObject().put("field", key).put("before", display(current[key])).put("after", display(desired[key]))
                .put("operation", if (desired[key] == null) "删除" else if (current[key] == null) "新增" else "替换"))
        }
        val changed = !before.bytes.contentEquals(next)
        if (changed) {
            plans[token] = Plan(token, before.revision, next, expiry, profile)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ synchronized(this) { expire() } }, 120001)
        }
        while (plans.size > 4) plans.remove(plans.keys.first())?.next?.fill(0)
        return JSONObject().put("token", if (changed) token else JSONObject.NULL).put("expiresAt", expiry).put("action", action)
            .put("changes", changes).put("changed", changed).put("deletesFile", next == null)
    }
    private fun expire() { plans.entries.removeAll { if (it.value.expires < System.currentTimeMillis()) { it.value.next?.fill(0); true } else false } }
    private fun checkRevision(file: NativeFile, expected: String) { if (file.revision != expected) throw ConfigFailure("CONFIG_CONFLICT", "配置已被其他程序修改；草稿保留，请重新读取后检查变更") }

    @Synchronized fun apply(token: String): JSONObject = synchronized(app.maintenance) {
        expire()
        if (!app.linux.ready || app.packages.busy || app.terminals.session?.isRunning == true)
            throw ConfigFailure("CONFIG_BUSY", "请先关闭当前终端并等待软件管理完成，再应用配置")
        val selected = plans.remove(token) ?: throw ConfigFailure("CONFIG_EXPIRED", "预览已过期或已使用，请重新预览")
        busy = true
        try {
            if (selected.profile != null) profiles.withSelected(selected.profile.id, selected.profile.revision) { commit(selected) }
            else commit(selected)
        } finally { busy = false; selected.next?.fill(0) }
    }
    private fun commit(selected: Plan): JSONObject {
            stateDirectory.mkdirs()
            FileChannel.open(File(stateDirectory, "claude.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use {
                    val before = nativeFile()
                    checkRevision(before, selected.revision)
                    values(before)
                    val file = target()
                    file.parentFile!!.mkdirs()
                    target() // Recheck after creating the fixed parent.
                    val content = JSONObject().put("exists", before.bytes != null)
                        .put("bytes", before.bytes?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }.orEmpty())
                    val saved = JSONObject().put("afterRevision", revision(selected.next)).put("encrypted", secrets.encrypt(content.toString().toByteArray()))
                    val stream = backup.startWrite()
                    try { stream.write(saved.toString().toByteArray()); backup.finishWrite(stream) }
                    catch (error: Exception) { backup.failWrite(stream); throw error }
                    val temp = File(file.parentFile, ".settings-agentm-${UUID.randomUUID()}.tmp")
                    try {
                        if (selected.next != null) {
                            val descriptor = Os.open(temp.absolutePath, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW, 384)
                            FileOutputStream(descriptor).use { output -> output.write(selected.next); output.fd.sync() }
                        }
                        checkRevision(nativeFile(), selected.revision)
                        if (selected.next == null) { if (before.bytes != null) Files.delete(file.toPath()) }
                        else Os.rename(temp.absolutePath, file.absolutePath)
                        val descriptor = Os.open(file.parentFile!!.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
                        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
                        if (nativeFile().revision != revision(selected.next)) throw ConfigFailure("CONFIG_CONFLICT", "保存后检测到外部修改；未自动回滚，请重新读取配置")
                    } finally { if (temp.exists()) temp.delete() }
                }
            }
            app.logs.add("config", "Claude 配置已保存；未记录配置内容")
            return read().put("busy", false)
    }
    companion object {
        private const val MAX_BYTES = 1024 * 1024
        private fun revision(bytes: ByteArray?): String = bytes?.let { MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { b -> "%02x".format(b) } } ?: "missing"
    }
}
