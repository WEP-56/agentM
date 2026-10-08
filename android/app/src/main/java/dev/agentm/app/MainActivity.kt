package dev.agentm.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private val app get() = application as AgentMApplication
    private val inspector by lazy { EnvironmentInspector(app) }
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(32))
    private val replies = object : LinkedHashMap<String, Pair<String, String>>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, String>>) = size > 128
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            setContentView(TextView(this).apply { text = "请先更新 Android System WebView 后使用 agentM。" })
            return
        }
        WindowCompat.setDecorFitsSystemWindows(window, true)
        web = WebView(this)
        val host = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.rgb(254, 247, 255))
            addView(web, FrameLayout.LayoutParams(-1, -1))
        }
        web.setBackgroundColor(android.graphics.Color.rgb(254, 247, 255))
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        val assets = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assets.shouldInterceptRequest(request.url)
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.toString().startsWith("${BridgePolicy.ORIGIN}/assets/workbench/")) return false
                if (request.isForMainFrame && uri.scheme == "https") runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                return true
            }
        }
        WebViewCompat.addWebMessageListener(web, "AgentMHost", setOf(BridgePolicy.ORIGIN)) { _, message, origin, mainFrame, reply ->
            val raw = message.data ?: return@addWebMessageListener
            if (raw.toByteArray().size > BridgePolicy.MAX_BYTES) return@addWebMessageListener
            // Origin is supplied by WebView, never by the caller's JSON.
            val request = runCatching { JSONObject(raw) }.getOrNull() ?: return@addWebMessageListener
            val id = request.optString("id")
            val method = request.optString("method")
            if (!BridgePolicy.accepts(origin.toString().removeSuffix("/"), mainFrame, method, id, raw.toByteArray().size)) {
                reply.postMessage(error(id, "INVALID_REQUEST", "原生接口请求无效"))
                return@addWebMessageListener
            }
            val body = request.optJSONObject("params") ?: JSONObject()
            val fingerprint = method + ":" + body.toString()
            replies[id]?.let { cached ->
                reply.postMessage(if (cached.first == fingerprint) cached.second else error(id, "REQUEST_ID_REUSED", "请求标识已被使用"))
                return@addWebMessageListener
            }
            // A queued duplicate can only repeat read-only inspection. UI mutations run atomically below.
            fun respond(result: JSONObject) {
                val encoded = JSONObject().put("id", id).put("ok", true).put("value", result).toString()
                replies[id] = fingerprint to encoded
                reply.postMessage(encoded)
            }
            try {
                when (method) {
                    "managePackages" -> {
                        val operationId = app.packages.enqueue(body.optString("action"))
                        try { dev.agentm.app.packages.PackageService.start(this) }
                        catch (error: Exception) { app.packages.failure(error); throw error }
                        respond(JSONObject().put("operationId", operationId))
                    }
                    "installLinux" -> {
                        val operationId = app.linux.enqueue()
                        try { dev.agentm.app.linux.LinuxInstallService.start(this) }
                        catch (error: Exception) { app.linux.failStart(); throw error }
                        respond(JSONObject().put("operationId", operationId))
                    }
                    "cancelLinuxInstall" -> { app.linux.cancel(); respond(JSONObject().put("requested", true)) }
                    "checkLinux" -> worker.execute {
                        try { app.linux.check(); val snapshot = inspector.inspect(); runOnUiThread { if (!isDestroyed) respond(snapshot) } }
                        catch (error: Exception) { runOnUiThread { if (!isDestroyed) reply.postMessage(error(id, "LINUX_CHECK_FAILED", error.message ?: "Linux 检查失败")) } }
                    }
                    "setAppearance" -> {
                        val background = body.optString("background")
                        require(background.matches(Regex("#[0-9a-fA-F]{6}")))
                        host.setBackgroundColor(android.graphics.Color.parseColor(background))
                        WindowCompat.getInsetsController(window, host).apply {
                            isAppearanceLightStatusBars = !body.optBoolean("dark")
                            isAppearanceLightNavigationBars = !body.optBoolean("dark")
                        }
                        respond(JSONObject().put("applied", true))
                    }
                    "inspect" -> worker.execute {
                        try {
                            val snapshot = inspector.inspect()
                            runOnUiThread { if (!isDestroyed) respond(snapshot) }
                        } catch (e: Exception) {
                            runOnUiThread { if (!isDestroyed) reply.postMessage(error(id, "INSPECTION_FAILED", "设备检查失败")) }
                        }
                    }
                    "openTerminal" -> {
                        val kind = body.optString("kind", "deviceShell")
                        app.terminals.requireOpenable(kind)
                        TerminalService.start(this, kind)
                        startActivity(Intent(this, TerminalActivity::class.java).putExtra("kind", kind))
                        respond(JSONObject().put("opened", true))
                    }
                    "stopTerminal" -> { app.terminals.stop(); respond(JSONObject().put("requested", true)) }
                    "clearLogs" -> { app.logs.clear(); respond(JSONObject().put("cleared", true)) }
                    "openPermission" -> {
                        when (body.optString("permission")) {
                            "notifications" -> if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                            } else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                            "battery" -> startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            "storage" -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                            else -> { reply.postMessage(error(id, "INVALID_PERMISSION", "未知权限")); return@addWebMessageListener }
                        }
                        respond(JSONObject().put("opened", true))
                    }
                }
            } catch (e: Exception) {
                app.logs.add("bridge", "$method 未完成：${e.javaClass.simpleName}", "E")
                reply.postMessage(error(id, "NATIVE_ERROR", e.message?.take(200) ?: "操作未完成，请查看运行日志"))
            }
        }
        setContentView(host)
        // WebView padding does not inset its HTML viewport. Inset the containing layout instead.
        ViewCompat.setOnApplyWindowInsetsListener(host) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("Boolean(window.agentMBack && window.agentMBack())") { handled ->
                    if (handled != "true") moveTaskToBack(true)
                }
            }
        })
        web.loadUrl("${BridgePolicy.ORIGIN}/assets/workbench/index.html")
        app.logs.add("app", "agentM ${BuildConfig.VERSION_NAME} 已启动")
    }
    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) web.evaluateJavascript("window.dispatchEvent(new Event('agentm:resume'))", null)
    }
    override fun onDestroy() {
        worker.shutdownNow()
        if (::web.isInitialized) { WebViewCompat.removeWebMessageListener(web, "AgentMHost"); web.destroy() }
        super.onDestroy()
    }
    private fun error(id: String, code: String, message: String): String = JSONObject().put("id", id).put("ok", false)
        .put("error", JSONObject().put("code", code).put("message", message)).toString()
}
