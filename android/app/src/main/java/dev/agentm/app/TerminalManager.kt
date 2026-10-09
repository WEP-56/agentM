package dev.agentm.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.system.Os
import android.system.OsConstants
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Owns one PTY session independently of any Activity or WebView. */
class TerminalManager(private val app: AgentMApplication) : TerminalSessionClient {
    @Volatile var session: TerminalSession? = null; private set
    @Volatile private var id: String? = null
    @Volatile private var stopping = false
    private var restartRequested = false
    private var birth: ProcessIdentity? = null
    @Volatile var kind: String = "deviceShell"; private set
    @Volatile var launchDirectory: String = "/workspace"; private set
    private val main = Handler(Looper.getMainLooper())
    private val observers = mutableSetOf<() -> Unit>()

    fun observe(observer: () -> Unit) { observers.add(observer) }
    fun unobserve(observer: () -> Unit) { observers.remove(observer) }
    private fun changed() { observers.toList().forEach { it() } }

    fun requireOpenable(requestedKind: String) {
        require(requestedKind in setOf("deviceShell", "linuxShell") || requestedKind in dev.agentm.app.packages.ManagedPackagePaths.terminals) { "未知终端类型" }
        check(!app.packages.busy) { "软件管理任务进行中，请完成后打开终端" }
        check(!app.configs.busy) { "配置保存进行中，请稍后打开终端" }
        check(!app.providers.busy && app.providers.recoveryError == null) { "提供商配置写入或恢复未完成，请先处理配置" }
        check(app.webAgents.current(requestedKind)?.active != true) { "请先停止该 Agent 的 Web 服务，再打开终端" }
        check(session?.isRunning != true || kind == requestedKind) { "请先关闭当前终端，再切换终端类型" }
        if (requestedKind != "deviceShell") check(app.linux.ready) { "Ubuntu 尚未就绪，请先安装或检查系统" }
        if (requestedKind in dev.agentm.app.packages.ManagedPackagePaths.agents) app.packages.agentCommand(requestedKind)
        if (requestedKind != "deviceShell" && session?.isRunning != true) app.workingDirectories.current()
    }

    fun open(requestedKind: String = "deviceShell"): TerminalSession = synchronized(app.maintenance) {
        check(Looper.myLooper() == Looper.getMainLooper())
        requireOpenable(requestedKind)
        session?.takeIf { it.isRunning }?.let { return it }
        val workspace = File(app.filesDir, "workspaces").apply { mkdirs() }
        val env = arrayOf("HOME=${workspace.absolutePath}", "PATH=/system/bin:/system/xbin", "TERM=xterm-256color",
            "LANG=C.UTF-8", "TMPDIR=${app.cacheDir.absolutePath}", "PS1=agentM \\$ ")
        val directory = if (requestedKind == "deviceShell") null else app.workingDirectories.current()
        val linux = when (requestedKind) {
            "linuxShell" -> app.linux.runtime.launch(guestEnvironment = mapOf("PROMPT_COMMAND" to TerminalDirectoryPrompt.COMMAND), workingDirectory = directory!!.path)
            in dev.agentm.app.packages.ManagedPackagePaths.agents -> app.linux.runtime.launch(command = app.packages.agentCommand(requestedKind), workingDirectory = directory!!.path)
            else -> null
        }
        val argv = linux?.argv ?: arrayOf("/system/bin/sh", "-i")
        val created = TerminalSession(argv[0], linux?.cwd ?: workspace.absolutePath, argv, linux?.environment ?: env, 2000, this)
        id = UUID.randomUUID().toString()
        stopping = false
        kind = requestedKind
        launchDirectory = directory?.path ?: workspace.absolutePath
        session = created
        birth = null
        try { PtyIdentity.capture({ birth = it }) { created.initializeEmulator(80, 24) } }
        catch (error: Throwable) { session = null; birth = null; throw error }
        app.logs.add("terminal", "$kind PTY 已创建 · session=$id")
        changed()
        return created
    }

    fun stop() {
        restartRequested = false
        stopOwnedSession()
    }

    fun restart() {
        if (restartRequested || stopping) return
        requireOpenable(kind)
        if (kind != "deviceShell") app.workingDirectories.current() // Reject a missing selection before stopping an existing session.
        if (session?.isRunning != true) { open(kind); return }
        restartRequested = true
        stopOwnedSession()
    }

    private fun stopOwnedSession() {
        val current = session ?: return
        if (!current.isRunning || stopping) return
        stopping = true
        // HUP only an owned shell/group. Never discover processes by name or TCP port.
        if (owns(current)) runCatching { Os.kill(-current.pid, OsConstants.SIGHUP) }
        main.postDelayed({
            if (session === current && owns(current)) current.finishIfRunning()
        }, 1500)
        main.postDelayed({
            if (session === current && current.isRunning) {
                restartRequested = false; stopping = false
                app.logs.add("terminal", "尚未确认终端退出，请重试终止", "W"); changed()
            }
        }, 4500)
        app.logs.add("terminal", "已请求关闭 $kind 终端")
        changed()
    }

    private fun owns(current: TerminalSession): Boolean =
        current.isRunning && birth != null && processBirth(current.pid) == birth

    private fun processBirth(pid: Int): ProcessIdentity? = runCatching {
        ProcessIdentity.parse(File("/proc/$pid/stat").readText())
    }.getOrNull()

    fun snapshot(): JSONObject = JSONObject().put("id", id ?: JSONObject.NULL)
        .put("running", session?.isRunning == true).put("stopping", stopping)
        .put("kind", kind).put("pid", session?.pid ?: -1)
        .put("directory", launchDirectory)

    override fun onTextChanged(changedSession: TerminalSession) = changed()
    override fun onTitleChanged(changedSession: TerminalSession) = changed()
    override fun onSessionFinished(finishedSession: TerminalSession) {
        if (session !== finishedSession) return
        stopping = false
        app.logs.add("terminal", "$kind PTY 已退出 · code=${finishedSession.exitStatus}")
        if (restartRequested) {
            restartRequested = false
            try { open(kind); return }
            catch (error: Exception) { app.logs.add("terminal", "终端重启失败：${error.javaClass.simpleName}", "E") }
        }
        changed()
        app.stopService(Intent(app, TerminalService::class.java))
    }
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Terminal", text))
    }
    override fun onPasteTextFromClipboard(session: TerminalSession) {
        val clip = app.getSystemService(ClipboardManager::class.java).primaryClip ?: return
        session.emulator?.paste(clip.getItemAt(0).coerceToText(app).toString())
    }
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) = changed()
    override fun onTerminalCursorStateChange(state: Boolean) = changed()
    override fun getTerminalCursorStyle(): Int = 0
    override fun logError(tag: String, message: String) { app.logs.add("pty", "终端内部错误：$tag", "E") }
    override fun logWarn(tag: String, message: String) { app.logs.add("pty", "终端警告：$tag", "W") }
    override fun logInfo(tag: String, message: String) {}
    override fun logDebug(tag: String, message: String) {}
    override fun logVerbose(tag: String, message: String) {}
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) = logError(tag, message)
    override fun logStackTrace(tag: String, e: Exception) = logError(tag, e.javaClass.simpleName)
}
