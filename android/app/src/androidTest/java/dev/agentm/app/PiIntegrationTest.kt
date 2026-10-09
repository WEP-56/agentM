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

/** Opt-in real install + local tools + lifecycle regression; does not submit a model prompt. */
@RunWith(AndroidJUnit4::class)
class PiIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as AgentMApplication
    private fun awaitMain(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 60000
        while (System.currentTimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = predicate() }
            if (ready) return
            Thread.sleep(100)
        }
        fail("Pi PTY timed out: ${app.terminals.session?.emulator?.screen?.transcriptText}")
    }
    private fun perform(action: String) {
        instrumentation.runOnMainSync { app.packages.enqueue(action); PackageService.start(app) }
        val deadline = System.currentTimeMillis() + 1200000
        while (app.packages.busy && System.currentTimeMillis() < deadline) Thread.sleep(300)
        val result = app.packages.snapshot()
        assertFalse(result.toString(), app.packages.busy)
        assertEquals(result.toString(), "done", result.getString("phase"))
    }
    @Test fun installsPiChecksLocalToolsAndPreservesDataAcrossReinstall() {
        assertTrue("Ubuntu must be installed", app.linux.ready)
        assertTrue("Prepare developer tools before this test", app.packages.toolsReady)
        instrumentation.runOnMainSync { app.terminals.stop() }
        awaitMain { app.terminals.session?.isRunning != true }
        val before = listOf("claude", "codex").associateWith { app.packages.snapshot().optJSONObject(it)?.getString("slot") }
        if (!app.packages.piReady) perform("installPi")
        perform("checkPackages")
        val installed = app.packages.snapshot().getJSONObject("pi")
        assertTrue(installed.getString("probeOutput").contains("AGENTM_PI_TOOLS_OK"))
        assertTrue(installed.getString("probeOutput").contains("AGENTM_PI_WASM_OK"))
        println("PI_LOCAL_PROBE ${installed.getString("probeOutput")}")

        // A missing external runtime asset must not be reported as an intact install.
        val wasm = File(app.linux.runtime.managed, "slots/${installed.getString("slot")}/package/node_modules/quickjs-wasi/quickjs.wasm")
        val moved = File(wasm.parentFile, "quickjs.wasm.test-backup")
        assertFalse(moved.exists())
        assertTrue(wasm.renameTo(moved))
        try {
            assertFalse(app.packages.piReady)
            assertThrows(IllegalStateException::class.java) { app.packages.agentCommand("pi") }
        } finally { assertTrue(moved.renameTo(wasm)) }
        assertTrue(app.packages.piReady)

        val marker = File(app.linux.runtime.home, ".pi/agent/.agentm-preserve-${UUID.randomUUID()}")
        val workspaceMarker = File(app.linux.runtime.workspace, ".agentm-pi-preserve-${UUID.randomUUID()}")
        marker.parentFile!!.mkdirs(); marker.writeText("keep-pi-data"); workspaceMarker.writeText("keep-project")
        try {
            ActivityScenario.launch<TerminalActivity>(Intent(app, TerminalActivity::class.java).putExtra("kind", "pi")).use { activity ->
                awaitMain { app.terminals.session?.isRunning == true && app.terminals.session?.emulator?.screen?.transcriptText?.contains(installed.getString("version")) == true }
                val pid = app.terminals.session!!.pid
                activity.onActivity {
                    assertEquals("pi", app.terminals.kind)
                    assertSame(app.terminals.session, app.terminals.open("pi"))
                    assertThrows(IllegalStateException::class.java) { app.packages.enqueue("removePi") }
                    assertThrows(IllegalStateException::class.java) { app.terminals.requireOpenable("claude") }
                }
                activity.recreate()
                activity.onActivity { assertEquals(pid, app.terminals.session!!.pid) }
                println("PI_PTY_SCREEN ${app.terminals.session?.emulator?.screen?.transcriptText}")
            }
            // Closing the Activity only detaches the view. Re-entry uses the same process.
            val retainedPid = app.terminals.session!!.pid
            ActivityScenario.launch<TerminalActivity>(Intent(app, TerminalActivity::class.java).putExtra("kind", "pi")).use { activity ->
                activity.onActivity { assertTrue(app.terminals.session!!.isRunning); assertEquals(retainedPid, app.terminals.session!!.pid); app.terminals.stop() }
                awaitMain { app.terminals.session?.isRunning == false }
            }
            perform("removePi")
            assertFalse(app.packages.piReady)
            assertEquals("keep-pi-data", marker.readText())
            assertEquals("keep-project", workspaceMarker.readText())
            perform("installPi")
            assertTrue(app.packages.piReady)
            assertEquals("keep-pi-data", marker.readText())
            assertEquals("keep-project", workspaceMarker.readText())
            before.forEach { (kind, slot) -> assertEquals(slot, app.packages.snapshot().optJSONObject(kind)?.getString("slot")) }
            println("PI_INTEGRATION_OK: install, local tools/WASM, missing asset refusal, PTY, recreation/re-entry, stop, removal/reinstall, data retention")
        } finally { instrumentation.runOnMainSync { app.terminals.stop() }; marker.delete(); workspaceMarker.delete() }
    }
}
