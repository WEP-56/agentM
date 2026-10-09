package dev.agentm.app

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.agentm.app.packages.PackageService
import dev.agentm.app.web.WebAgentService
import dev.agentm.app.web.WebHealth
import dev.agentm.app.web.AgentWebActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Opt-in native/service/HTTP test; user drives real WebUI interactions and account login. */
@RunWith(AndroidJUnit4::class)
class WebAgentsIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as AgentMApplication
    private fun awaitMain(timeout: Long = 110000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = predicate() }
            if (ready) return
            Thread.sleep(200)
        }
        fail("Web state timed out: ${app.webAgents.snapshot()}")
    }
    private fun perform(action: String) {
        instrumentation.runOnMainSync { app.packages.enqueue(action); PackageService.start(app) }
        val deadline = System.currentTimeMillis() + 1800000
        while (app.packages.busy && System.currentTimeMillis() < deadline) Thread.sleep(400)
        val state = app.packages.snapshot()
        assertFalse(state.toString(), app.packages.busy)
        assertEquals(state.toString(), "done", state.getString("phase"))
    }
    private fun start(kind: String) {
        instrumentation.runOnMainSync { val session = app.webAgents.request(kind); WebAgentService.start(app, session) }
        awaitMain { app.webAgents.current(kind)?.state in setOf("ready", "failed", "exited") }
        assertEquals(app.webAgents.snapshot().toString() + "\n" + app.webAgents.current(kind)!!.diagnostic, "ready", app.webAgents.current(kind)!!.state)
    }
    private fun webView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) webView(view.getChildAt(index))?.let { return it }
        return null
    }
    private fun checkNativeWebView(kind: String) {
        val session = app.webAgents.current(kind)!!
        val oldPid = session.process!!.pid
        ActivityScenario.launch<AgentWebActivity>(Intent(app, AgentWebActivity::class.java).putExtra("kind", kind)).use { activity ->
            val result = AtomicReference("")
            awaitMain {
                activity.onActivity { screen ->
                    webView(screen.window.decorView)?.evaluateJavascript("JSON.stringify({origin:location.origin,bridge:typeof window.AgentMHost,ready:document.readyState})") { result.set(it) }
                }
                result.get().contains("127.0.0.1:${session.port}") && result.get().contains("undefined") && result.get().contains("complete")
            }
            activity.onActivity { screen ->
                val endpoint = if (kind == "opencode") "/global/health" else "/"
                webView(screen.window.decorView)!!.evaluateJavascript("window.__agentmProbe=0;fetch('$endpoint').then(r=>window.__agentmProbe=r.status).catch(()=>window.__agentmProbe=-1)", null)
            }
            result.set("")
            awaitMain {
                activity.onActivity { screen -> webView(screen.window.decorView)!!.evaluateJavascript("window.__agentmProbe") { result.set(it) } }
                result.get() == "200"
            }
            activity.recreate()
            activity.onActivity { assertEquals(oldPid, app.webAgents.current(kind)!!.process!!.pid) }
        }
        assertEquals(oldPid, app.webAgents.current(kind)!!.process!!.pid)
        assertTrue(session.process!!.isRunning)
        println("WEBVIEW_ISOLATION_OK $kind: authenticated fetch, no management bridge, Activity recreation retains PID")
    }
    @Test fun installsBothAndSeparatesAuthenticatedWebLifecycles() {
        assertTrue(app.linux.ready && app.packages.toolsReady)
        instrumentation.runOnMainSync { app.terminals.stop(); app.webAgents.stopAll() }
        awaitMain { app.terminals.session?.isRunning != true && !app.webAgents.active }
        val before = listOf("claude", "codex", "pi").associateWith { app.packages.snapshot().optJSONObject(it)?.getString("slot") }
        if (!app.packages.openCodeReady) perform("installOpenCode")
        if (!app.packages.dshReady) perform("installDsh")
        // Occupy the preferred OpenCode port without stopping its owner.
        val occupied = runCatching { ServerSocket(4096, 1, InetAddress.getByName("127.0.0.1")) }.getOrNull()
        val marker = File(app.linux.runtime.home, ".dsh/.agentm-preserve-${UUID.randomUUID()}")
        marker.parentFile!!.mkdirs(); marker.writeText("preserve-dsh")
        try {
            start("opencode")
            val openCode = app.webAgents.current("opencode")!!
            if (occupied != null) assertNotEquals(4096, openCode.port)
            assertEquals(401, WebHealth.get(openCode.port, "/global/health").code)
            assertTrue(WebHealth.openCode(openCode.port, openCode.password, openCode.version))
            instrumentation.runOnMainSync {
                assertSame(openCode, app.webAgents.request("opencode"))
                assertThrows(IllegalStateException::class.java) { app.packages.enqueue("removeOpenCode") }
                assertThrows(IllegalStateException::class.java) { app.terminals.requireOpenable("opencode") }
                assertThrows(IllegalArgumentException::class.java) { app.terminals.requireOpenable("dsh") }
            }
            start("dsh")
            val dsh = app.webAgents.current("dsh")!!
            assertNotEquals(openCode.port, dsh.port)
            assertEquals(401, WebHealth.get(dsh.port, "/").code)
            assertEquals(401, WebHealth.get(dsh.port, "/?token=${"x".repeat(43)}").code)
            val page = WebHealth.get(dsh.port, "/", cookie = dsh.cookie)
            assertEquals(200, page.code)
            assertTrue(page.body.contains("<html", true))
            checkNativeWebView("opencode")
            checkNativeWebView("dsh")
            assertFalse(app.webAgents.snapshot().toString().contains(dsh.token!!))
            assertFalse(app.webAgents.snapshot().toString().contains(openCode.password))
            instrumentation.runOnMainSync { app.webAgents.stop("dsh") }
            awaitMain { app.webAgents.current("dsh")?.active == false }
            assertTrue(WebHealth.openCode(openCode.port, openCode.password, openCode.version))
            start("dsh")
            assertNotEquals(dsh.id, app.webAgents.current("dsh")!!.id)
            assertEquals("preserve-dsh", marker.readText())
            instrumentation.runOnMainSync { app.webAgents.stopAll() }
            awaitMain { !app.webAgents.active }
            perform("removeDsh")
            assertEquals("preserve-dsh", marker.readText())
            perform("installDsh")
            ActivityScenario.launch<TerminalActivity>(Intent(app, TerminalActivity::class.java).putExtra("kind", "opencode")).use { activity ->
                awaitMain { app.terminals.session?.isRunning == true && app.terminals.session?.emulator?.screen?.transcriptText?.isNotBlank() == true }
                val pid = app.terminals.session!!.pid
                activity.recreate()
                activity.onActivity { assertEquals("opencode", app.terminals.kind); assertEquals(pid, app.terminals.session!!.pid); app.terminals.stop() }
                awaitMain { app.terminals.session?.isRunning == false }
            }
            before.forEach { (kind, slot) -> assertEquals(slot, app.packages.snapshot().optJSONObject(kind)?.getString("slot")) }
            println("WEB_AGENTS_OK: fixed packages, independent authenticated endpoints, dynamic ports, duplicate start, selective stop/restart, no credential snapshot, DSH uninstall retention, existing Agent slots preserved")
        } finally { occupied?.close(); instrumentation.runOnMainSync { app.webAgents.stopAll() }; marker.delete() }
    }
}
