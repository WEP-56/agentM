package dev.agentm.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** Persistent launch preference over the two existing Linux project mounts. No shell/path interpolation. */
class WorkingDirectories(
    workspace: File,
    home: File,
    private val readSaved: () -> String?,
    private val writeSaved: (String) -> Unit,
) {
    data class Directory(val path: String, val host: File)
    private val roots = linkedMapOf(DEFAULT to workspace, "/root" to home)
    private fun validatePath(path: String): Pair<String, File> {
        require(path.length <= 2048 && path.none(Char::isISOControl)) { "工作目录路径无效" }
        val root = roots.keys.firstOrNull { path == it || path.startsWith("$it/") } ?: error("请选择工作区或 Linux home 内的目录")
        require(path == root || !path.endsWith('/')) { "工作目录路径无效" }
        return root to StoragePaths.resolve(roots.getValue(root), path.removePrefix(root).removePrefix("/"))
    }
    fun resolve(path: String): Directory {
        val (_, host) = validatePath(path)
        require(Files.isDirectory(host.toPath(), NOFOLLOW_LINKS) && Files.isReadable(host.toPath()) && Files.isExecutable(host.toPath())) { "工作目录不存在或不可访问，请重新选择：$path" }
        return Directory(path, host)
    }
    private fun prepareRoots() {
        for (root in roots.keys) {
            val host = validatePath(root).second
            if (!Files.exists(host.toPath(), NOFOLLOW_LINKS)) Files.createDirectories(host.toPath())
        }
    }
    @Synchronized fun current(): Directory {
        val path = readSaved() ?: DEFAULT
        if (path == DEFAULT) prepareRoots()
        return resolve(path)
    }
    @Synchronized fun snapshot(): JSONObject {
        val path = readSaved() ?: DEFAULT
        val failure = runCatching { resolve(path) }.exceptionOrNull()
        return JSONObject().put("path", path).put("available", failure == null).put("error", failure?.message ?: JSONObject.NULL)
    }
    @Synchronized fun select(path: String): JSONObject {
        val directory = resolve(path)
        writeSaved(directory.path)
        return snapshot()
    }
    fun list(path: String, offset: Int = 0): JSONObject {
        require(offset in 0..50000 && offset % PAGE_SIZE == 0) { "目录分页无效" }
        prepareRoots()
        val directory = resolve(path)
        val names = mutableListOf<String>()
        val deadline = System.nanoTime() + 2_000_000_000L
        var count = 0
        Files.newDirectoryStream(directory.host.toPath()).use { stream ->
            for (entry in stream) {
                check(!Thread.currentThread().isInterrupted && System.nanoTime() < deadline && count++ < 50000) { "目录项目过多，请选择更具体的路径" }
                val name = entry.fileName.toString()
                if (Files.isDirectory(entry, NOFOLLOW_LINKS) && validName(name)) names += name
            }
        }
        names.sortWith(compareBy<String> { it.lowercase() }.thenBy { it })
        val root = roots.keys.first { path == it || path.startsWith("$it/") }
        return JSONObject().put("path", path).put("parent", if (path == root) JSONObject.NULL else path.substringBeforeLast('/'))
            .put("roots", JSONArray(roots.keys.map { JSONObject().put("path", it).put("name", if (it == DEFAULT) "工作区" else "Linux home") }))
            .put("entries", JSONArray(names.drop(offset).take(PAGE_SIZE)))
            .put("offset", offset).put("more", offset + PAGE_SIZE < names.size)
    }
    fun create(parent: String, name: String): JSONObject {
        require(validName(name)) { "目录名不能为空、点、斜杠或控制字符，且不能超过 255 字节" }
        resolve(parent)
        val path = "$parent/$name"
        val host = validatePath(path).second
        require(!Files.exists(host.toPath(), NOFOLLOW_LINKS)) { "同名目录或文件已存在" }
        Files.createDirectory(host.toPath())
        return list(path)
    }
    companion object {
        const val DEFAULT = "/workspace"
        const val PAGE_SIZE = 50
        private fun validName(name: String) = name.isNotBlank() && name !in listOf(".", "..") &&
            name.toByteArray(Charsets.UTF_8).size <= 255 && name.none { it == '/' || it == '\\' || it.isISOControl() }
    }
}
