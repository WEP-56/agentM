package dev.agentm.app.web

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.system.Os
import android.system.OsConstants
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import dev.agentm.app.AgentMApplication
import dev.agentm.app.ProcessIdentity
import dev.agentm.app.PtyIdentity
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors

class WebAgentManager(private val app: AgentMApplication) : TerminalSessionClient {
    class Session(val kind: String, val id: String, val version: String) {
        @Volatile var state = "starting"
        @Volatile var message = "正在启动本机 Web 服务"
        @Volatile var process: TerminalSession? = null
        @Volatile var birth: ProcessIdentity? = null
        @Volatile var port = 0
        @Volatile internal var password = ""
        @Volatile internal var token: String? = null
        @Volatile internal var cookie: String? = null
        internal var diagnostic = ""
        var probing = false
        internal var restartRequested = false
        val startedAt = System.currentTimeMillis()
        val active get() = state in setOf("starting", "ready", "stopping", "stop-unconfirmed") || process?.isRunning == true
        fun snapshot() = JSONObject().put("id", id).put("kind", kind).put("state", state).put("message", message)
            .put("port", port).put("version", version).put("running", active).put("pid", process?.pid ?: -1)
    }
    private val sessions = linkedMapOf<String, Session>()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(2)
    private val observers = mutableSetOf<() -> Unit>()
    val active: Boolean get() = synchronized(app.maintenance) { sessions.values.any { it.active } }
    fun current(kind: String): Session? = synchronized(app.maintenance) { sessions[kind] }
    fun observe(observer: () -> Unit) { observers.add(observer) }
    fun unobserve(observer: () -> Unit) { observers.remove(observer) }
    private fun changed() { observers.toList().forEach { it() } }
    fun snapshot(): JSONObject = synchronized(app.maintenance) { JSONObject().also { out -> sessions.forEach { (kind, value) -> out.put(kind, value.snapshot()) } } }

