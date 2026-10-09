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
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference
import android.graphics.Bitmap
import java.io.File

/** Read-only browser RPC regression: no workspace creation, prompts, login or file contents. */
@RunWith(AndroidJUnit4::class)
class DshWebUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as AgentMApplication
    private fun awaitMain(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 100000
        while (System.currentTimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = predicate() }
            if (ready) return
            Thread.sleep(200)
        }
        fail("DSH WebUI condition timed out")
    }
    private fun webView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) webView(view.getChildAt(index))?.let { return it }
        return null
    }
    @Test fun listsWorkspaceDirectoryWithComposedAbortSignal() {
        assertTrue(app.packages.dshReady)
        assertFalse("Close managed Web sessions before this opt-in regression", app.webAgents.active)
        val preferences = app.getSharedPreferences("agent-browser", android.content.Context.MODE_PRIVATE)
        val previousDesktop = preferences.getBoolean("desktop-dsh", false)
        preferences.edit().putBoolean("desktop-dsh", false).commit()
        instrumentation.runOnMainSync { WebAgentService.start(app, app.webAgents.request("dsh")) }
        try {
            awaitMain { app.webAgents.current("dsh")?.state in setOf("ready", "failed") }
            assertEquals("ready", app.webAgents.current("dsh")!!.state)
            val pid = app.webAgents.current("dsh")!!.process!!.pid
            ActivityScenario.launch<AgentWebActivity>(Intent(app, AgentWebActivity::class.java).putExtra("kind", "dsh")).use { activity ->
                val state = AtomicReference("")
                awaitMain {
                    activity.onActivity { screen -> webView(screen.window.decorView)?.evaluateJavascript(
                        "document.readyState==='complete' && typeof AbortSignal.any==='function' && typeof window.AgentMHost==='undefined'"
                    ) { state.set(it) } }
                    state.get() == "true"
                }
                for (desktop in listOf(true, false, true)) {
                    state.set("")
                    activity.onActivity { it.setDesktopMode(desktop) }
                    awaitMain {
                        activity.onActivity { screen -> webView(screen.window.decorView)!!.evaluateJavascript("""
                            document.readyState==='complete' && typeof AbortSignal.any==='function' &&
                            (navigator.userAgent.includes('Mobile') === ${!desktop}) &&
                            (${if (desktop) "innerWidth>=1200 && [...document.querySelectorAll('meta[name=viewport]')].every(meta=>meta.content==='width=1280')" else "innerWidth<600 && document.querySelector('meta[name=viewport]').content.includes('width=device-width')"})
                        """.trimIndent()) { state.set(it) } }
                        state.get() == "true"
                    }
                    assertEquals("Display mode must not restart DSH", pid, app.webAgents.current("dsh")!!.process!!.pid)
                }
                activity.recreate()
                state.set("")
                awaitMain {
                    activity.onActivity { screen -> webView(screen.window.decorView)!!.evaluateJavascript(
                        "document.readyState==='complete' && innerWidth>=1200 && typeof AbortSignal.any==='function' && !navigator.userAgent.includes('Mobile')"
                    ) { state.set(it) } }
                    state.get() == "true"
                }
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(app.getExternalFilesDir(null), "dsh-desktop-091.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                }
                activity.onActivity { screen -> webView(screen.window.decorView)!!.evaluateJavascript("""
                    window.__agentmDirectoryCheck='pending';
                    (async () => {
                      try {
                        const lifetime = new AbortController();
                        const signal = AbortSignal.any([lifetime.signal, AbortSignal.timeout(15000)]);
                        const rpcId = crypto.randomUUID();
                        const response = await fetch('/api/directoryPicker/list', {
                          method:'POST', headers:{'content-type':'application/json'}, signal,
                          body:JSON.stringify({type:'client-request',rpcId,method:'directoryPicker/list',payload:{args:{path:'/workspace'}}})
                        });
                        const envelope = await response.json();
                        window.__agentmDirectoryCheck = response.ok && envelope.rpcId===rpcId && envelope.result?.ok===true
                          ? 'passed' : 'failed:'+response.status+':'+String(envelope.result?.error?.code ?? 'invalid-envelope')+':'+String(envelope.result?.error?.message ?? '').slice(0,500);
                      } catch(error) { window.__agentmDirectoryCheck='failed:'+error.name+':'+error.message; }
                    })();
                """.trimIndent(), null) }
                state.set("")
                awaitMain {
                    activity.onActivity { screen -> webView(screen.window.decorView)!!.evaluateJavascript("window.__agentmDirectoryCheck") { state.set(it) } }
                    state.get().contains("passed") || state.get().contains("failed")
                }
                assertEquals(state.get(), "\"passed\"", state.get())
                println("DSH_WEBUI_DIRECTORY_OK: desktop/mobile/desktop switch, persisted desktop mode, same PID, AbortSignal.any, authenticated directory RPC, no management bridge")
            }
        } finally {
            preferences.edit().putBoolean("desktop-dsh", previousDesktop).commit()
            instrumentation.runOnMainSync { app.webAgents.stop("dsh") }; awaitMain { app.webAgents.current("dsh")?.active != true }
        }
    }
}
