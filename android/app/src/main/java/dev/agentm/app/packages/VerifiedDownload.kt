package dev.agentm.app.packages

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** APK recipes or validated registry metadata supply digests; the bridge cannot supply URLs. */
class VerifiedDownload(private val cache: File, private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
    fun fetch(asset: JSONObject, progress: (Long, Long) -> Unit): File {
        if (asset.optString("recipe") == "native-patch-v1") return fetchPatch(asset, progress)
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
                val connection = connect(url)
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

    private fun fetchPatch(asset: JSONObject, progress: (Long, Long) -> Unit): File {
        val integrity = asset.getString("integrity")
        require(integrity.matches(Regex("sha512-[A-Za-z0-9+/]{86}==")))
        val expected = java.util.Base64.getDecoder().decode(integrity.removePrefix("sha512-"))
        val url = URL(asset.getString("url"))
        require(url.protocol == "https" && url.host == "registry.npmjs.org" && url.port == -1 && url.userInfo == null)
        cache.mkdirs()
        val connection = connect(url)
        val file = File.createTempFile("patch-", ".tgz", cache)
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 20000; connection.readTimeout = 20000
        val sha512 = MessageDigest.getInstance("SHA-512")
        val sha256 = MessageDigest.getInstance("SHA-256")
        try {
            if (connection.responseCode != 200) throw IOException("更新包返回 HTTP ${connection.responseCode}")
            val total = connection.contentLengthLong.coerceAtLeast(0)
            require(total <= 512L * 1024 * 1024) { "更新包过大" }
            var count = 0L
            FileOutputStream(file).use { output -> connection.inputStream.use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val size = input.read(buffer); if (size < 0) break
                    count += size
                    check(count <= 512L * 1024 * 1024) { "更新包过大" }
                    sha512.update(buffer, 0, size); sha256.update(buffer, 0, size)
                    output.write(buffer, 0, size); progress(count, total)
                }
                output.fd.sync()
            } }
            check(MessageDigest.isEqual(sha512.digest(), expected)) { "更新包 SHA-512 校验失败" }
            val sha = sha256.digest().joinToString("") { "%02x".format(it) }
            asset.put("sha256", sha).put("bytes", count)
            val archive = File(cache, "$sha.tar.gz")
            check(file.renameTo(archive)) { "无法保存更新包" }
            return archive
        } finally { connection.disconnect(); file.delete() }
    }
}
