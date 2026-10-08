package dev.agentm.app.linux

import android.os.Build
import android.os.StatFs
import android.util.AtomicFile
import dev.agentm.app.AgentMApplication
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class InstallState(
    val phase: String = "notInstalled", val operationId: String = "", val message: String = "尚未安装 Ubuntu",
    val downloaded: Long = 0, val total: Long = 0, val extracted: Long = 0,
    val error: String? = null, val probeOutput: String = "", val checkedAt: Long = 0, val imageSha: String = "",
) {
    fun json(): JSONObject = JSONObject().put("phase", phase).put("operationId", operationId).put("message", message)
        .put("downloadedBytes", downloaded).put("totalBytes", total).put("extractedBytes", extracted)
        .put("error", error ?: JSONObject.NULL).put("probeOutput", probeOutput).put("checkedAt", checkedAt).put("imageSha", imageSha)
}

class LinuxManager(private val app: AgentMApplication) {
    val runtime = LinuxRuntime(app)
    private val journal = AtomicFile(File(app.filesDir, "linux-install.json"))
    private val catalog = JSONObject(app.assets.open("linux-catalog.json").bufferedReader().use { it.readText() })
    val abi: String? = Build.SUPPORTED_ABIS.firstOrNull()?.takeIf { it == "x86_64" || it == "arm64-v8a" }
    private val image: JSONObject? get() = abi?.let { catalog.getJSONObject("rootfs").optJSONObject(it) }
    private val cancelled = AtomicBoolean(false)
    private val executing = AtomicBoolean(false)
    @Volatile private var state = restore()
    val busy get() = state.phase in setOf("queued", "downloading", "verifying", "extracting", "configuring", "checking", "committing")
    val ready get() = state.phase == "ready" && state.imageSha == image?.optString("sha256") &&
        File(runtime.rootfs, "bin/bash").exists() && runtime.proot.isFile

    private fun restore(): InstallState = runCatching {
        val json = JSONObject(journal.openRead().bufferedReader().use { it.readText() })
        val old = json.optString("phase", "notInstalled")
        InstallState(phase = if (old in setOf("ready", "failed", "cancelled", "notInstalled", "interrupted")) old else "interrupted",
            operationId = json.optString("operationId"), message = if (old == "ready") "Ubuntu 已安装" else "上次操作未完成，可重新检查或重试",
            imageSha = json.optString("imageSha"), probeOutput = json.optString("probeOutput"), checkedAt = json.optLong("checkedAt"))
    }.getOrDefault(InstallState())

    @Synchronized private fun update(value: InstallState) {
        val stream = journal.startWrite()
        try { stream.write(value.json().toString().toByteArray()); journal.finishWrite(stream); state = value }
        catch (e: Exception) { journal.failWrite(stream); throw e }
    }

    fun snapshot(): JSONObject = state.json().put("linuxReady", ready).put("busy", busy)
        .put("supported", image != null).put("distribution", "Ubuntu 24.04.5")
        .put("engineVersion", catalog.getString("engineVersion")).put("architecture", image?.optString("arch") ?: "unsupported")
        .put("downloadSize", image?.optLong("bytes") ?: 0).put("canCancel", busy && state.phase != "committing")

    @Synchronized fun enqueue(): String {
        if (busy) return state.operationId
        if (ready) throw IllegalStateException("Ubuntu 已安装，无需重复安装")
        require(image != null) { "暂不支持该设备架构" }
        require(runtime.proot.isFile) { "APK 缺少执行引擎" }
        require(!runtime.rootfs.exists()) { "发现已有系统，请先重新检查；未覆盖现有文件" }
        if (StatFs(app.filesDir.absolutePath).availableBytes < 768L * 1024 * 1024) throw IOException("至少需要 768 MiB 可用空间")
        val id = UUID.randomUUID().toString()
        cancelled.set(false)
        update(InstallState("queued", id, "准备安装 Ubuntu", total = image!!.getLong("bytes"), imageSha = image!!.getString("sha256")))
        return id
    }

