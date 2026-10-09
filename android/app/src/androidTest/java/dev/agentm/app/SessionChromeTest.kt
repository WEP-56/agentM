package dev.agentm.app

import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in lifecycle test of a fresh shell only; no user commands or existing processes are reused. */
@RunWith(AndroidJUnit4::class)
class SessionChromeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as AgentMApplication
    private fun awaitMain(predicate: () -> Boolean) {
        val until = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < until) {
            var ready = false; instrumentation.runOnMainSync { ready = predicate() }
            if (ready) return
            Thread.sleep(100)
        }
        fail("Terminal chrome condition timed out")
    }
    private fun allViews(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { allViews(view.getChildAt(it)) } else emptyList()
    @Test fun directoryClearAndRestartKeepPtyOwnership() {
        assertTrue(app.linux.ready)
        assertFalse("Close user terminals before this opt-in test", app.terminals.session?.isRunning == true)
        assertFalse("Close Web services before this opt-in test", app.webAgents.active)
        val expectedDirectory = app.workingDirectories.current().path
        try {
            ActivityScenario.launch<TerminalActivity>(Intent(app, TerminalActivity::class.java).putExtra("kind", "linuxShell")).use { screen ->
                awaitMain { app.terminals.session?.emulator?.workingDirectoryUri?.let { android.net.Uri.parse(it).path } == expectedDirectory }
                val first = app.terminals.session!!
                screen.onActivity {
                    first.write("cd /tmp; AGENTM_CHROME=kept\r")
                }
                awaitMain { first.emulator.workingDirectoryUri == "file://localhost/tmp" }
                screen.onActivity {
                    val views = allViews(it.window.decorView)
                    assertTrue(views.any { view -> view is TextView && view.text.toString() == "/tmp" })
                    assertTrue(views.any { view -> view.contentDescription == "终端菜单" })
                    first.emulator.clearVisibleScreen()
                    assertTrue(first.isRunning)
                    first.write("printf 'CLEAR_%s\\n' \"\$AGENTM_CHROME\"\r")
                }
                awaitMain { first.emulator.screen.transcriptText.contains("CLEAR_kept") }
                screen.recreate()
                screen.onActivity { assertSame(first, app.terminals.session) }
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(app.getExternalFilesDir(null), "terminal-chrome.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                }
                screen.onActivity { TerminalService.restart(it) }
                awaitMain { app.terminals.session !== first && app.terminals.session?.emulator?.workingDirectoryUri?.let { android.net.Uri.parse(it).path } == expectedDirectory }
                val second = app.terminals.session!!
                assertFalse(first.isRunning)
                screen.recreate()
                screen.onActivity { assertSame(second, app.terminals.session); app.terminals.stop() }
                awaitMain { !second.isRunning }
                screen.recreate()
                screen.onActivity { assertSame("Recreating a stopped terminal must not restart it", second, app.terminals.session); assertFalse(second.isRunning) }
            }
        } finally { instrumentation.runOnMainSync { app.terminals.stop() }; awaitMain { app.terminals.session?.isRunning != true } }
    }
}
