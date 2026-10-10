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
}
