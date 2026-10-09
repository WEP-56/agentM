package dev.agentm.app.config

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** Fixed paths and an encrypted undo journal make Pi's two files and the catalog recoverable together. */
class ProviderFiles(
    private val home: File,
    private val state: File,
    private val encrypt: (ByteArray) -> ByteArray,
    private val decrypt: (ByteArray) -> ByteArray,
    private val beforeWrite: (String) -> Unit = {},
    private val syncDirectory: (File) -> Unit = {},
) {
    private fun safe(base: File, path: String): File {
        var file = base
        require(!Files.isSymbolicLink(base.toPath())) { "配置目录不能是符号链接" }
        for (part in path.split('/')) {
            file = File(file, part)
            require(!Files.isSymbolicLink(file.toPath())) { "配置路径不能包含符号链接" }
        }
        require(file.canonicalFile == File(base.canonicalFile, path)) { "配置路径越界" }
        return file
    }
    private fun target(key: String) = when (key) {
        "claude" -> safe(home, ".claude/settings.json")
        "piModels" -> safe(home, ".pi/agent/models.json")
        "piSettings" -> safe(home, ".pi/agent/settings.json")
        "opencodeLegacy" -> safe(home, ".config/opencode/config.json")
        "opencodeJson" -> safe(home, ".config/opencode/opencode.json")
        "opencodeJsonc" -> safe(home, ".config/opencode/opencode.jsonc")
        "codexConfig" -> safe(home, ".codex/config.toml")
        "codexAuth" -> safe(home, ".codex/auth.json")
        "codexCatalog" -> safe(home, ".codex/agentm-model-catalog.json")
        "library" -> safe(state, "providers-v1.json")
        else -> error("未知配置文件")
    }
    fun read(key: String): ByteArray? = readFile(target(key))
    private fun readFile(file: File): ByteArray? {
        if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) return null
        require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.length() <= 8 * 1024 * 1024) { "配置文件类型或大小无效" }
        return Files.newInputStream(file.toPath(), NOFOLLOW_LINKS).use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break; require(out.size() + n <= 8 * 1024 * 1024); out.write(buffer, 0, n) }
            out.toByteArray()
        }
    }
    fun text(key: String): String = read(key)?.let { bytes ->
        Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } ?: "{\n}\n"
    private fun writeFile(file: File, bytes: ByteArray?) {
        file.parentFile!!.mkdirs()
        require(!Files.isSymbolicLink(file.parentFile!!.toPath()) && !Files.isSymbolicLink(file.toPath()))
        if (bytes == null) { Files.deleteIfExists(file.toPath()); syncDirectory(file.parentFile!!); return }
        val temporary = File(file.parentFile, ".agentm-provider-${UUID.randomUUID()}.tmp")
        try {
            Files.newByteChannel(temporary.toPath(), setOf<java.nio.file.OpenOption>(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, NOFOLLOW_LINKS)).use { channel ->
                temporary.setReadable(false, false); temporary.setWritable(false, false)
                temporary.setReadable(true, true); temporary.setWritable(true, true)
                val buffer = java.nio.ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
            }
            FileOutputStream(temporary, true).use { it.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            syncDirectory(file.parentFile!!)
        } finally { temporary.delete() }
    }
    fun readLibrary(): JSONObject? = read("library")?.let { encrypted ->
        val clear = decrypt(encrypted)
        try { JSONObject(clear.toString(Charsets.UTF_8)) } finally { clear.fill(0) }
    }
    fun encodeLibrary(value: JSONObject): ByteArray {
        val clear = value.toString().toByteArray()
        return try { encrypt(clear) } finally { clear.fill(0) }
    }
    fun revision(keys: List<String>): String = digest(keys.joinToString("|") { "$it:${digest(read(it))}" }.toByteArray())
    private fun contents(row: JSONObject, side: String): ByteArray? = if (row.isNull(side)) null else Base64.getDecoder().decode(row.getString(side))
    fun recover() {
        val journal = safe(state, "providers-transaction.json")
        val bytes = readFile(journal) ?: return
        val clear = decrypt(bytes)
        val rows = try { JSONObject(clear.toString(Charsets.UTF_8)) } finally { clear.fill(0) }
        val keys = rows.keys().asSequence().toList()
        val current = keys.associateWith { read(it) }
        require(keys.all { key -> current[key].contentEquals(contents(rows.getJSONObject(key), "before")) || current[key].contentEquals(contents(rows.getJSONObject(key), "after")) }) {
            "上次配置写入中断后文件又被外部修改；已保留文件与备份，请先处理冲突"
        }
        if (!keys.all { current[it].contentEquals(contents(rows.getJSONObject(it), "after")) }) {
            for (key in keys.reversed()) writeFile(target(key), contents(rows.getJSONObject(key), "before"))
        }
        Files.delete(journal.toPath())
    }
    fun commit(expected: Map<String, String>, changes: Map<String, ByteArray?>) {
        recover()
        require(changes.keys.all { it in expected })
        val before = changes.keys.associateWith { read(it) }
        for ((key, revision) in expected) check(digest(read(key)) == revision) { "配置或提供商列表已被修改，请重新读取后再保存" }
        val rows = JSONObject()
        for ((key, value) in changes) rows.put(key, JSONObject()
            .put("before", before[key]?.let { Base64.getEncoder().encodeToString(it) } ?: JSONObject.NULL)
            .put("after", value?.let { Base64.getEncoder().encodeToString(it) } ?: JSONObject.NULL))
        val encrypted = encodeLibrary(rows)
        require(encrypted.size <= 8 * 1024 * 1024) { "提供商配置总量过大，请精简后重试" }
        writeFile(safe(state, "providers-backup.json"), encrypted)
        if (changes.keys.any { it != "library" }) writeFile(safe(state, "providers-native-backup.json"), encrypted)
        val journal = safe(state, "providers-transaction.json")
        writeFile(journal, encrypted)
        try {
            for ((key, bytes) in changes) {
                beforeWrite(key)
                check(digest(read(key)) == expected.getValue(key)) { "配置在保存期间被修改，未覆盖新内容" }
                writeFile(target(key), bytes)
            }
            Files.delete(journal.toPath())
        } catch (failure: Exception) {
            runCatching { recover() }.exceptionOrNull()?.let { throw IllegalStateException("保存中断且发生外部修改，已保留恢复记录", it) }
            throw failure
        }
    }
    companion object {
        fun digest(bytes: ByteArray?): String = bytes?.let { MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { b -> "%02x".format(b) } } ?: "missing"
    }
}