    fun failStart() { if (state.phase == "queued") update(state.copy(phase = "failed", message = "安装服务未能启动", error = "请重试")) }
    fun serviceFailure(error: Exception) {
        val failed = state.copy(phase = "failed", message = "安装服务未完成", error = error.message?.take(2048) ?: error.javaClass.simpleName)
        // If storage cannot persist the error, still surface it without crashing the Application.
        runCatching { update(failed) }.onFailure { state = failed }
    }
    fun cancel() { if (busy && state.phase != "committing") cancelled.set(true) }
    private fun cancellationPoint() { if (cancelled.get() || Thread.currentThread().isInterrupted) throw InterruptedException("安装已取消") }

    fun install(onProgress: (InstallState) -> Unit) {
        if (!executing.compareAndSet(false, true)) return
        val id = state.operationId
        val stage = File(runtime.base, "staging-$id")
        var lastUpdate = 0L
        fun publish(next: InstallState, force: Boolean = true) {
            val now = System.currentTimeMillis()
            if (!force && now - lastUpdate < 300) return
            update(next); lastUpdate = now; onProgress(next)
        }
        try {
            if (state.phase != "queued") return
            val definition = image ?: throw IOException("设备架构不支持")
            val cache = File(app.cacheDir, "linux-downloads").apply { mkdirs() }
            val archive = File(cache, "${definition.getString("sha256")}.tar.gz")
            val partial = File(cache, "${definition.getString("sha256")}.part")
            if (!archive.exists()) {
                publish(state.copy(phase = "downloading", message = "下载 Ubuntu 基础系统"))
                download(definition, partial) { downloaded -> publish(state.copy(downloaded = downloaded), false) }
                cancellationPoint()
                publish(state.copy(phase = "verifying", message = "校验镜像 SHA-256", downloaded = partial.length()))
                verify(partial, definition.getString("sha256"))
                if (!partial.renameTo(archive)) throw IOException("无法保存镜像缓存")
            } else {
                publish(state.copy(phase = "verifying", message = "校验已下载镜像", downloaded = archive.length()))
                verify(archive, definition.getString("sha256"))
            }
            cancellationPoint()
            runtime.base.mkdirs()
            // Only staging trees owned by previous installer operations are disposable.
            runtime.base.listFiles()?.filter { it.name.matches(Regex("staging-[a-f0-9-]{36}")) }?.forEach { deleteStage(it) }
            publish(state.copy(phase = "extracting", message = "解压 Ubuntu 根文件系统", extracted = 0))
            RootfsExtractor().extract(archive, stage, { cancelled.get() }) { bytes -> publish(state.copy(extracted = bytes), false) }
            cancellationPoint()
            publish(state.copy(phase = "configuring", message = "配置 DNS、临时目录与工作区"))
            configure(stage)
            publish(state.copy(phase = "checking", message = "实际运行 Bash 与 apt 自检"))
            val probe = runtime.probe(stage)
            if (probe.code != 0 || !probe.output.contains("AGENTM_LINUX_OK")) throw IOException("Linux 自检未通过：${probe.output.takeLast(2000)}")
            cancellationPoint()
            publish(state.copy(phase = "committing", message = "保存已验证的环境", probeOutput = probe.output, checkedAt = System.currentTimeMillis()))
            if (runtime.rootfs.exists() || !stage.renameTo(runtime.rootfs)) throw IOException("无法发布系统目录，现有文件未覆盖")
            publish(state.copy(phase = "ready", message = "Ubuntu 已就绪，可打开 Linux 终端", error = null))
            app.logs.add("linux", "Ubuntu ${definition.getString("version")} ${definition.getString("arch")} 安装并验证成功")
        } catch (e: InterruptedException) {
            publish(state.copy(phase = "cancelled", message = "安装已取消，可稍后续传", error = null))
            app.logs.add("linux", "安装已取消", "W")
        } catch (e: Exception) {
            val message = e.message?.take(2048) ?: e.javaClass.simpleName
            publish(state.copy(phase = "failed", message = "安装未完成", error = message))
            app.logs.add("linux", message, "E")
        } finally {
            runCatching { if (stage.exists()) deleteStage(stage) }
            executing.set(false)
        }
    }

