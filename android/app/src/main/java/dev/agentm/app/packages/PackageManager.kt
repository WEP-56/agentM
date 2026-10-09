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

/** An atomic manifest selects a verified slot; home/workspaces are never removed. */
class PackageManager(private val app: AgentMApplication) {
    private val runtime get() = app.linux.runtime
    private val journal = AtomicFile(File(app.filesDir, "packages.json"))
    private val catalog = JSONObject(app.assets.open("agent-catalog.json").bufferedReader().use { it.readText() })
    private val executing = AtomicBoolean(false)
    @Volatile private var state: JSONObject = restore()
    val busy get() = state.optBoolean("busy")
    val toolsReady get() = validRecord("node")
    val claudeReady get() = validRecord("claude")
    val codexReady get() = validRecord("codex")
    val piReady get() = validRecord("pi")
    val openCodeReady get() = validRecord("opencode")
    val dshReady get() = validRecord("dsh")
    private val dsh by lazy { DshPackage(app.assets, app.linux.abi ?: error("不支持该架构")) }
    private val slots get() = File(runtime.managed, "slots").apply { mkdirs() }

    private fun restore(): JSONObject = runCatching {
        JSONObject(journal.openRead().bufferedReader().use { it.readText() }).apply {
            if (optBoolean("busy")) put("phase", "interrupted").put("message", "上次软件管理任务中断，请重试；已发布版本保留")
            optJSONObject("updates")?.let { updates -> updates.keys().forEach { kind ->
                updates.getJSONObject(kind).let { if (it.optString("status") == "checking") it.put("status", "failed").put("error", "上次在线检查中断，请重试") }
            } }
            put("busy", false)
        }
    }.getOrElse { JSONObject().put("busy", false).put("phase", "idle").put("message", "尚未准备开发工具") }

    @Synchronized private fun update(edit: (JSONObject) -> Unit) {
        val next = JSONObject(state.toString()).also(edit)
        val stream = journal.startWrite()
        try { stream.write(next.toString().toByteArray()); journal.finishWrite(stream); state = next }
        catch (error: Exception) { journal.failWrite(stream); throw error }
    }
    private fun asset(kind: String): JSONObject = if (kind == "dsh") JSONObject().put("version", dsh.version).put("sha256", dsh.sha256)
        .put("url", "https://github.com/DSH-APP/DSHA/tree/70e37a7dbcae83b32fc92a8a37b33af88befc0e0/tools/dsh-runtime")
        else catalog.getJSONObject(kind).getJSONObject(app.linux.abi ?: error("不支持该架构"))
    private fun slot(record: JSONObject): File {
        val name = record.getString("slot")
        require(ManagedPackagePaths.validSlot(name)) { "无效受管槽位" }
        val target = File(slots, name)
        require(!Files.isSymbolicLink(runtime.managed.toPath()) && !Files.isSymbolicLink(slots.toPath()) &&
            target.canonicalFile == File(slots.canonicalFile, name)) { "受管目录不能是符号链接" }
        return target
    }
    private fun ownedRecord(kind: String, record: JSONObject, checkRuntime: Boolean = true): File {
        val directory = slot(record)
        val marker = JSONObject(File(directory, ".agentm-slot.json").readText())
        val entry = record.getString("entry")
        val executable = File(directory, entry)
        require(record.getString("slot").startsWith("$kind-") && ManagedPackagePaths.validVersion(record.getString("version")))
        require(ManagedPackagePaths.validEntry(kind, entry))
        require(marker.getString("sha256") == record.getString("sha256") && marker.getString("slot") == record.getString("slot") &&
            marker.getString("entry") == entry && marker.getString("version") == record.getString("version")) { "安装来源记录不匹配，拒绝接管" }
        require(executable.isFile && executable.canonicalFile == File(directory.canonicalFile, entry)) { "受管可执行文件缺失或已被替换为链接" }
        if (kind == "codex" && checkRuntime) {
            val bundle = executable.parentFile!!.parentFile!!
            for (name in listOf("bin/codex-code-mode-host", "codex-path/rg", "codex-resources/bwrap", "codex-package.json")) {
                val resource = File(bundle, name)
                require(resource.isFile && resource.canonicalFile == File(bundle.canonicalFile, name)) { "Codex 原生组件不完整" }
            }
            val metadata = JSONObject(File(bundle, "codex-package.json").readText())
            require(metadata.getString("version") == record.getString("version") && metadata.getInt("layoutVersion") == 1) { "Codex 组件版本不匹配" }
        }
        if (kind == "pi" && checkRuntime) PiPackage.verifyLayout(directory, record.getString("version"), asset("pi"))
        if (kind == "dsh" && checkRuntime) dsh.verify(directory, record.getString("version"))
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
        if (codexReady) dirs += guest(state.getJSONObject("codex")).substringBeforeLast('/')
        if (piReady) dirs += guest(state.getJSONObject("pi")).substringBeforeLast('/')
        if (openCodeReady) dirs += guest(state.getJSONObject("opencode")).substringBeforeLast('/')
        if (dshReady) dirs += guest(state.getJSONObject("dsh")).substringBeforeLast('/')
        return (dirs + listOf("/usr/local/sbin", "/usr/local/bin", "/usr/sbin", "/usr/bin", "/sbin", "/bin")).joinToString(":")
    }
    fun agentCommand(kind: String): List<String> {
        require(kind in ManagedPackagePaths.agents) { "尚未接入该 Agent" }
        check(!busy && validRecord(kind) && toolsReady) { "请先准备工具并安装或检查 ${ManagedPackagePaths.title(kind)}" }
        val argv = listOf(guest(state.getJSONObject(kind)))
        return if (kind == "codex") argv + listOf("-c", "check_for_update_on_startup=false") else argv
    }
    fun snapshot(): JSONObject = JSONObject(state.toString()).put("toolsReady", toolsReady).put("claudeReady", claudeReady).put("codexReady", codexReady).put("piReady", piReady)
        .put("nodeVersion", catalog.getString("nodeVersion")).put("claudeVersion", catalog.getString("claudeVersion")).put("codexVersion", catalog.getString("codexVersion")).put("piVersion", catalog.getString("piVersion"))
        .put("openCodeReady", openCodeReady).put("dshReady", dshReady).put("opencodeVersion", catalog.getString("opencodeVersion")).put("dshVersion", dsh.version)
        .also { snapshot -> snapshot.optJSONObject("updates")?.let { updates -> updates.keys().forEach { kind ->
            val info = updates.getJSONObject(kind)
            info.remove("candidate")
            info.put("updateAvailable", runCatching {
                val installed = state.optJSONObject(kind) ?: return@runCatching false
                UpdatePolicy.newer(updateDefinition(kind).getString("version"), installed.getString("version"))
            }.getOrDefault(false))
        } } }

