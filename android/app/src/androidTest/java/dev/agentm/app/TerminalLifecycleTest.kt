package dev.agentm.app

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as AgentMApplication

    private fun awaitMain(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10000
        while (System.currentTimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = predicate() }
            if (ready) return
            Thread.sleep(50)
        }
        fail("Terminal condition was not reached within 10 seconds")
    }

    @Test fun opensRealPtyAndRetainsShellAcrossActivityRecreation() {
        try {
            var pid = -1
            ActivityScenario.launch(TerminalActivity::class.java).use { screen ->
                awaitMain { app.terminals.session?.isRunning == true }
                screen.onActivity {
                    val session = app.terminals.session!!
                    pid = session.pid
                    // The expected output is not present in the echoed command.
                    session.write("test -t 0 && AGENTM_REENTRY=42; printf 'PTY_%s\\n' \"\$AGENTM_REENTRY\"\r")
                }
                awaitMain { app.terminals.session?.emulator?.screen?.transcriptText?.contains("PTY_42") == true }
                screen.recreate()
                screen.onActivity { assertEquals(pid, app.terminals.session!!.pid) }
            }
            ActivityScenario.launch(TerminalActivity::class.java).use { screen ->
                screen.onActivity {
                    assertEquals(pid, app.terminals.session!!.pid)
                    app.terminals.session!!.write("printf 'REOPEN_%s\\n' \"\$AGENTM_REENTRY\"\r")
                }
                awaitMain { app.terminals.session?.emulator?.screen?.transcriptText?.contains("REOPEN_42") == true }
                screen.onActivity { app.terminals.stop() }
                awaitMain { app.terminals.session?.isRunning == false }
            }
        } finally {
            instrumentation.runOnMainSync { app.terminals.stop() }
        }
    }
}
