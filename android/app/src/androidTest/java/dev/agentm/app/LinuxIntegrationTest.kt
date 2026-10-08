package dev.agentm.app

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.agentm.app.linux.LinuxInstallService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit opt-in integration test: downloads the pinned official Ubuntu image on first run. */
@RunWith(AndroidJUnit4::class)
class LinuxIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as AgentMApplication
    private fun awaitMain(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20000
        while (System.currentTimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = predicate() }
            if (ready) return
            Thread.sleep(100)
        }
        fail("Linux PTY condition timed out")
    }

    @Test fun installsVerifiedUbuntuAndRunsARealLinuxPty() {
        if (!app.linux.ready) {
            instrumentation.runOnMainSync { app.linux.enqueue(); LinuxInstallService.start(app) }
            val deadline = System.currentTimeMillis() + 600000
            var previous = ""
            while (app.linux.busy && System.currentTimeMillis() < deadline) {
                val state = app.linux.snapshot()
                val phase = state.getString("phase")
                if (phase != previous) { println("LINUX_INSTALL $state"); previous = phase }
                Thread.sleep(500)
            }
        }
        assertTrue(app.linux.snapshot().toString(), app.linux.ready)
        val probe = app.linux.runtime.probe()
        assertEquals(probe.output, 0, probe.code)
        assertTrue(probe.output, probe.output.contains("AGENTM_OS=ubuntu:24.04"))
        val marker = File(app.filesDir, "workspaces/.agentm-guest-stop-test")
        marker.delete()
        try {
            val launch = Intent(app, TerminalActivity::class.java).putExtra("kind", "linuxShell")
            ActivityScenario.launch<TerminalActivity>(launch).use { activity ->
                awaitMain { app.terminals.session?.isRunning == true }
                activity.onActivity {
                    assertEquals("linuxShell", app.terminals.kind)
                    app.terminals.session!!.write("test -t 0 && printf 'LINUX_PTY_%s\\n' \"\$(dpkg --print-architecture)\"\r")
                }
                awaitMain { app.terminals.session?.emulator?.screen?.transcriptText?.contains("LINUX_PTY_${if (app.linux.abi == "x86_64") "amd64" else "arm64"}") == true }
                val pid = app.terminals.session!!.pid
                activity.recreate()
                activity.onActivity {
                    assertEquals(pid, app.terminals.session!!.pid)
                    app.terminals.session!!.write("(sleep 5; printf 'leaked' > /workspace/.agentm-guest-stop-test) & printf 'CHILD_%s\\n' started\r")
                }
                awaitMain { app.terminals.session?.emulator?.screen?.transcriptText?.contains("CHILD_started") == true }
                activity.onActivity { app.terminals.stop() }
                awaitMain { app.terminals.session?.isRunning == false }
                Thread.sleep(5500)
                assertFalse("A Linux descendant survived terminal stop", marker.exists())
            }
        } finally {
            instrumentation.runOnMainSync { app.terminals.stop() }
            marker.delete()
        }
    }
}
