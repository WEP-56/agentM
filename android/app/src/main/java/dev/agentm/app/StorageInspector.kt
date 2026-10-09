package dev.agentm.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes

class StorageInspector(private val app: AgentMApplication) {
    private val roots get() = linkedMapOf(
        "workspace" to Triple("工作区", File(app.filesDir.canonicalFile, "workspaces"), "/workspace"),
        "home" to Triple("Linux home", File(app.filesDir.canonicalFile, "linux-home"), "/root"),
        "rootfs" to Triple("Ubuntu rootfs", File(app.filesDir.canonicalFile, "linux/ubuntu"), "/"),
        "managed" to Triple("受管软件", File(app.filesDir.canonicalFile, "managed"), "/opt/agentm"),
        "cache" to Triple("应用缓存", File(app.dataDir.canonicalFile, "cache"), "宿主缓存，不对应单一 Linux 路径")
    )
    fun usage(): JSONObject {
        val result = JSONArray()
        for ((id, value) in roots) {
            var bytes = 0L; var count = 0; var incomplete = false
            val deadline = System.nanoTime() + 1_000_000_000L
            val pending = java.util.ArrayDeque<File>().apply { add(value.second) }
            try {
                while (pending.isNotEmpty()) {
                    if (Thread.currentThread().isInterrupted || System.nanoTime() > deadline || count >= 50000) { incomplete = true; break }
                    val file = pending.removeFirst()
                    val relative = value.second.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
                    // Recheck every directory before opening it; never traverse links.
                    StoragePaths.resolve(value.second, relative)
                    if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) continue
                    val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                    count++
                    if (attrs.isRegularFile) bytes += attrs.size()
                    if (attrs.isDirectory) Files.newDirectoryStream(file.toPath()).use { entries ->
                        for (entry in entries) {
                            if (pending.size + count >= 50000 || System.nanoTime() > deadline) { incomplete = true; break }
                            if (!Files.isSymbolicLink(entry)) pending.add(entry.toFile())
                        }
                    }
                }
            } catch (_: Exception) { incomplete = true }
            result.put(JSONObject().put("id", id).put("name", value.first).put("hostPath", value.second.absolutePath)
                .put("guestPath", value.third).put("bytes", bytes).put("entries", count).put("incomplete", incomplete))
        }
        return JSONObject().put("roots", result).put("checkedAt", System.currentTimeMillis())
    }
    fun list(id: String, relative: String, offset: Int): JSONObject {
        val root = roots[id]?.second ?: error("未知浏览目录")
        val dir = StoragePaths.resolve(root, relative)
        require(offset in 0..50000) { "分页范围无效" }
        val entries = JSONArray()
        var skipped = 0; var more = false
        if (dir.exists()) {
            require(dir.isDirectory) { "只能浏览目录" }
            Files.newDirectoryStream(dir.toPath()).use { stream ->
                for (path in stream) {
                    if (skipped++ < offset) continue
                    if (entries.length() >= 100) { more = true; break }
                    val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                    entries.put(JSONObject().put("name", path.fileName.toString()).put("directory", attrs.isDirectory)
                        .put("link", attrs.isSymbolicLink).put("bytes", if (attrs.isRegularFile) attrs.size() else 0))
                }
            }
        }
        return JSONObject().put("root", id).put("path", relative).put("entries", entries).put("offset", offset).put("more", more)
    }
}
