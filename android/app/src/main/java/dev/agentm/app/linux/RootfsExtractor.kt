package dev.agentm.app.linux

import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.zip.GZIPInputStream

class RootfsExtractor {
    fun extract(archive: File, root: File, cancelled: () -> Boolean, progress: (Long) -> Unit) {
        root.mkdirs()
        val hardLinks = mutableListOf<Pair<File, String>>()
        var bytes = 0L
        var entries = 0
        TarArchiveInputStream(GZIPInputStream(archive.inputStream().buffered(65536))).use { tar ->
            while (true) {
                if (cancelled()) throw InterruptedException("安装已取消")
                val entry = tar.nextEntry ?: break
                if (++entries > 100000) throw IOException("归档文件数量超出限制")
                val destination = ArchivePaths.resolve(root, entry.name)
                if (destination == root) continue
                if (Files.isSymbolicLink(destination.toPath())) throw IOException("归档重复写入符号链接")
                if (entry.isDirectory) {
                    if (!destination.isDirectory && !destination.mkdirs()) throw IOException("无法创建系统目录")
                } else {
                    destination.parentFile!!.mkdirs()
                    when {
                        entry.isSymbolicLink -> {
                            // Absolute targets are guest paths. They may exist as links, but the resolver
                            // refuses subsequent host writes through any link that escapes this staging root.
                            if (entry.linkName.contains('\u0000')) throw IOException("无效链接")
                            Os.symlink(entry.linkName, destination.absolutePath)
                        }
                        entry.isLink -> hardLinks.add(destination to entry.linkName)
                        entry.isFile -> {
                            if (entry.size < 0 || bytes + entry.size > 1024L * 1024 * 1024) throw IOException("根文件系统超过解压上限")
                            FileOutputStream(destination).use { output ->
                                val buffer = ByteArray(65536)
                                while (true) {
                                    if (cancelled()) throw InterruptedException("安装已取消")
                                    val count = tar.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                    bytes += count
                                }
                            }
                            Os.chmod(destination.absolutePath, entry.mode and 511)
                        }
                        // /dev is supplied by the host at execution time, not created from archive devices.
                        entry.isCharacterDevice || entry.isBlockDevice || entry.isFIFO -> Unit
                        else -> throw IOException("不支持的归档条目")
                    }
                }
                if (entries % 100 == 0) progress(bytes)
            }
        }
        for ((destination, sourceName) in hardLinks) {
            val source = ArchivePaths.resolve(root, sourceName)
            if (!source.isFile || Files.isSymbolicLink(source.toPath())) throw IOException("无效硬链接目标")
            runCatching { Os.link(source.absolutePath, destination.absolutePath) }.getOrElse {
                source.copyTo(destination)
                Os.chmod(destination.absolutePath, Os.stat(source.absolutePath).st_mode and 511)
            }
        }
        progress(bytes)
    }
}
