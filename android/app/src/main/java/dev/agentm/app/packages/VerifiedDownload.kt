package dev.agentm.app.packages

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Only the APK's pinned catalog supplies URLs and digests; callers cannot execute a URL. */
class VerifiedDownload(private val cache: File) {
    fun fetch(asset: JSONObject, progress: (Long, Long) -> Unit): File {
        cache.mkdirs()
        val sha = asset.getString("sha256")
        require(sha.matches(Regex("[a-f0-9]{64}")))
        val expected = asset.getLong("bytes")
        val archive = File(cache, "$sha.tar.gz")
        val partial = File(cache, "$sha.part")
        if (!archive.exists()) {
            var offset = partial.takeIf { it.exists() }?.length() ?: 0
            if (offset > expected) { check(partial.delete()); offset = 0 }
            if (offset < expected) {
                val url = URL(asset.getString("url"))
                require(url.protocol == "https")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 20000; connection.readTimeout = 20000
                connection.setRequestProperty("Accept-Encoding", "identity")
                if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
                try {
                    val code = connection.responseCode
                    if (code !in listOf(200, 206) || connection.url.protocol != "https") throw IOException("下载返回 HTTP $code")
                    if (code == 206 && connection.getHeaderField("Content-Range") != "bytes $offset-${expected - 1}/$expected") throw IOException("续传范围不匹配")
                    if (code == 200) offset = 0
                    FileOutputStream(partial, offset > 0).use { output -> connection.inputStream.use { input ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            if (Thread.currentThread().isInterrupted) throw InterruptedException()
                            val count = input.read(buffer); if (count < 0) break
                            if (offset + count > expected) throw IOException("下载大小超过固定清单")
                            output.write(buffer, 0, count); offset += count; progress(offset, expected)
                        }
                        output.fd.sync()
                    } }
                    if (offset != expected) throw IOException("下载不完整，可重试续传")
                } finally { connection.disconnect() }
            }
        }
        val file = if (archive.exists()) archive else partial
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val count = input.read(buffer); if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        if (file.length() != expected || digest.digest().joinToString("") { "%02x".format(it) } != sha) {
            file.delete(); throw IOException("软件包 SHA-256 校验失败，请重新下载")
        }
        if (file == partial && !partial.renameTo(archive)) throw IOException("无法保存下载缓存")
        progress(expected, expected)
        return archive
    }
}
