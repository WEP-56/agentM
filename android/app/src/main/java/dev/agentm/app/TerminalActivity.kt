package dev.agentm.app

import android.app.AlertDialog
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalSession
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import dev.agentm.app.ui.SessionAppearance
import dev.agentm.app.ui.SessionChrome

class TerminalActivity : ComponentActivity() {
    private val manager get() = (application as AgentMApplication).terminals
    private lateinit var terminal: TerminalView
    private lateinit var path: TextView
    private lateinit var chrome: SessionChrome
    private lateinit var ctrlKey: TextView
    private val preferences by lazy { getSharedPreferences("terminal-view", MODE_PRIVATE) }
    private var fontSize = 14f
    private var ctrl = false
    private val kind get() = intent.getStringExtra("kind") ?: manager.kind
    private val agentName get() = when (manager.kind) {
        "linuxShell" -> "Linux 终端"; "claude" -> "Claude Code"; "codex" -> "Codex"; "pi" -> "Pi"; "opencode" -> "OpenCode"; else -> "设备终端"
    }
    private var attached: TerminalSession? = null
    private val observer: () -> Unit = {
        if (::terminal.isInitialized) {
            manager.session?.let { if (attached !== it) { terminal.attachSession(it); attached = it } }
            terminal.onScreenUpdated()
            path.text = directory()
            path.contentDescription = "$agentName · ${path.text} · ${sessionStatus()}"
            path.setTextColor(if (manager.session?.isRunning == true) chrome.colors.foreground else chrome.colors.muted)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chrome = SessionChrome(this, SessionAppearance(this, terminal = true))
        fontSize = preferences.getFloat("fontSize", 14f)
        val colors = chrome.colors
        // Only change defaults; applications retain their own OSC/ANSI colors.
        mapOf(TextStyle.COLOR_INDEX_BACKGROUND to colors.background, TextStyle.COLOR_INDEX_FOREGROUND to colors.foreground, TextStyle.COLOR_INDEX_CURSOR to colors.accent).forEach { (role, color) ->
            val previous = TerminalColors.COLOR_SCHEME.mDefaultColors[role]
            manager.session?.emulator?.mColors?.mCurrentColors?.let { if (it[role] == previous) it[role] = color }
            TerminalColors.COLOR_SCHEME.mDefaultColors[role] = color
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(colors.bar) }
        val bar = chrome.bar()
        bar.addView(chrome.button("back", "返回工作台") { finish() })
        path = chrome.label(directory(), 14f).apply {
            typeface = Typeface.MONOSPACE; setSingleLine(); ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(chrome.dp(8), 0, chrome.dp(8), 0); background = chrome.ripple()
            setOnClickListener { chrome.show(AlertDialog.Builder(this@TerminalActivity).setTitle(agentName)
                .setMessage("${directory()}\n\n${sessionStatus()}").setPositiveButton("知道了", null).create()) }
        }
        bar.addView(path, LinearLayout.LayoutParams(0, chrome.dp(48), 1f))
        val more = chrome.button("more", "终端菜单") {}
        more.setOnClickListener { showMenu(more) }; bar.addView(more); root.addView(bar)
        terminal = TerminalView(this, null).apply {
            setTextSize((fontSize * resources.displayMetrics.scaledDensity).toInt()); setTypeface(Typeface.MONOSPACE)
            setTerminalViewClient(InputClient()); isFocusableInTouchMode = true; setBackgroundColor(colors.background)
        }
        root.addView(terminal, LinearLayout.LayoutParams(-1, 0, 1f))
        val keys = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(chrome.dp(4), 0, chrome.dp(4), 0) }
        listOf("ESC" to "\u001b", "TAB" to "\t").forEach { (label, bytes) -> keys.addView(key(label) { manager.session?.write(bytes) }) }
        ctrlKey = key("CTRL") { setCtrl(!ctrl) }; keys.addView(ctrlKey)
        listOf("^C" to "\u0003", "↑" to "\u001b[A", "↓" to "\u001b[B", "←" to "\u001b[D", "→" to "\u001b[C").forEach { (label, bytes) -> keys.addView(key(label) { manager.session?.write(bytes) }) }
        val keyBar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        keyBar.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(keys) }, LinearLayout.LayoutParams(0, chrome.dp(52), 1f))
        keyBar.addView(chrome.button("keyboard", "显示键盘") { keyboard() }); root.addView(keyBar)
        setContentView(root); chrome.insets(root)
        if (savedInstanceState == null || manager.session == null) TerminalService.start(this, kind)
        terminal.post { terminal.requestFocus(); observer() }
    }
    private fun directory(): String {
        val session = manager.session
        val reported = session?.emulator?.workingDirectoryUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?.takeIf { it.scheme == "file" && it.host in listOf("", "localhost", "127.0.0.1") }?.path
        return reported?.takeIf { it.startsWith('/') && it.length <= 4096 && it.none(Char::isISOControl) }
            ?: if (kind == "deviceShell") session?.cwd ?: java.io.File(filesDir, "workspaces").absolutePath else manager.launchDirectory
    }
    private fun sessionStatus(): String = when {
        manager.snapshot().optBoolean("stopping") -> "正在结束当前任务…"
        manager.session?.isRunning == true -> if (kind == "deviceShell") "Android Shell · 运行中" else "Ubuntu · 运行中"
        else -> "会话已退出 · 可在菜单重新启动"
    }
    private fun showMenu(anchor: View) {
        val running = manager.session?.isRunning == true
        val stopping = manager.snapshot().optBoolean("stopping")
        chrome.menu(anchor, agentName, sessionStatus(), listOf(
            SessionChrome.Item("copy", "粘贴", running && !stopping) { manager.session?.let { manager.onPasteTextFromClipboard(it) } },
            SessionChrome.Item("clear", "清空屏幕", attached != null) {
                // View-only: never write a shell command or Ctrl-L into an agent's input.
                manager.session?.emulator?.let { emulator ->
                    emulator.clearVisibleScreen(); terminal.onScreenUpdated()
                }
            },
            SessionChrome.Item("text", "终端字号") { fontDialog() },
            SessionChrome.Item("refresh", "重启 $agentName", !stopping) {
                val target = if (manager.kind == "deviceShell") manager.launchDirectory else (application as AgentMApplication).workingDirectories.snapshot().optString("path")
                chrome.confirm("重启 $agentName？", "当前终端任务会结束，并在 $target 新建会话。", "重启") { setCtrl(false); TerminalService.restart(this) }
            },
            SessionChrome.Item("stop", "终止会话", running && !stopping, danger = true) {
                chrome.confirm("终止当前会话？", "当前终端中的任务将被终止。", "终止") { manager.stop() }
            }
        ))
    }
    private fun fontDialog() {
        val label = chrome.label("${fontSize.toInt()} sp", 16f).apply { gravity = Gravity.CENTER }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(chrome.dp(24), chrome.dp(12), chrome.dp(24), 0); addView(label) }
        content.addView(SeekBar(this).apply {
            max = 18; progress = fontSize.toInt() - 10
            progressTintList = android.content.res.ColorStateList.valueOf(chrome.colors.accent); thumbTintList = progressTintList
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) { resizeFont((progress + 10).toFloat()); label.text = "${fontSize.toInt()} sp" } }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        })
        chrome.show(AlertDialog.Builder(this).setTitle("终端字号").setView(content).setPositiveButton("完成", null).create())
    }
    private fun resizeFont(size: Float) { fontSize = size.coerceIn(10f, 28f); terminal.setTextSize((fontSize * resources.displayMetrics.scaledDensity).toInt()); preferences.edit().putFloat("fontSize", fontSize).apply() }
    private fun setCtrl(value: Boolean) { ctrl = value; ctrlKey.isSelected = value; ctrlKey.setTextColor(if (value) chrome.colors.background else chrome.colors.muted); ctrlKey.background = chrome.ripple(if (value) chrome.colors.accent else chrome.colors.container, 12) }
    private fun key(label: String, action: () -> Unit) = chrome.label(label, 12f, chrome.colors.muted).apply {
        gravity = Gravity.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); background = chrome.ripple(chrome.colors.container, 12)
        layoutParams = LinearLayout.LayoutParams(chrome.dp(48), chrome.dp(48)).apply { setMargins(chrome.dp(2), 0, chrome.dp(2), 0) }
        contentDescription = if (label == "^C") "中断 Ctrl-C" else label; setOnClickListener { action() }
    }
    override fun onStart() { super.onStart(); manager.observe(observer); observer() }
    override fun onStop() { manager.unobserve(observer); super.onStop() }
    private fun keyboard() { terminal.requestFocus(); getSystemService(InputMethodManager::class.java).showSoftInput(terminal, InputMethodManager.SHOW_IMPLICIT) }
    private inner class InputClient : TerminalViewClient {
        override fun onScale(scale: Float): Float { resizeFont(fontSize * scale); return 1f }
        override fun onSingleTapUp(e: MotionEvent) = keyboard()
        override fun shouldBackButtonBeMappedToEscape() = false
        override fun shouldEnforceCharBasedInput() = true
        override fun shouldUseCtrlSpaceWorkaround() = false
        override fun isTerminalViewSelected() = true
        override fun copyModeChanged(copyMode: Boolean) {}
        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession) = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
        override fun onLongPress(event: MotionEvent) = false
        override fun readControlKey(): Boolean = ctrl.also { setCtrl(false) }
        override fun readAltKey() = false
        override fun readShiftKey() = false
        override fun readFnKey() = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession) = false
        override fun onEmulatorSet() {}
        override fun logError(tag: String, message: String) = manager.logError(tag, message)
        override fun logWarn(tag: String, message: String) = manager.logWarn(tag, message)
        override fun logInfo(tag: String, message: String) {}
        override fun logDebug(tag: String, message: String) {}
        override fun logVerbose(tag: String, message: String) {}
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) = manager.logError(tag, message)
        override fun logStackTrace(tag: String, e: Exception) = manager.logError(tag, e.javaClass.simpleName)
    }
}
