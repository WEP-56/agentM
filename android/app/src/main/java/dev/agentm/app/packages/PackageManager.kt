package dev.agentm.app.packages

import android.os.StatFs
import android.util.AtomicFile
import dev.agentm.app.AgentMApplication
import dev.agentm.app.linux.RootfsExtractor
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** APK-pinned packages. An atomic manifest selects a verified slot; home/workspaces are never removed. */
class PackageManager(private val app: AgentMApplication) {
    private val runtime get() = app.linux.runtime
    private val journal = AtomicFile(File(app.filesDir, "packages.json"))
    private val catalog = JSONObject(app.assets.open("agent-catalog.json").bufferedReader().use { it.readText() })
    private val executing = AtomicBoolean(false)
    @Volatile private var state: JSONObject = restore()
    val busy get() = state.optBoolean("busy")
    val toolsReady get() = validRecord("node")
    val claudeReady get() = validRecord("claude")
    private val slots get() = File(runtime.managed, "slots").apply { mkdirs() }

    private fun restore(): JSONObject = runCatching {
        JSONObject(journal.openRead().bufferedReader().use { it.readText() }).apply {
            if (optBoolean("busy")) put("phase", "interrupted").put("message", "上次软件管理任务中断，请重试；已发布版本保留")
            put("busy", false)
        }
    }.getOrElse { JSONObject().put("busy", false).put("phase", "idle").put("message", "尚未准备开发工具") }