    @Synchronized fun check(): JSONObject {
        if (busy || app.packages.busy) return snapshot()
        if (!runtime.rootfs.exists()) return snapshot()
        require(state.imageSha == image?.getString("sha256")) { "现有系统缺少本机安装来源记录" }
        // A crash after the directory rename but before the final journal can be recovered here.
        val result = runtime.probe()
        if (result.code == 0 && result.output.contains("AGENTM_LINUX_OK"))
            update(state.copy(phase = "ready", message = "Linux 自检通过", error = null, probeOutput = result.output, checkedAt = System.currentTimeMillis()))
        else update(state.copy(phase = "failed", message = "Linux 自检未通过", error = result.output.takeLast(2048)))
        return snapshot()
    }

    private fun configure(root: File) {
        for (name in listOf("tmp", "var/tmp", "root", "workspace", "proc", "dev", "sys")) File(root, name).mkdirs()
        val dns = File(root, "etc/resolv.conf")
        if (Files.isSymbolicLink(dns.toPath())) Files.delete(dns.toPath())
        dns.writeText("nameserver 1.1.1.1\nnameserver 223.5.5.5\n")
        // apt runs as the app UID under fake root; Android cannot perform real _apt privilege drops.
        File(root, "etc/apt/apt.conf.d/99agentm").writeText("APT::Sandbox::User \"root\";\n")
        File(root, "etc/hostname").writeText("agentm\n")
    }

    private fun download(definition: JSONObject, target: File, progress: (Long) -> Unit) {
        val expected = definition.getLong("bytes")
        var offset = if (target.exists()) target.length() else 0L
        if (offset > expected) { target.delete(); offset = 0 }
        if (offset == expected) { progress(offset); return }
        val connection = URL(definition.getString("url")).openConnection() as HttpURLConnection
        connection.connectTimeout = 20000; connection.readTimeout = 20000
        connection.setRequestProperty("Accept-Encoding", "identity")
        if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
        try {
            val code = connection.responseCode
            if (code !in listOf(200, 206)) throw IOException("镜像下载返回 HTTP $code")
            if (connection.url.protocol != "https") throw IOException("拒绝非 HTTPS 镜像")
            if (code == 206 && connection.getHeaderField("Content-Range") != "bytes $offset-${expected - 1}/$expected") throw IOException("镜像续传范围不匹配")
            if (code == 200) offset = 0
            java.io.FileOutputStream(target, offset > 0).use { output -> connection.inputStream.use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    cancellationPoint()
                    val size = input.read(buffer); if (size < 0) break
                    if (offset + size > expected) throw IOException("镜像大小超过清单")
                    output.write(buffer, 0, size); offset += size; progress(offset)
                }
                output.fd.sync()
            } }
            if (offset != expected) throw IOException("镜像下载不完整，可重试续传")
        } finally { connection.disconnect() }
    }

    private fun verify(file: File, expected: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(65536)
            while (true) { cancellationPoint(); val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
        }
        if (digest.digest().joinToString("") { "%02x".format(it) } != expected) { file.delete(); throw IOException("镜像 SHA-256 校验失败，请重新下载") }
    }

    private fun deleteStage(directory: File) {
        require(directory.parentFile!!.canonicalFile == runtime.base.canonicalFile && directory.name.matches(Regex("staging-[a-f0-9-]{36}")))
        // walkFileTree does not follow symbolic links, including absolute guest links in a rootfs.
        Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { Files.delete(file); return FileVisitResult.CONTINUE }
            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult { if (exc != null) throw exc; Files.delete(dir); return FileVisitResult.CONTINUE }
        })
    }
}
