package dev.agentm.app.packages

import org.json.JSONObject
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Exact npm origin, bounded metadata, no registry-provided install scripts. */
class PackageUpdates(private val read: (String) -> JSONObject = { readRegistry(it) }) {
    companion object {
        private fun readRegistry(path: String): JSONObject {
            val connection = URL("https://registry.npmjs.org/$path").openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15000; connection.readTimeout = 15000
            try {
                if (connection.responseCode != 200) throw IOException("更新源返回 HTTP ${connection.responseCode}")
                val bytes = connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        val count = input.read(buffer); if (count < 0) break
                        check(output.size() + count <= 1024 * 1024) { "更新元数据过大" }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                return JSONObject(String(bytes, Charsets.UTF_8))
            } finally { connection.disconnect() }
        }
    }

    fun check(kind: String, pinned: JSONObject): JSONObject {
        val upstream = when (kind) {
            "claude" -> "@anthropic-ai/claude-code"
            "codex" -> "@openai/codex"
            "opencode" -> "opencode-ai"
            "pi" -> "@earendil-works/pi-coding-agent"
            "dsh" -> "@deepseek-ai/dsh"
            else -> error("未知 Agent")
        }
        val latest = read("$upstream/latest").getString("version")
        require(ManagedPackagePaths.validVersion(latest)) { "上游版本格式无效" }
        val version = pinned.getString("version")
        val result = JSONObject().put("status", "checked").put("upstreamVersion", latest)
            .put("checkedAt", System.currentTimeMillis()).put("supportedVersion", version)
            .put("source", "https://registry.npmjs.org/$upstream/latest")
            .put("policy", if (kind in setOf("pi", "dsh")) "固定依赖与适配组件；新版本需随 agentM 适配发布" else "仅接受 ${version.substringBeforeLast('.')} 系列补丁；校验原生组件并运行自检后发布")
        if (UpdatePolicy.accepts(kind, version, latest) && UpdatePolicy.newer(latest, version)) {
            val packageName = pinned.getString("package")
            val packageVersion = if (kind == "codex") latest + pinned.getString("packageVersion").removePrefix(version) else latest
            val metadata = read("$packageName/$packageVersion")
            require(metadata.getString("name") == packageName && metadata.getString("version") == packageVersion) { "上游包身份不匹配" }
            val dist = metadata.getJSONObject("dist")
            val url = URL(dist.getString("tarball"))
            require(url.protocol == "https" && url.host == "registry.npmjs.org" && url.port == -1 && url.userInfo == null && url.query == null && url.ref == null) { "更新包来源无效" }
            val integrity = dist.getString("integrity")
            require(integrity.matches(Regex("sha512-[A-Za-z0-9+/]{86}=="))) { "更新包缺少 SHA-512 校验" }
            val candidate = JSONObject(pinned.toString()).put("version", latest).put("url", url.toString()).put("integrity", integrity)
                .put("recipe", "native-patch-v1").put("packageVersion", packageVersion)
            candidate.remove("sha256"); candidate.remove("bytes")
            result.put("candidate", candidate).put("supportedVersion", latest)
        }
        return result
    }
}
