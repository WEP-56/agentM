package dev.agentm.app

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.agentm.app.packages.PackageService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Opt-in real package test; no account credentials or model prompts are submitted. */
@RunWith(AndroidJUnit4::class)
class CodexIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as AgentMApplication
    private fun awaitMain(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 45000
        while (System.currentTimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = predicate() }
            if (ready) return
            Thread.sleep(100)
        }
        fail("Codex PTY condition timed out")
    }
    private fun perform(action: String) {
        instrumentation.runOnMainSync { app.packages.enqueue(action); PackageService.start(app) }
        val deadline = System.currentTimeMillis() + 1200000
        while (app.packages.busy && System.currentTimeMillis() < deadline) Thread.sleep(300)
        val result = app.packages.snapshot()
        assertFalse(result.toString(), app.packages.busy)
        assertEquals(result.toString(), "done", result.getString("phase"))
    }
    @Test fun installsCodexRetainsSessionAndPreservesHomesOnRemoval() {
        assertTrue("Ubuntu must be installed", app.linux.ready)
        assertTrue("Prepare developer tools before this test", app.packages.toolsReady)
        instrumentation.runOnMainSync { app.terminals.stop() }
        awaitMain { app.terminals.session?.isRunning != true }
        val claudeBefore = app.packages.snapshot().optJSONObject("claude")?.getString("slot")
        if (!app.packages.codexReady) perform("installCodex")
        perform("checkCodex")
        val installed = app.packages.snapshot().getJSONObject("codex")
        val capability = installed.getJSONObject("sandboxProbe")
        println("CODEX_SANDBOX_PROBE $capability")
        if (capability.getString("status") == "passed") {
            assertEquals(0, capability.getInt("exitCode"))
            assertTrue(capability.getString("output").contains("AGENTM_CODEX_SANDBOX_OK"))
        } else { assertNotEquals(0, capability.getInt("exitCode")); assertTrue(capability.getString("output").isNotBlank()) }
        val version = app.linux.runtime.run(script = "codex --version", timeoutSeconds = 30)
        assertEquals(version.output, 0, version.code)
        assertTrue(version.output.contains("codex-cli ${installed.getString("version")}"))
        val command = app.packages.agentCommand("codex")
        assertFalse(command.any { it.contains("danger") || it == "--yolo" || it == "never" })
        val marker = File(app.linux.runtime.home, ".codex/.agentm-preserve-${UUID.randomUUID()}")
        marker.parentFile!!.mkdirs(); marker.writeText("keep-codex-data")
        try {
            ActivityScenario.launch<TerminalActivity>(Intent(app, TerminalActivity::class.java).putExtra("kind", "codex")).use { activity ->
                awaitMain { app.terminals.session?.isRunning == true && app.terminals.session?.emulator?.screen?.transcriptText?.contains("Codex", ignoreCase = true) == true }
                val pid = app.terminals.session!!.pid
                activity.onActivity {
                    assertEquals("codex", app.terminals.kind)
                    assertThrows(IllegalStateException::class.java) { app.packages.enqueue("removeCodex") }
                    assertThrows(IllegalStateException::class.java) { app.terminals.requireOpenable("claude") }
                }
                activity.recreate()
                activity.onActivity { assertEquals(pid, app.terminals.session!!.pid); app.terminals.stop() }
                awaitMain { app.terminals.session?.isRunning == false }
            }
            perform("removeCodex")
            assertFalse(app.packages.codexReady)
            assertEquals("keep-codex-data", marker.readText())
            assertEquals(claudeBefore, app.packages.snapshot().optJSONObject("claude")?.getString("slot"))
            perform("installCodex")
            assertTrue(app.packages.codexReady)
            assertEquals("keep-codex-data", marker.readText())
            assertEquals(claudeBefore, app.packages.snapshot().optJSONObject("claude")?.getString("slot"))
            println("CODEX_INTEGRATION_OK: version, real PTY, recreation, stop, removal/reinstall, data retention, independent Claude slot")
        } finally { instrumentation.runOnMainSync { app.terminals.stop() }; marker.delete() }
    }
}
