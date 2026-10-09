package dev.agentm.app

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.agentm.app.web.AgentWebActivity
import dev.agentm.app.web.WebAgentService
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference
import android.graphics.Bitmap
import java.io.File

/** Opt-in actual UI render regression; no prompts, credentials, or chat content are collected. */
@RunWith(AndroidJUnit4::class)
class OpenCodeWebUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as AgentMApplication
    private fun awaitMain(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 100000
        while (System.currentTimeMillis() < deadline) {
            var done = false
            instrumentation.runOnMainSync { done = predicate() }
            if (done) return
            Thread.sleep(200)
        }
        fail("OpenCode condition timed out")
    }
    private fun webView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) webView(view.getChildAt(index))?.let { return it }
        return null
    }
    private fun assertHome(activity: ActivityScenario<AgentWebActivity>) {
        val rendered = AtomicReference(JSONObject())
        var stableSince = 0L
        awaitMain {
            activity.onActivity { screen -> webView(screen.window.decorView)?.evaluateJavascript("""
                JSON.stringify({home:!!document.querySelector('[data-component="home-session-search"]'),
                  groupBy:typeof Map.groupBy, withResolvers:typeof Promise.withResolvers,
                  bridge:typeof window.AgentMHost, ready:document.readyState})
            """.trimIndent()) { raw ->
                runCatching { JSONObject(JSONTokener(raw).nextValue() as String) }.onSuccess(rendered::set)
            } }
            val state = rendered.get()
            val ready = state.optBoolean("home") && state.optString("ready") == "complete" &&
                state.optString("groupBy") == "function" && state.optString("withResolvers") == "function"
            if (!ready) stableSince = 0 else if (stableSince == 0L) stableSince = System.currentTimeMillis()
            ready && System.currentTimeMillis() - stableSince >= 2000
        }
        assertEquals(rendered.get().toString(), "undefined", rendered.get().getString("bridge"))
    }
    @Test fun rendersBeyondDocumentLoadAndAuthenticatedHealth() {
        assertTrue(app.packages.openCodeReady)
        assertFalse("Close managed Web sessions before this opt-in regression", app.webAgents.active)
        assertFalse("Close the interactive terminal before this opt-in regression", app.terminals.session?.isRunning == true)
        instrumentation.runOnMainSync { WebAgentService.start(app, app.webAgents.request("opencode")) }
        try {
            awaitMain { app.webAgents.current("opencode")?.state in setOf("ready", "failed") }
            val session = app.webAgents.current("opencode")!!
            assertEquals("ready", session.state)
            val pid = session.process!!.pid
            ActivityScenario.launch<AgentWebActivity>(Intent(app, AgentWebActivity::class.java).putExtra("kind", "opencode")).use { activity ->
                assertHome(activity)
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(app.getExternalFilesDir(null), "web-chrome.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                }
                activity.recreate()
                assertHome(activity)
                assertEquals(pid, session.process!!.pid)
                val prefs = app.getSharedPreferences("agent-browser", android.content.Context.MODE_PRIVATE)
                val previousDesktop = prefs.getBoolean("desktop-opencode", false)
                try {
                    prefs.edit().putBoolean("desktop-opencode", true).commit()
                    activity.recreate(); assertHome(activity)
                    val desktop = AtomicReference("")
                    awaitMain {
                        activity.onActivity { screen -> webView(screen.window.decorView)!!.evaluateJavascript(
                            "!navigator.userAgent.includes('Mobile') && document.querySelector('meta[name=viewport]').content==='width=1280' && innerWidth>=1200"
                        ) { desktop.set(it) } }
                        desktop.get() == "true"
                    }
                    activity.onActivity { app.webAgents.restart("opencode") }
                    awaitMain { app.webAgents.current("opencode")?.let { it.id != session.id && it.state == "ready" } == true }
                    assertFalse(session.process!!.isRunning)
                    assertHome(activity)
                    activity.onActivity { assertEquals(app.webAgents.current("opencode")!!.port, android.net.Uri.parse(webView(it.window.decorView)!!.url).port) }
                } finally { prefs.edit().putBoolean("desktop-opencode", previousDesktop).commit() }
            }
            println("OPENCODE_WEBUI_RENDER_OK: actual home, required APIs, no bridge, recreation, desktop viewport, owned service restart/reconnect")
        } finally { instrumentation.runOnMainSync { app.webAgents.stop("opencode") }; awaitMain { app.webAgents.current("opencode")?.active != true } }
    }
}
