package dev.agentm.app.files

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.ArrayDeque
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Guest paths, including proot bind mounts. Never interpret a guest symlink as an Android path. */
class UbuntuFiles(rootfs: File, home: File, workspace: File, managed: File) {
    data class Location(val path: String, val file: File)
    private val mounts = linkedMapOf("/opt/agentm" to managed, "/workspace" to workspace, "/root" to home, "/" to rootfs)
    private val virtual = listOf("/dev", "/proc", "/sys", "/.l2s")

    private fun under(path: String, root: String) = path == root || path.startsWith("$root/")
    private fun parts(path: String): List<String> {
        require(path.startsWith('/') && path.length <= 4096 && path.none { it == '\\' || it.isISOControl() }) { "Ubuntu 路径无效" }
        if (path == "/") return emptyList()
        val parts = path.drop(1).split('/')
        require(parts.all { it.isNotEmpty() && it != "." && it != ".." }) { "路径不能包含空段、. 或 .." }
        return parts
    }
    private fun host(path: String): File {
        require(virtual.none { under(path, it) }) { "此目录由 Android 动态提供，不能通过文件管理器访问" }
        val mount = mounts.keys.first { it == "/" || under(path, it) }
        val root = mounts.getValue(mount).absoluteFile
        require(Files.isDirectory(root.toPath(), NOFOLLOW_LINKS) && root.toPath() == root.toPath().toRealPath()) { "Ubuntu 目录未就绪或已被替换：$mount" }
        return File(root, if (mount == "/") path.drop(1) else path.removePrefix(mount).removePrefix("/"))
    }
    fun resolve(path: String, followFinal: Boolean = true): Location {
        var remaining = parts(path)
        var resolved = emptyList<String>()
        var links = 0
        while (remaining.isNotEmpty()) {
            val name = remaining.first(); remaining = remaining.drop(1)
            val candidate = "/" + (resolved + name).joinToString("/")
            val file = host(candidate)
            if (Files.isSymbolicLink(file.toPath()) && (followFinal || remaining.isNotEmpty())) {
                require(++links <= 40) { "符号链接循环或层级过深" }
                val target = Files.readSymbolicLink(file.toPath()).toString()
                require(target.none { it == '\\' || it.isISOControl() }) { "符号链接目标无效" }
                val normalized = (if (target.startsWith('/')) emptyList() else resolved).toMutableList()
                for (part in target.split('/')) when (part) {
                    "", "." -> Unit
                    ".." -> if (normalized.isNotEmpty()) normalized.removeAt(normalized.lastIndex)
                    else -> normalized.add(part)
                }
                remaining = normalized + remaining; resolved = emptyList()
            } else {
                if (remaining.isNotEmpty()) require(Files.isDirectory(file.toPath(), NOFOLLOW_LINKS)) { "目录不存在：$candidate" }
                // Reject a host-side link introduced into an ancestor during resolution.
                val parent = file.parentFile!!.toPath()
                require(parent == parent.toRealPath()) { "目录发生变化，请刷新后重试" }
                resolved = resolved + name
            }
        }
        val result = "/" + resolved.joinToString("/")
        return Location(result, host(result))
    }
    fun directory(path: String): Location = resolve(path).also {
        require(Files.isDirectory(it.file.toPath(), NOFOLLOW_LINKS)) { "目录不存在：$path" }
    }
    fun canWrite(path: String) = !under(path, "/opt/agentm") && virtual.none { under(path, it) }
    private fun mutable(path: String): Location = resolve(path, false).also {
        require(canWrite(it.path) && it.path !in setOf("/", "/root", "/workspace", "/opt")) { "不能修改系统挂载点或受管软件目录" }
    }
    fun child(parent: String, name: String): String {
        require(name.isNotBlank() && name !in listOf(".", "..") && name.toByteArray().size <= 255 && name.none { it == '/' || it == '\\' || it.isISOControl() }) { "名称无效：不能包含斜杠或控制字符，最多 255 字节" }
        return parent.trimEnd('/') + "/" + name
    }
    private fun children(path: String): List<String> {
        val directory = directory(path)
        val names = linkedSetOf<String>()
        val deadline = System.nanoTime() + 3_000_000_000L
        Files.newDirectoryStream(directory.file.toPath()).use { stream ->
            for (entry in stream) {
                check(!Thread.currentThread().isInterrupted && System.nanoTime() < deadline && names.size < 50000) { "目录过大，请打开更具体的目录" }
                names += entry.fileName.toString()
            }
        }
        mounts.keys.filter { it != "/" && it.substringBeforeLast('/').ifEmpty { "/" } == directory.path }.forEach { names += it.substringAfterLast('/') }
        return names.toList()
    }
    private fun entry(path: String): JSONObject {
        val value = JSONObject().put("path", path).put("name", path.substringAfterLast('/'))
        return try {
            val location = resolve(path, false)
            val attrs = Files.readAttributes(location.file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            val target = if (attrs.isSymbolicLink) runCatching { resolve(path) }.getOrNull() else location
            val folder = target != null && Files.isDirectory(target.file.toPath(), NOFOLLOW_LINKS)
            value.put("directory", folder).put("link", attrs.isSymbolicLink).put("bytes", attrs.size()).put("modified", attrs.lastModifiedTime().toMillis())
                .put("readable", target != null && (folder || Files.isRegularFile(target.file.toPath(), NOFOLLOW_LINKS)))
                .put("writable", canWrite(location.path) && location.path !in setOf("/", "/root", "/workspace", "/opt"))
        } catch (_: Exception) {
            value.put("directory", false).put("link", false).put("readable", false).put("writable", false).put("bytes", 0).put("modified", 0)
        }
    }
    fun list(path: String, offset: Int = 0, query: String = ""): JSONObject {
        require(offset in 0..50000 && offset % PAGE_SIZE == 0 && query.length <= 128) { "分页或搜索参数无效" }
        val location = directory(path)
        val entries = mutableListOf<JSONObject>()
        var incomplete = false
        var visited = 0
        val pending = ArrayDeque<String>().apply { add(location.path) }
        val deadline = System.nanoTime() + 5_000_000_000L
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            val names = try { children(current) } catch (e: Exception) {
                if (current == location.path) throw e
                incomplete = true; continue
            }
            for (name in names) {
                if (++visited > 50000 || System.nanoTime() > deadline || Thread.currentThread().isInterrupted) { incomplete = true; break }
                val item = entry(current.trimEnd('/') + "/" + name)
                if (query.isEmpty() || name.contains(query, ignoreCase = true)) entries += item
                if (query.isNotEmpty() && item.getBoolean("directory") && !item.getBoolean("link")) pending.add(item.getString("path"))
            }
            if (visited > 50000 || System.nanoTime() > deadline || Thread.currentThread().isInterrupted || query.isEmpty()) break
        }
        entries.sortWith(compareByDescending<JSONObject> { it.getBoolean("directory") }.thenBy { it.getString("path").lowercase(Locale.ROOT) }.thenBy { it.getString("path") })
        return JSONObject().put("path", location.path).put("parent", if (location.path == "/") JSONObject.NULL else location.path.substringBeforeLast('/').ifEmpty { "/" })
            .put("entries", JSONArray(entries.drop(offset).take(PAGE_SIZE))).put("offset", offset).put("more", offset + PAGE_SIZE < entries.size)
            .put("total", entries.size).put("incomplete", incomplete).put("query", query).put("writable", canWrite(location.path))
    }
    fun create(parent: String, name: String, folder: Boolean): JSONObject {
        val location = mutable(child(directory(parent).path, name))
        if (folder) Files.createDirectory(location.file.toPath()) else Files.createFile(location.file.toPath())
        return JSONObject().put("path", location.path)
    }
    fun move(paths: List<String>, destination: String, name: String? = null): JSONObject {
        val target = directory(destination)
        require(canWrite(target.path)) { "目标目录只读" }
        val sources = selected(paths)
        require(name == null || sources.size == 1)
        val targets = sources.map { mutable(child(target.path, name ?: it.file.name)) }
        require(targets.map { it.path }.distinct().size == targets.size) { "存在同名项目，请分别移动" }
        for ((source, dest) in sources.zip(targets)) {
            require(!under(dest.path, source.path)) { "不能移动到自身或子目录中" }
            require(!Files.exists(dest.file.toPath(), NOFOLLOW_LINKS)) { "目标已存在：${dest.file.name}" }
        }
        return batch(sources.zip(targets)) { (source, dest) ->
            // All private mounts share the app filesystem. No replacement and no shell interpolation.
            Files.move(mutable(source.path).file.toPath(), mutable(dest.path).file.toPath())
        }
    }
    private fun selected(paths: List<String>): List<Location> {
        require(paths.isNotEmpty() && paths.size <= 100) { "每次请选择 1 至 100 项" }
        val locations = paths.distinct().map { mutable(it) }
        require(locations.all { Files.exists(it.file.toPath(), NOFOLLOW_LINKS) }) { "部分项目已不存在，请刷新" }
        return locations.filter { item -> locations.none { other -> item.path != other.path && under(item.path, other.path) } }
    }
    private fun <T> batch(items: List<T>, action: (T) -> Unit): JSONObject {
        var completed = 0
        val errors = JSONArray()
        for (item in items) {
            try { check(!Thread.currentThread().isInterrupted) { "操作已中断" }; action(item); completed++ }
            catch (e: Exception) { errors.put(describeFailure(e)) }
        }
        return JSONObject().put("completed", completed).put("errors", errors)
    }
    fun delete(paths: List<String>): JSONObject = batch(selected(paths)) { location -> deleteTree(location.path) }
    private fun deleteTree(path: String, depth: Int = 0) {
        check(depth < 256 && !Thread.currentThread().isInterrupted) { "目录过深或操作已中断" }
        val location = mutable(path)
        if (Files.isDirectory(location.file.toPath(), NOFOLLOW_LINKS)) {
            for (name in children(location.path)) deleteTree(child(location.path, name), depth + 1)
        }
        Files.delete(mutable(path).file.toPath()) // Delete links themselves, never their targets.
    }
    fun importFile(parent: String, name: String, input: InputStream): JSONObject {
        val location = mutable(child(directory(parent).path, name))
        val temporary = Files.createTempFile(location.file.parentFile!!.toPath(), ".agentm-import-", ".tmp")
        try {
            Files.newOutputStream(temporary).use { copy(input, it) }
            Files.move(temporary, mutable(location.path).file.toPath())
        } finally { Files.deleteIfExists(temporary) }
        return JSONObject().put("path", location.path)
    }
    fun exportName(path: String): String {
        val location = resolve(path)
        val directory = Files.isDirectory(location.file.toPath(), NOFOLLOW_LINKS)
        require(directory || Files.isRegularFile(location.file.toPath(), NOFOLLOW_LINKS)) { "请选择普通文件或文件夹" }
        return (if (location.path == "/") "ubuntu" else location.file.name) + if (directory) ".zip" else ""
    }
    fun exportFile(path: String, output: OutputStream) {
        val location = resolve(path)
        if (Files.isDirectory(location.file.toPath(), NOFOLLOW_LINKS)) {
            var count = 0
            ZipOutputStream(output).use { zip ->
                fun append(guest: String, relative: String, depth: Int) {
                    check(depth < 128 && ++count <= 100000 && !Thread.currentThread().isInterrupted) { "文件夹过大或操作已中断，请分批导出" }
                    if (virtual.any { under(guest, it) }) return
                    val item = resolve(guest, false)
                    val attrs = Files.readAttributes(item.file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                    if (attrs.isSymbolicLink || (!attrs.isDirectory && !attrs.isRegularFile)) return
                    val entry = ZipEntry(relative + if (attrs.isDirectory) "/" else "").apply { time = attrs.lastModifiedTime().toMillis() }
                    zip.putNextEntry(entry)
                    if (attrs.isRegularFile) Files.newInputStream(item.file.toPath(), StandardOpenOption.READ, NOFOLLOW_LINKS).use { copy(it, zip) }
                    zip.closeEntry()
                    if (attrs.isDirectory) for (name in children(item.path)) append(child(item.path, name), "$relative/$name", depth + 1)
                }
                append(location.path, if (location.path == "/") "ubuntu" else location.file.name, 0)
            }
            return
        }
        require(Files.isRegularFile(location.file.toPath(), NOFOLLOW_LINKS)) { "请选择普通文件或文件夹" }
        Files.newInputStream(location.file.toPath(), StandardOpenOption.READ, NOFOLLOW_LINKS).use { copy(it, output) }
    }
    private fun copy(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            check(!Thread.currentThread().isInterrupted) { "文件传输已中断" }
            val count = input.read(buffer); if (count < 0) return
            output.write(buffer, 0, count)
        }
    }
    companion object {
        const val PAGE_SIZE = 100
        fun fromAppFiles(filesDir: File): UbuntuFiles {
            // Context.filesDir can contain Android's /data/user/0 <-> /data/data alias.
            // Resolve only this trusted base, never guest-writable mount directories.
            val privateRoot = filesDir.toPath().toRealPath().toFile()
            return UbuntuFiles(File(privateRoot, "linux/ubuntu"), File(privateRoot, "linux-home"),
                File(privateRoot, "workspaces"), File(privateRoot, "managed"))
        }
        fun describeFailure(error: Exception): String = when (error) {
            is java.nio.file.FileAlreadyExistsException -> "同名文件或文件夹已存在，未覆盖；请先重命名"
            is java.nio.file.NoSuchFileException -> "文件或目录已不存在，请刷新后重试"
            is java.nio.file.AccessDeniedException -> "没有访问该文件或目录的权限"
            is java.nio.file.DirectoryNotEmptyException -> "目录内容发生变化，请刷新后重试"
            is java.io.IOException -> "文件读写失败，请检查可用空间及所选文件是否可访问"
            else -> error.message ?: "文件操作未完成"
        }
    }
}