    fun request(kind: String): Session = synchronized(app.maintenance) {
        check(Looper.myLooper() == Looper.getMainLooper())
        require(kind in WebPolicy.kinds) { "该 Agent 不提供 WebUI" }
        sessions[kind]?.takeIf { it.active }?.let {
            check(it.state in setOf("starting", "ready")) { "正在确认 Web 服务停止，请稍后重试" }
            return@synchronized it
        }
        check(app.linux.ready && !app.linux.busy && !app.packages.busy && !app.configs.busy) { "请先完成环境或软件管理任务" }
        check(!app.providers.busy && app.providers.recoveryError == null) { "请先完成提供商配置写入或恢复" }
        check(app.terminals.session?.isRunning != true || app.terminals.kind != kind) { "请先关闭该 Agent 的终端，再打开 WebUI" }
        app.packages.agentCommand(kind)
        Session(kind, UUID.randomUUID().toString(), app.packages.snapshot().getJSONObject(kind).getString("version")).also {
            if (kind == "opencode") it.password = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
            sessions[kind] = it
            changed()
        }
    }
    fun launch(kind: String, id: String) = synchronized(app.maintenance) {
        val session = sessions[kind]?.takeIf { it.id == id && it.state == "starting" && it.process == null } ?: return@synchronized
        try {
            val command = app.packages.agentCommand(kind) + if (kind == "dsh") listOf("web", "--no-open", "--host", "127.0.0.1", "--port", "0")
                else listOf("web", "--hostname", "127.0.0.1", "--port", "0")
            val env = if (kind == "dsh") mapOf("DSH_HOME" to "/root/.dsh", "BROWSER" to "true")
                else mapOf("BROWSER" to "true", "OPENCODE_SERVER_USERNAME" to "opencode", "OPENCODE_SERVER_PASSWORD" to session.password, "OPENCODE_DISABLE_AUTOUPDATE" to "true")
            val launch = app.linux.runtime.launch(command = command, guestEnvironment = env)
            val process = TerminalSession(launch.argv[0], launch.cwd, launch.argv, launch.environment, 150, this)
            session.process = process
            PtyIdentity.capture({ session.birth = it }) { process.initializeEmulator(512, 32) }
            app.logs.add("web", "$kind Web 服务已创建 · session=$id")
            main.postDelayed({ tick(session) }, 150)
        } catch (error: Exception) {
            session.message = "Web 服务启动失败：${error.javaClass.simpleName}"
            session.state = "failed"
            if (session.process?.isRunning == true) stop(kind)
            else { session.password = ""; session.cookie = null; session.token = null }
            changed()
        }
    }
    private fun tick(session: Session) {
        if (current(session.kind) !== session || session.state != "starting") return
        val process = session.process ?: return
        if (!process.isRunning) return
        val output = process.emulator?.screen?.transcriptText ?: ""
        if (session.kind == "dsh") WebPolicy.dshStartup(output)?.let { (port, token) -> session.port = port; session.token = token }
        else WebPolicy.openCodePort(output)?.let { session.port = it }
        if (System.currentTimeMillis() - session.startedAt > 90000) {
            session.diagnostic = safeOutput(session, output)
            session.message = "Web 启动超时，请停止后重试"
            stop(session.kind)
            return
        }
        if (session.port > 0 && !session.probing) {
            session.probing = true
            val port = session.port
            val password = session.password
            val token = session.token
            worker.execute {
                var cookie: String? = null
                val ready = runCatching {
                    if (session.kind == "dsh") { cookie = token?.let { WebHealth.dsh(port, it) }; cookie != null }
                    else WebHealth.openCode(port, password, session.version)
                }.getOrDefault(false)
                main.post {
                    if (current(session.kind) !== session || session.state != "starting") return@post
                    session.probing = false
                    if (ready && process.isRunning) {
                        session.cookie = cookie
                        session.state = "ready"
                        session.message = "Web 服务已就绪 · 127.0.0.1:$port"
                        app.logs.add("web", "${session.kind} 身份与认证检查通过 · port=$port")
                        changed()
                    }
                }
            }
        }
        main.postDelayed({ tick(session) }, 400)
    }
    private fun safeOutput(session: Session, text: String): String {
        var value = WebPolicy.redact(text)
        if (session.password.isNotEmpty()) value = value.replace(session.password, "[redacted]")
        session.token?.let { value = value.replace(it, "[redacted]") }
        return value
    }
    private fun owns(session: Session): Boolean = session.process?.let { process ->
        process.isRunning && session.birth != null && runCatching { ProcessIdentity.parse(File("/proc/${process.pid}/stat").readText()) }.getOrNull() == session.birth
    } == true
    fun restart(kind: String) = synchronized(app.maintenance) {
        val session = sessions[kind]
        if (session?.active == true) {
            check(session.state in setOf("starting", "ready")) { "正在确认服务退出，请稍后重试" }
            session.restartRequested = true
            stopOwnedSession(kind)
        } else WebAgentService.start(app, request(kind))
    }
    fun stop(kind: String) = synchronized(app.maintenance) {
        sessions[kind]?.restartRequested = false
        stopOwnedSession(kind)
    }
    private fun stopOwnedSession(kind: String) = synchronized(app.maintenance) {
        val session = sessions[kind] ?: return@synchronized
        if (!session.active || session.state == "stopping") return@synchronized
        session.password = ""; session.token = null; session.cookie = null
        if (session.process?.isRunning != true) {
            session.state = "exited"; session.message = "Web 会话已停止"
            if (session.restartRequested) { session.restartRequested = false; WebAgentService.start(app, request(kind)) }
            changed(); return@synchronized
        }
        session.state = "stopping"
        if (session.message.startsWith("Web 服务已") || session.message.startsWith("正在")) session.message = "正在停止 Web 会话"
        if (owns(session)) runCatching { Os.kill(-session.process!!.pid, OsConstants.SIGHUP) }
        main.postDelayed({ if (current(kind) === session && owns(session)) session.process!!.finishIfRunning() }, 1500)
        main.postDelayed({
            if (current(kind) === session && session.process?.isRunning == true) {
                session.restartRequested = false
                session.state = "stop-unconfirmed"; session.message = "未确认进程退出，请重试停止；软件管理保持锁定"; changed()
            }
        }, 4500)
        changed()
    }
    fun stopAll() { WebPolicy.kinds.forEach(::stop) }
    override fun onSessionFinished(process: TerminalSession) {
        synchronized(app.maintenance) {
            val session = sessions.values.firstOrNull { it.process === process } ?: return
            session.diagnostic = safeOutput(session, process.emulator?.screen?.transcriptText ?: "")
            if (session.state !in setOf("stopping", "stop-unconfirmed")) session.message = "Web 进程退出（${process.exitStatus}），请检查原生 Agent 配置或重试"
            else if (session.message == "正在停止 Web 会话") session.message = "Web 会话已停止"
            session.state = if (session.state in setOf("stopping", "stop-unconfirmed")) "exited" else "failed"
            session.password = ""; session.token = null; session.cookie = null
            app.logs.add("web", "${session.kind} Web 进程已退出 · code=${process.exitStatus}")
            if (session.restartRequested) {
                session.restartRequested = false
                try { WebAgentService.start(app, request(session.kind)) }
                catch (error: Exception) { session.message = "重启失败，请返回工作台检查环境后重试"; session.state = "failed" }
            }
            changed()
            if (!active) app.stopService(Intent(app, WebAgentService::class.java))
        }
    }
    override fun onTextChanged(session: TerminalSession) {}
    override fun onTitleChanged(session: TerminalSession) {}
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {}
    override fun onPasteTextFromClipboard(session: TerminalSession) {}
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle() = 0
    override fun logError(tag: String, message: String) { app.logs.add("web", "Web 进程内部错误：$tag", "E") }
    override fun logWarn(tag: String, message: String) {}
    override fun logInfo(tag: String, message: String) {}
    override fun logDebug(tag: String, message: String) {}
    override fun logVerbose(tag: String, message: String) {}
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) = logError(tag, message)
    override fun logStackTrace(tag: String, e: Exception) = logError(tag, "")
}