    @Synchronized private fun update(edit: (JSONObject) -> Unit) {
        val next = JSONObject(state.toString()).also(edit)
        val stream = journal.startWrite()
        try { stream.write(next.toString().toByteArray()); journal.finishWrite(stream); state = next }
        catch (error: Exception) { journal.failWrite(stream); throw error }
    }
    private fun asset(kind: String): JSONObject = catalog.getJSONObject(kind).getJSONObject(app.linux.abi ?: error("不支持该架构"))
    private fun slot(record: JSONObject): File {
        val name = record.getString("slot")
        require(name.matches(Regex("(node|claude)-[a-f0-9-]{36}"))) { "无效受管槽位" }
        val target = File(slots, name)
        require(!Files.isSymbolicLink(runtime.managed.toPath()) && !Files.isSymbolicLink(slots.toPath()) &&
            target.canonicalFile == File(slots.canonicalFile, name)) { "受管目录不能是符号链接" }
        return target
    }
    private fun ownedRecord(kind: String, record: JSONObject): File {
        val directory = slot(record)
        val marker = JSONObject(File(directory, ".agentm-slot.json").readText())
        val entry = record.getString("entry")
        val executable = File(directory, entry)
        require(record.getString("slot").startsWith("$kind-") && record.getString("version").matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")))
        require(entry.matches(Regex(if (kind == "node") "node-v[0-9.]+-linux-(x64|arm64)/bin/node" else "package/claude")))
        require(marker.getString("sha256") == record.getString("sha256") && marker.getString("slot") == record.getString("slot") &&
            marker.getString("entry") == entry && marker.getString("version") == record.getString("version")) { "安装来源记录不匹配，拒绝接管" }
        require(executable.isFile && executable.canonicalFile == File(directory.canonicalFile, entry)) { "受管可执行文件缺失或已被替换为链接" }
        return directory
    }
    private fun validRecord(kind: String): Boolean = runCatching {
        val record = state.optJSONObject(kind) ?: return false
        ownedRecord(kind, record)
        record.optBoolean("verified")
    }.getOrDefault(false)
    private fun guest(record: JSONObject) = "/opt/agentm/slots/${record.getString("slot")}/${record.getString("entry")}"
    fun path(): String {
        val dirs = mutableListOf<String>()
        if (toolsReady) dirs += guest(state.getJSONObject("node")).substringBeforeLast('/')
        if (claudeReady) dirs += guest(state.getJSONObject("claude")).substringBeforeLast('/')
        return (dirs + listOf("/usr/local/sbin", "/usr/local/bin", "/usr/sbin", "/usr/bin", "/sbin", "/bin")).joinToString(":")
    }
    fun claudeCommand(): List<String> {
        check(!busy && claudeReady && toolsReady) { "请先准备工具并安装或检查 Claude Code" }
        return listOf(guest(state.getJSONObject("claude")))
    }
    fun snapshot(): JSONObject = JSONObject(state.toString()).put("toolsReady", toolsReady).put("claudeReady", claudeReady)
        .put("nodeVersion", catalog.getString("nodeVersion")).put("claudeVersion", catalog.getString("claudeVersion"))

    fun enqueue(action: String): String = synchronized(app.maintenance) {
        require(action in setOf("installTools", "installClaude", "removeClaude", "checkPackages")) { "未知软件管理操作" }
        if (busy) {
            check(state.optString("action") == action) { "另一个软件管理任务正在执行" }
            return@synchronized state.getString("operationId")
        }
        check(app.linux.ready && !app.linux.busy) { "请先安装并检查 Ubuntu" }
        check(app.terminals.session?.isRunning != true) { "请先关闭当前终端，再管理软件" }
        if (action == "installClaude") check(toolsReady) { "请先在环境页安装开发工具" }
        if (action == "removeClaude") check(state.optJSONObject("claude") != null) { "没有受管的 Claude Code 安装" }
        if (action.startsWith("install")) check(StatFs(app.filesDir.absolutePath).availableBytes >= 1024L * 1024 * 1024) { "至少需要 1 GiB 可用空间" }
        val id = UUID.randomUUID().toString()
        update { it.put("busy", true).put("phase", "queued").put("action", action).put("operationId", id)
            .put("message", "准备软件管理任务").put("error", JSONObject.NULL).put("downloadedBytes", 0).put("totalBytes", 0) }
        id
    }

    fun failure(error: Exception) {
        val message = error.message?.take(2000) ?: error.javaClass.simpleName
        runCatching { update { it.put("busy", false).put("phase", "failed").put("message", "操作未完成，可重试").put("error", message) } }
            .onFailure { state = JSONObject(state.toString()).put("busy", false).put("phase", "failed").put("error", message) }
        app.logs.add("packages", message, "E")
    }
    fun execute(progress: (String) -> Unit) {
        if (!executing.compareAndSet(false, true)) return
        fun phase(name: String, message: String) { update { it.put("phase", name).put("message", message) }; progress(message) }
        try {
            if (!busy || state.optString("phase") != "queued") return
            when (state.getString("action")) {
                "installTools" -> {
                    phase("packages", "安装 Git、Python 和 CA 证书，可能需要数分钟")
                    command("""
                        set -e
                        export DEBIAN_FRONTEND=noninteractive
                        /usr/bin/dpkg --configure -a
                        /usr/bin/apt-get update -o Acquire::Retries=1 -o Acquire::http::Timeout=25 -o APT::Update::Error-Mode=any
                        /usr/bin/apt-get -y --no-install-recommends -o Dpkg::Options::=--force-confold install git python3 ca-certificates
                    """.trimIndent(), 900)
                    install("node", ::phase)
                }
                "installClaude" -> install("claude", ::phase)
                "removeClaude" -> {
                    phase("removing", "卸载受管程序，保留 Claude 配置与会话")
                    val record = state.getJSONObject("claude")
                    val directory = ownedRecord("claude", record)
                    // Unpublish first. A crash during deletion cannot expose a half-removed install.
                    update { it.remove("claude") }
                    deleteSlot(directory)
                }
                "checkPackages" -> {
                    phase("checking", "实际检查已安装工具与 Claude Code")
                    for (kind in listOf("node", "claude")) {
                        val record = state.optJSONObject(kind) ?: continue
                        try {
                            ownedRecord(kind, record)
                            val output = probe(kind, guest(record), record.getString("version"))
                            update { it.getJSONObject(kind).put("verified", true).put("probeOutput", output).put("checkedAt", System.currentTimeMillis()) }
                        } catch (error: Exception) {
                            update { it.getJSONObject(kind).put("verified", false) }
                            throw error
                        }
                    }
                }
            }
            update { it.put("busy", false).put("phase", "done").put("message", "软件管理任务已完成").put("error", JSONObject.NULL) }
            app.logs.add("packages", "${state.getString("action")} 已完成")
        } catch (error: Exception) { failure(error) }
        finally { executing.set(false) }
    }

    private fun install(kind: String, phase: (String, String) -> Unit) {
        val definition = asset(kind)
        val old = state.optJSONObject(kind)
        val id = UUID.randomUUID().toString()
        val name = "$kind-$id"
        val stage = File(slots, name)
        // Unique unselected slot. Publishing happens only through the final AtomicFile write.
        check(!stage.exists() && !Files.isSymbolicLink(runtime.managed.toPath()) && !Files.isSymbolicLink(slots.toPath()))
        var published = false
        try {
            phase("downloading", "下载 ${if (kind == "node") "Node.js" else "Claude Code"} ${definition.getString("version")}")
            var previous = 0L
            val archive = VerifiedDownload(File(app.cacheDir, "agent-downloads")).fetch(definition) { current, total ->
                if (System.currentTimeMillis() - previous >= 500 || current == total) {
                    update { it.put("downloadedBytes", current).put("totalBytes", total) }; previous = System.currentTimeMillis()
                }
            }
            phase("extracting", "SHA-256 校验通过，正在解压")
            RootfsExtractor().extract(archive, stage, { Thread.currentThread().isInterrupted }) {}
            val entry = definition.getString("archiveRoot") + if (kind == "node") "/bin/node" else "/claude"
            val record = JSONObject().put("slot", name).put("entry", entry).put("version", definition.getString("version"))
                .put("sha256", definition.getString("sha256")).put("source", definition.getString("url")).put("verified", true)
            File(stage, ".agentm-slot.json").writeText(record.toString())
            phase("checking", "实际执行版本与运行检查")
            val output = probe(kind, guest(record), record.getString("version"))
            record.put("probeOutput", output).put("checkedAt", System.currentTimeMillis())
            update { it.put(kind, record) }
            published = true
            // Only a previously selected owned slot can be cleaned; no HOME/config directory is involved.
            if (old != null) runCatching { deleteSlot(slot(old)) }
        } finally { if (!published && stage.exists()) deleteSlot(stage) }
    }

    private fun probe(kind: String, executable: String, expectedVersion: String): String {
        require(executable.matches(Regex("/opt/agentm/slots/(node|claude)-[a-f0-9-]{36}/[A-Za-z0-9./_-]+")))
        require(expectedVersion.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")))
        return if (kind == "node") {
            val bin = executable.substringBeforeLast('/')
            command("""
                set -e
                export PATH=$bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
                test "${'$'}($executable --version)" = "v$expectedVersion"
                $executable --version
                $bin/npm --version
                /usr/bin/git --version
                /usr/bin/python3 --version
                /usr/bin/dpkg-query -W git python3 ca-certificates
                $executable -e 'const fs=require("fs"),cp=require("child_process");if(!fs.existsSync("/workspace")||cp.execFileSync("/bin/sh",["-c","printf child-ok"]).toString()!=="child-ok")process.exit(1);console.log("AGENTM_NODE_OK")'
            """.trimIndent(), 60).also { check(it.contains("AGENTM_NODE_OK")) }
        } else command("$executable --version", 60).also {
            check(it.lineSequence().any { line -> line == "$expectedVersion (Claude Code)" }) { "Claude Code 版本不符合安装记录：${it.takeLast(500)}" }
        }
    }
    private fun command(script: String, timeout: Long): String {
        val result = runtime.run(script = script, timeoutSeconds = timeout)
        if (result.code != 0) throw IOException("命令退出 ${result.code}：${result.output.takeLast(1800)}")
        return result.output
    }
    private fun deleteSlot(directory: File) {
        require(directory.parentFile!!.canonicalFile == slots.canonicalFile && directory.name.matches(Regex("(node|claude)-[a-f0-9-]{36}")))
        Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { Files.delete(file); return FileVisitResult.CONTINUE }
            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult { if (exc != null) throw exc; Files.delete(dir); return FileVisitResult.CONTINUE }
        })
    }
}
