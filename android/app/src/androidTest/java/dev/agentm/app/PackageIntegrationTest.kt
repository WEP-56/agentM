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

/** Explicit opt-in: downloads real pinned tools/Claude; never submits prompts or credentials. */
@RunWith(AndroidJUnit4::class)
class PackageIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as AgentMApplication
    private fun perform(action: String) {
        instrumentation.runOnMainSync {
            val id = app.packages.enqueue(action)
            assertEquals(id, app.packages.enqueue(action))
            assertThrows(IllegalStateException::class.java) { app.terminals.requireOpenable("linuxShell") }
            PackageService.start(app)
        }
        val deadline = System.currentTimeMillis() + 1200000
        var last = ""
        while (app.packages.busy && System.currentTimeMillis() < deadline) {
            val snapshot = app.packages.snapshot()
            val phase = snapshot.getString("phase")
            if (phase != last) { println("PACKAGE_TASK $action $snapshot"); last = phase }
            Thread.sleep(500)
        }
        val result = app.packages.snapshot()
        println("PACKAGE_RESULT $action $result")
        assertFalse(result.toString(), app.packages.busy)
        assertEquals(result.toString(), "done", result.getString("phase"))
    }
    private fun awaitMain(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 45000
        while (System.currentTimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = predicate() }
            if (ready) return
            Thread.sleep(100)
        }
        fail("Claude PTY condition timed out")
    }

    @Test fun installsToolsAndClaudeRetainsPtyAndPreservesDataOnUninstall() {
        assertTrue("Install Ubuntu before this test", app.linux.ready)
        instrumentation.runOnMainSync { app.terminals.stop() }
        awaitMain { app.terminals.session?.isRunning != true }
        if (!app.packages.toolsReady) perform("installTools")
        if (!app.packages.claudeReady) perform("installClaude")
        perform("checkPackages")
        // A version command alone must not reclaim a slot with mismatched ownership metadata.
        val record = app.packages.snapshot().getJSONObject("claude")
        val provenance = File(app.linux.runtime.managed, "slots/${record.getString("slot")}/.agentm-slot.json")
        val original = provenance.readText()
        try {
            provenance.writeText(org.json.JSONObject(original).put("sha256", "invalid").toString())
            assertFalse(app.packages.claudeReady)
            instrumentation.runOnMainSync { app.packages.enqueue("checkPackages") }
            app.packages.execute {}
            assertEquals("failed", app.packages.snapshot().getString("phase"))
            assertFalse(app.packages.claudeReady)
        } finally { provenance.writeText(original) }
        perform("checkPackages")
        val output = app.linux.runtime.run(script = "node --version; npm --version; git --version; python3 --version; claude --version", timeoutSeconds = 60)
        assertEquals(output.output, 0, output.code)
        println("PACKAGE_SHELL ${output.output}")
        val marker = File(app.linux.runtime.home, ".claude/.agentm-preserve-${UUID.randomUUID()}")
        marker.parentFile!!.mkdirs(); marker.writeText("keep-me")
        try {
            ActivityScenario.launch<TerminalActivity>(Intent(app, TerminalActivity::class.java).putExtra("kind", "claude")).use { activity ->
                awaitMain { app.terminals.session?.isRunning == true && app.terminals.session?.emulator?.screen?.transcriptText?.contains("Claude Code") == true }
                val pid = app.terminals.session!!.pid
                activity.onActivity {
                    assertEquals("claude", app.terminals.kind)
                    assertThrows(IllegalStateException::class.java) { app.packages.enqueue("removeClaude") }
                }
                activity.recreate()
                activity.onActivity { assertEquals(pid, app.terminals.session!!.pid) }
                activity.onActivity { app.terminals.stop() }
                awaitMain { app.terminals.session?.isRunning == false }
            }
            perform("removeClaude")
            assertFalse(app.packages.claudeReady)
            assertEquals("keep-me", marker.readText())
            perform("installClaude")
            assertTrue(app.packages.claudeReady)
            assertEquals("keep-me", marker.readText())
        } finally {
            instrumentation.runOnMainSync { app.terminals.stop() }
            marker.delete()
        }
    }
}
