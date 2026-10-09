package dev.agentm.app

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

class TerminalActivity : ComponentActivity() {
    private val manager get() = (application as AgentMApplication).terminals
    private lateinit var terminal: TerminalView
    private lateinit var status: TextView
    private var fontSize = 14f
    private var ctrl = false
    private val kind get() = intent.getStringExtra("kind") ?: manager.kind
    private val observer: () -> Unit = {
        if (::terminal.isInitialized) {
            manager.session?.let { terminal.attachSession(it) }
            terminal.onScreenUpdated()
            status.text = if (manager.session?.isRunning == true) when (manager.kind) {
                "linuxShell" -> "Linux 终端 · Ubuntu"
                "claude" -> "Claude Code · Ubuntu"
                "codex" -> "Codex · Ubuntu"
                else -> "设备终端 · Android Shell"
            } else "会话已退出"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(20, 18, 24)) }
        val bar = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        bar.addView(button("返回") { finish() })
        status = TextView(this).apply { setTextColor(Color.WHITE); textSize = 15f; text = "设备终端 · Android Shell" }
        bar.addView(status, LinearLayout.LayoutParams(0, dp(52), 1f))
        bar.addView(button("关闭") {
            AlertDialog.Builder(this).setTitle("关闭终端会话？").setMessage("当前终端中的任务将被终止。返回工作台会保留会话。")
                .setNegativeButton("取消", null).setPositiveButton("关闭") { _, _ -> manager.stop() }.show()
        })
        root.addView(bar)
        root.addView(TextView(this).apply {
            text = if (kind != "deviceShell") "Ubuntu · /workspace 为持久工作区" else "Android 设备 Shell · 不需要启动 Linux"
            setTextColor(Color.rgb(187, 177, 205)); textSize = 12f; setPadding(dp(12), dp(4), dp(12), dp(8))
        })
        terminal = TerminalView(this, null).apply {
            // Termux creates its renderer in setTextSize; setTypeface reads that renderer.
            setTextSize((fontSize * resources.displayMetrics.scaledDensity).toInt())
            setTypeface(Typeface.MONOSPACE)
            setTerminalViewClient(InputClient())
            isFocusableInTouchMode = true
        }
        root.addView(terminal, LinearLayout.LayoutParams(-1, 0, 1f))
        val keys = LinearLayout(this)
        listOf("ESC" to "\u001b", "TAB" to "\t", "Ctrl-C" to "\u0003", "↑" to "\u001b[A", "↓" to "\u001b[B", "←" to "\u001b[D", "→" to "\u001b[C").forEach { (label, bytes) ->
            keys.addView(button(label) { manager.session?.write(bytes) })
        }
        keys.addView(button("Ctrl") { ctrl = !ctrl; (it as Button).isSelected = ctrl })
        keys.addView(button("键盘") { keyboard() })
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(keys) })
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom)); insets
        }
        // Usually already started by the bridge; notification and service own the shell.
        TerminalService.start(this, kind)
        terminal.post { manager.session?.let { terminal.attachSession(it) }; terminal.requestFocus(); observer() }
    }
    override fun onStart() { super.onStart(); manager.observe(observer); observer() }
    override fun onStop() { manager.unobserve(observer); super.onStop() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun button(label: String, action: (android.view.View) -> Unit): Button = Button(this).apply {
        text = label; textSize = 12f; isAllCaps = false; minWidth = dp(48); minimumWidth = dp(48)
        layoutParams = LinearLayout.LayoutParams(-2, dp(48)); setOnClickListener(action)
    }
    private fun keyboard() {
        terminal.requestFocus()
        getSystemService(InputMethodManager::class.java).showSoftInput(terminal, InputMethodManager.SHOW_IMPLICIT)
    }
    private inner class InputClient : TerminalViewClient {
        override fun onScale(scale: Float): Float {
            fontSize = (fontSize * scale).coerceIn(10f, 28f)
            terminal.setTextSize((fontSize * resources.displayMetrics.scaledDensity).toInt())
            return 1f
        }
        override fun onSingleTapUp(e: MotionEvent) = keyboard()
        override fun shouldBackButtonBeMappedToEscape() = false
        override fun shouldEnforceCharBasedInput() = true
        override fun shouldUseCtrlSpaceWorkaround() = false
        override fun isTerminalViewSelected() = true
        override fun copyModeChanged(copyMode: Boolean) {}
        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession) = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
        override fun onLongPress(event: MotionEvent) = false
        override fun readControlKey(): Boolean = ctrl.also { ctrl = false }
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
