package dev.agentm.app

import java.io.File
import java.nio.file.Files

object StoragePaths {
    fun resolve(root: File, relative: String): File {
        require(relative.length <= 2048 && !relative.contains('\u0000') && !relative.contains('\\')) { "路径无效" }
        val parts = if (relative.isEmpty()) emptyList() else relative.split('/')
        require(parts.none { it.isEmpty() || it == "." || it == ".." }) { "路径不能越界" }
        require(!Files.isSymbolicLink(root.toPath()) && root.canonicalFile == root.absoluteFile) { "根目录不能是链接" }
        var current = root
        for (part in parts) {
            current = File(current, part)
            require(!Files.isSymbolicLink(current.toPath())) { "只读浏览不进入符号链接" }
        }
        require(current.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) { "路径不能越界" }
        return current
    }
}
