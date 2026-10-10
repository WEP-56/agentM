package dev.agentm.app

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Public stable releases only. Version identity comes from tag_name, never the release title. */
class AppUpdates(private val readLatest: () -> JSONObject? = { fetchLatest() }) {
    fun check(installed: String): JSONObject {
        val current = requireNotNull(Version.parse(installed)) { "当前应用版本格式无效" }
        val result = JSONObject().put("currentVersion", installed).put("checkedAt", System.currentTimeMillis())
        val release = readLatest() ?: return result.put("found", false).put("updateAvailable", false)
        require(!release.getBoolean("draft") && !release.getBoolean("prerelease")) { "更新源没有返回正式发布版本" }
        val tag = release.getString("tag_name")
        require(stableTag.matches(tag)) { "Release tag 格式无效，请到 GitHub 发布页查看" }
        val latest = requireNotNull(Version.parse(tag.removePrefix("v"))) { "Release 版本号无效" }
        return result.put("found", true).put("updateAvailable", latest > current)
            .put("latestVersion", tag.removePrefix("v")).put("tag", tag)
            .put("releaseUrl", projectUrl("release", tag))
            .put("notes", if (release.isNull("body")) "" else release.optString("body").take(12000))
    }

    private data class Version(val numbers: List<Long>, val preview: String, val revision: Long) : Comparable<Version> {
        override fun compareTo(other: Version): Int {
            for (i in numbers.indices) if (numbers[i] != other.numbers[i]) return numbers[i].compareTo(other.numbers[i])
            val stages = listOf("alpha", "beta", "rc", "")
            val stage = stages.indexOf(preview).compareTo(stages.indexOf(other.preview))
            return if (stage != 0) stage else revision.compareTo(other.revision)
        }
        companion object {
            fun parse(value: String): Version? {
                if (value.length > 80) return null
                val match = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-(alpha|beta|rc)\\.([1-9][0-9]*))?").matchEntire(value) ?: return null
                val numbers = (1..3).map { match.groupValues[it].toLongOrNull() ?: return null }
                val preview = match.groupValues[4]
                return Version(numbers, preview, if (preview.isEmpty()) 0 else match.groupValues[5].toLongOrNull() ?: return null)
            }
        }
    }

    companion object {
        const val REPOSITORY = "https://github.com/WEP-56/agentM"
        const val LATEST_API = "https://api.github.com/repos/WEP-56/agentM/releases/latest"
        private val stableTag = Regex("v(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")

        fun projectUrl(page: String, tag: String = ""): String = when (page) {
            "repository" -> REPOSITORY
            "releases" -> "$REPOSITORY/releases/latest"
            "release" -> {
                require(stableTag.matches(tag) && Version.parse(tag.removePrefix("v")) != null) { "发布版本无效" }
                "$REPOSITORY/releases/tag/$tag"
            }
            else -> throw IllegalArgumentException("未知项目页面")
        }

        internal fun releaseFromRedirect(location: String): JSONObject {
            val url = URL(URL("$REPOSITORY/releases/latest"), location).toString()
            val tag = url.removePrefix("$REPOSITORY/releases/tag/")
            require(url == projectUrl("release", tag)) { "GitHub 发布页跳转无效" }
            return JSONObject().put("tag_name", tag).put("draft", false).put("prerelease", false).put("body", "")
        }

        // The public latest-release redirect does not consume REST API quota.
        private fun fetchLatestRedirect(deadline: Long): JSONObject? {
            val connection = URL("$REPOSITORY/releases/latest").openConnection() as HttpURLConnection
            val remaining = ((deadline - System.nanoTime()) / 1_000_000).coerceAtMost(8000).toInt()
            if (remaining <= 0) throw IOException("更新检查超时，请稍后重试")
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = remaining; connection.readTimeout = remaining
            connection.setRequestProperty("User-Agent", "agentM-Update-Check")
            try {
                return when (connection.responseCode) {
                    301, 302, 303, 307, 308 -> releaseFromRedirect(connection.getHeaderField("Location") ?: throw IOException("GitHub 没有返回发布版本"))
                    404 -> null
                    else -> throw IOException("GitHub 暂时限制更新检查，请稍后重试或直接查看发布页")
                }
            } finally { connection.disconnect() }
        }

        private fun fetchLatest(): JSONObject? {
            val connection = URL(LATEST_API).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "agentM-Update-Check")
            val deadline = System.nanoTime() + 20_000_000_000L
            try {
                when (val code = connection.responseCode) {
                    404 -> return null
                    403, 429 -> return fetchLatestRedirect(deadline)
                    200 -> Unit
                    else -> throw IOException("GitHub 更新检查失败（HTTP $code），请稍后重试")
                }
                val bytes = connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        if (Thread.currentThread().isInterrupted || System.nanoTime() > deadline) throw IOException("更新检查超时，请稍后重试")
                        val count = input.read(buffer); if (count < 0) break
                        require(output.size() + count <= 512 * 1024) { "发布信息过大，请直接查看 GitHub 发布页" }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                return JSONObject(String(bytes, Charsets.UTF_8))
            } catch (e: java.net.SocketTimeoutException) {
                throw IOException("连接 GitHub 超时，请检查网络后重试", e)
            } catch (e: java.net.UnknownHostException) {
                throw IOException("无法连接 GitHub，请检查网络后重试", e)
            } finally { connection.disconnect() }
        }
    }
}
