package dev.agentm.app.linux

import java.io.File
import java.io.IOException

/** Tar entry names are POSIX paths. Never interpret host drives or follow a named output symlink. */
object ArchivePaths {
    fun resolve(root: File, entry: String): File {
        val relative = entry.removePrefix("./").trimEnd('/')
        if (relative.isEmpty() || relative == ".") return root
        // Debian multiarch metadata legitimately uses names such as libc6:amd64.list.
        // Reject drive-qualified roots, not colons in ordinary Linux filename components.
        if (relative.startsWith('/') || relative.contains('\\') || relative.matches(Regex("^[A-Za-z]:.*")) ||
            relative.split('/').any { it == ".." } || relative.contains('\u0000')) throw IOException("归档包含不安全路径：${entry.take(160)}")
        val target = File(root, relative)
        val base = root.canonicalPath + File.separator
        if (!target.canonicalPath.startsWith(base)) throw IOException("归档路径越界")
        return target
    }
}