    fun enqueue(action: String): String = synchronized(app.maintenance) {
        require(action in ManagedPackagePaths.installActions || action in ManagedPackagePaths.removeActions || action in ManagedPackagePaths.checkActions ||
            action in ManagedPackagePaths.updateCheckActions || action in ManagedPackagePaths.updateActions || action == "installTools") { "未知软件管理操作" }
        check(!app.configs.busy) { "配置保存进行中，请稍后管理软件" }
        if (busy) {
            check(state.optString("action") == action) { "另一个软件管理任务正在执行" }
            return@synchronized state.getString("operationId")
        }
        check(app.linux.ready && !app.linux.busy) { "请先安装并检查 Ubuntu" }
        check(app.terminals.session?.isRunning != true) { "请先关闭当前终端，再管理软件" }
        check(!app.webAgents.active) { "请先停止 Web 服务，再管理软件" }
        if (action in ManagedPackagePaths.installActions || action in ManagedPackagePaths.updateActions) check(toolsReady) { "请先在环境页安装开发工具" }
        ManagedPackagePaths.updateActions[action]?.let { kind ->
            val installed = state.optJSONObject(kind) ?: error("请先安装该 Agent")
            val supported = updateDefinition(kind)
            check(UpdatePolicy.newer(supported.getString("version"), installed.getString("version"))) { "当前没有可适配的更新；请先在线检查" }
        }
        ManagedPackagePaths.removeActions[action]?.let { check(state.optJSONObject(it) != null) { "没有该 Agent 的受管安装" } }
        if (action.startsWith("install") || action in ManagedPackagePaths.updateActions) check(StatFs(app.filesDir.absolutePath).availableBytes >= 1024L * 1024 * 1024) { "至少需要 1 GiB 可用空间" }
        if (action == "installDsh" || action == "updateDsh") check(StatFs(app.filesDir.absolutePath).availableBytes >= 3L * 1024 * 1024 * 1024) { "DSH 安装至少需要 3 GiB 可用空间（含依赖、缓存与旧版本）" }
        val id = UUID.randomUUID().toString()
        update { it.put("busy", true).put("phase", "queued").put("action", action).put("operationId", id)
            .put("message", "准备软件管理任务").put("error", JSONObject.NULL).put("downloadedBytes", 0).put("totalBytes", 0)
            ManagedPackagePaths.updateCheckActions[action]?.let { kind ->
                val updates = it.optJSONObject("updates") ?: JSONObject().also { value -> it.put("updates", value) }
                val check = updates.optJSONObject(kind) ?: JSONObject().also { value -> updates.put(kind, value) }
                check.put("status", "checking").put("attemptedAt", System.currentTimeMillis()).put("error", JSONObject.NULL).remove("candidate")
            }
        }
        id
    }

    fun failure(error: Exception) {
        val message = error.message?.take(2000) ?: error.javaClass.simpleName
        runCatching { update { it.put("busy", false).put("phase", "failed").put("message", "操作未完成，可重试").put("error", message)
            ManagedPackagePaths.updateCheckActions[it.optString("action")]?.let { kind ->
                it.optJSONObject("updates")?.optJSONObject(kind)?.put("status", "failed")?.put("error", message)
            }
        } }
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
                in ManagedPackagePaths.installActions -> install(ManagedPackagePaths.installActions.getValue(state.getString("action")), ::phase)
                in ManagedPackagePaths.updateActions -> {
                    val kind = ManagedPackagePaths.updateActions.getValue(state.getString("action"))
                    install(kind, ::phase, updateDefinition(kind))
                }
                in ManagedPackagePaths.updateCheckActions -> {
                    val kind = ManagedPackagePaths.updateCheckActions.getValue(state.getString("action"))
                    phase("checking-update", "正在查询 ${ManagedPackagePaths.title(kind)} 上游版本")
                    val result = PackageUpdates().check(kind, asset(kind))
                    update { it.getJSONObject("updates").put(kind, result) }
                }
                in ManagedPackagePaths.removeActions -> {
                    val kind = ManagedPackagePaths.removeActions.getValue(state.getString("action"))
                    phase("removing", "卸载受管程序，保留 ${ManagedPackagePaths.title(kind)} 配置与会话")
                    val record = state.getJSONObject(kind)
                    val directory = ownedRecord(kind, record, checkRuntime = false)
                    // Unpublish first. A crash during deletion cannot expose a half-removed install.
                    update { it.remove(kind) }
                    deleteSlot(directory)
                }
                in ManagedPackagePaths.checkActions -> {
                    phase("checking", "实际检查已安装程序")
                    val failures = mutableListOf<String>()
                    for (kind in listOf(ManagedPackagePaths.checkActions.getValue(state.getString("action")))) {
                        val record = state.optJSONObject(kind) ?: error("尚未安装 ${ManagedPackagePaths.title(kind)}")
                        try {
                            ownedRecord(kind, record)
                            val output = probe(kind, guest(record), record.getString("version"))
                            update { it.getJSONObject(kind).put("verified", true).put("probeOutput", output).put("checkedAt", System.currentTimeMillis()) }
                            if (kind == "codex") { val sandbox = probeCodexSandbox(guest(record)); update { it.getJSONObject(kind).put("sandboxProbe", sandbox) } }
                        } catch (error: Exception) {
                            update { it.getJSONObject(kind).put("verified", false).put("probeOutput", error.message?.take(2000) ?: "检查失败").put("checkedAt", System.currentTimeMillis()) }
                            failures += "${ManagedPackagePaths.title(kind)}：${error.message?.take(1000) ?: "检查失败"}"
                        }
                    }
                    if (failures.isNotEmpty()) throw IOException(failures.joinToString("\n"))
                }
            }
            update { it.put("busy", false).put("phase", "done").put("message", "软件管理任务已完成").put("error", JSONObject.NULL) }
            app.logs.add("packages", "${state.getString("action")} 已完成")
        } catch (error: Exception) { failure(error) }
        finally { executing.set(false) }
    }

    private fun updateDefinition(kind: String): JSONObject {
        val check = state.optJSONObject("updates")?.optJSONObject(kind) ?: error("请先在线检查更新")
        check(check.optString("status") == "checked" && System.currentTimeMillis() - check.getLong("checkedAt") in 0..86400000L) { "更新检查已过期或失败，请重新检查" }
        val pinned = asset(kind)
        val candidate = check.optJSONObject("candidate") ?: return pinned
        require(UpdatePolicy.accepts(kind, pinned.getString("version"), candidate.getString("version"))) { "此版本需要新的适配 recipe" }
        return JSONObject(candidate.toString())
    }

    private fun install(kind: String, phase: (String, String) -> Unit, definition: JSONObject = asset(kind)) {
        val old = state.optJSONObject(kind)
        val id = UUID.randomUUID().toString()
        val name = "$kind-$id"
        val stage = File(slots, name)
        // Unique unselected slot. Publishing happens only through the final AtomicFile write.
        check(!stage.exists() && !Files.isSymbolicLink(runtime.managed.toPath()) && !Files.isSymbolicLink(slots.toPath()))
        val transaction = SlotTransaction { if (stage.exists()) deleteSlot(stage) }
        try {
            if (kind == "pi") {
                phase("packages", "准备 Pi 的文件搜索工具")
                command("""
                    set -e
                    if ! test -x /usr/bin/rg || ! test -x /usr/bin/fdfind; then
                        export DEBIAN_FRONTEND=noninteractive
                        /usr/bin/dpkg --configure -a
                        /usr/bin/apt-get update -o Acquire::Retries=1 -o Acquire::http::Timeout=25 -o APT::Update::Error-Mode=any
                        /usr/bin/apt-get -y --no-install-recommends -o Dpkg::Options::=--force-confold install ripgrep fd-find
                    fi
                """.trimIndent(), 600)
            }
            phase("downloading", "下载 ${ManagedPackagePaths.title(kind)} ${definition.getString("version")}")
            var previous = 0L
            if (kind == "dsh") {
                dsh.install(stage, VerifiedDownload(File(app.cacheDir, "agent-downloads"))) { message, current, total ->
                    if (System.currentTimeMillis() - previous >= 500 || current == total) {
                        update { it.put("message", message).put("downloadedBytes", current).put("totalBytes", total) }; previous = System.currentTimeMillis()
                    }
                }
            } else {
            val archive = VerifiedDownload(File(app.cacheDir, "agent-downloads")).fetch(definition) { current, total ->
                if (System.currentTimeMillis() - previous >= 500 || current == total) {
                    update { it.put("downloadedBytes", current).put("totalBytes", total) }; previous = System.currentTimeMillis()
                }
            }
            phase("extracting", "软件包摘要校验通过，正在解压")
            RootfsExtractor().extract(archive, stage, { Thread.currentThread().isInterrupted }) {}
            }
            if (kind == "pi") {
                phase("downloading", "下载并校验 Pi 的代码执行与图片处理组件")
                PiPackage.prepare(stage, definition, VerifiedDownload(File(app.cacheDir, "agent-downloads"))) { current, total ->
                    update { it.put("downloadedBytes", current).put("totalBytes", total) }
                }
            }
            val entry = when (kind) { "codex", "opencode" -> definition.getString("executable"); "dsh" -> "bin/dsh"; "pi" -> "bin/pi"; "node" -> definition.getString("archiveRoot") + "/bin/node"; else -> definition.getString("archiveRoot") + "/claude" }
            val record = JSONObject().put("slot", name).put("entry", entry).put("version", definition.getString("version"))
                .put("sha256", definition.getString("sha256")).put("source", definition.getString("url")).put("verified", true)
            File(stage, ".agentm-slot.json").writeText(record.toString())
            ownedRecord(kind, record)
            phase("checking", "实际执行版本与运行检查")
            val output = probe(kind, guest(record), record.getString("version"))
            record.put("probeOutput", output).put("checkedAt", System.currentTimeMillis())
            if (kind == "codex") {
                phase("checking", "检查 Codex 命令沙箱兼容性")
                record.put("sandboxProbe", probeCodexSandbox(guest(record)))
            }
            transaction.publish(select = { update { it.put(kind, record) } }, retire = {
                // Only a previously selected owned slot can be cleaned; no HOME/config directory is involved.
                if (old != null) deleteSlot(ownedRecord(kind, old, checkRuntime = false))
            })
        } finally { transaction.close() }
    }

    private fun probe(kind: String, executable: String, expectedVersion: String): String {
        require(ManagedPackagePaths.validGuest(executable))
        require(ManagedPackagePaths.validVersion(expectedVersion))
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
        } else if (kind == "pi") probePi(executable, expectedVersion)
        else if (kind == "opencode" || kind == "dsh") command("$executable --version", 90).also {
            check(it.lineSequence().any { line -> line.trim() == expectedVersion || line.trim() == "$kind $expectedVersion" }) { "${ManagedPackagePaths.title(kind)} 版本不匹配：${it.takeLast(500)}" }
        }
        else if (kind == "codex") command("$executable --version", 60).also {
            check(it.lineSequence().any { line -> line == "codex-cli $expectedVersion" }) { "Codex 版本不符合安装记录：${it.takeLast(500)}" }
        } else command("$executable --version", 60).also {
            check(it.lineSequence().any { line -> line == "$expectedVersion (Claude Code)" }) { "Claude Code 版本不符合安装记录：${it.takeLast(500)}" }
        }
    }
    private fun probePi(executable: String, expectedVersion: String): String {
        val probe = File(slots, "pi-${UUID.randomUUID()}")
        Files.createDirectory(probe.toPath())
        val guestProbe = "/opt/agentm/slots/${probe.name}"
        val packagePath = executable.removeSuffix("/bin/pi") + "/package"
        try {
            File(probe, "home").mkdirs(); File(probe, "workspace").mkdirs()
            app.assets.open("pi-probe.mjs").use { input -> File(probe, "probe.mjs").outputStream().use { input.copyTo(it) } }
            return command("""
                set -eu
                export PI_CODING_AGENT_DIR=$guestProbe/home PI_OFFLINE=1 PI_SKIP_VERSION_CHECK=1
                cd $guestProbe/workspace
                test "${'$'}($executable --version)" = "$expectedVersion"
                $executable --version
                /usr/bin/rg --version | head -n 1
                /usr/bin/fdfind --version
                node $guestProbe/probe.mjs $packagePath
            """.trimIndent(), 90).also {
                check(it.contains("AGENTM_PI_TOOLS_OK") && it.contains("AGENTM_PI_WASM_OK")) { "Pi 本地工具检查未完成" }
            }
        } finally { deleteSlot(probe) }
    }
    private fun probeCodexSandbox(executable: String): JSONObject {
        require(ManagedPackagePaths.validGuest(executable))
        // Codex refuses helper aliases beneath /tmp. Use a disposable private slot instead,
        // isolated from the user's CODEX_HOME and projects, with the same mount as installed binaries.
        val probe = File(slots, "codex-${UUID.randomUUID()}")
        Files.createDirectory(probe.toPath())
        File(probe, "home").mkdirs(); File(probe, "workspace").mkdirs()
        val guest = "/opt/agentm/slots/${probe.name}"
        var engineOutput = ""
        val result = try { runCatching { runtime.run(script = """
            set -eu
            export CODEX_HOME=$guest/home
            cd $guest/workspace
            $executable sandbox linux -- /bin/sh -c 'printf "AGENTM_CODEX_SANDBOX_OK\n"'
        """.trimIndent(), timeoutSeconds = 30).also { result ->
            if (result.code != 0) {
                val bwrap = executable.substringBeforeLast('/').substringBeforeLast('/') + "/codex-resources/bwrap"
                val engine = runtime.run(script = "$bwrap --unshare-user --ro-bind / / -- /bin/sh -c 'printf AGENTM_BWRAP_OK'", timeoutSeconds = 10)
                engineOutput = "\nbwrap 独立检查（退出 ${engine.code}）：\n${engine.output.takeLast(1200)}"
            }
        } } }
        finally { deleteSlot(probe) }
        result.exceptionOrNull()?.let { if (it is InterruptedException) throw it }
        val value = result.getOrNull()
        val passed = value?.code == 0 && value.output.contains("AGENTM_CODEX_SANDBOX_OK")
        return JSONObject().put("status", if (passed) "passed" else "unavailable").put("exitCode", value?.code ?: -1)
            .put("output", (value?.output?.takeLast(1800) ?: "沙箱检查未完成：${result.exceptionOrNull()?.javaClass?.simpleName}") + engineOutput)
            .put("checkedAt", System.currentTimeMillis())
    }
    private fun command(script: String, timeout: Long): String {
        val result = runtime.run(script = script, timeoutSeconds = timeout)
        if (result.code != 0) throw IOException("命令退出 ${result.code}：${result.output.takeLast(1800)}")
        return result.output
    }
    private fun deleteSlot(directory: File) {
        require(directory.parentFile!!.canonicalFile == slots.canonicalFile && ManagedPackagePaths.validSlot(directory.name))
        Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { Files.delete(file); return FileVisitResult.CONTINUE }
            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult { if (exc != null) throw exc; Files.delete(dir); return FileVisitResult.CONTINUE }
        })
    }
}
