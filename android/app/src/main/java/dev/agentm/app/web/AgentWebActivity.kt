package dev.agentm.app.web

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.agentm.app.AgentMApplication
import dev.agentm.app.ui.SessionAppearance
import dev.agentm.app.ui.SessionChrome
import java.io.ByteArrayInputStream

/** Agent content gets its own WebView and no AgentMHost/JavaScript management interface. */
class AgentWebActivity : ComponentActivity() {
    private val manager get() = (application as AgentMApplication).webAgents
    private val kind get() = intent.getStringExtra("kind") ?: ""
    private val agentName get() = if (kind == "opencode") "OpenCode" else "DSH"
    private lateinit var web: WebView
    private lateinit var chrome: SessionChrome
    private lateinit var address: TextView
    private lateinit var back: ImageButton
    private lateinit var forward: ImageButton
    private lateinit var refresh: ImageButton
    private lateinit var progress: ProgressBar
    private lateinit var statusPanel: LinearLayout
    private lateinit var status: TextView
    private var loadedId: String? = null
    @Volatile private var expectedId: String? = null
    private var compatibilityScript: ScriptHandler? = null
    private var chooser: ValueCallback<Array<Uri>>? = null
    private var savedWebState: Bundle? = null
    private var savedSessionId: String? = null
    private var clearInitialHistory = false
    private var desktop = false
    private lateinit var mobileUserAgent: String
    private val preferences by lazy { getSharedPreferences("agent-browser", MODE_PRIVATE) }
    private val chooseFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        chooser?.onReceiveValue(uris.toTypedArray()); chooser = null
    }
    private val observer: () -> Unit = { render() }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (kind !in WebPolicy.kinds) { finish(); return }
        chrome = SessionChrome(this, SessionAppearance(this))
        desktop = preferences.getBoolean("desktop-$kind", false)
        savedWebState = savedInstanceState?.getBundle("web")
        savedSessionId = savedInstanceState?.getString("sessionId")
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(chrome.colors.bar) }
        val bar = chrome.bar()
        back = chrome.button("back", "后退或返回工作台") { goBack() }
        forward = chrome.button("forward", "前进") { if (isCurrent() && web.canGoForward()) web.goForward() }
        bar.addView(back); bar.addView(forward)
        address = chrome.label(agentName, 12f).apply {
            setSingleLine(); ellipsize = TextUtils.TruncateAt.MIDDLE; setPadding(chrome.dp(14), 0, chrome.dp(14), 0)
            background = chrome.ripple(chrome.colors.container, 24); contentDescription = "地址栏"; setOnClickListener { editAddress() }
        }
        bar.addView(address, LinearLayout.LayoutParams(0, chrome.dp(48), 1f))
        refresh = chrome.button("refresh", "刷新页面") { if (isCurrent()) { hideStatus(); web.reload() } }
        bar.addView(refresh)
        val more = chrome.button("more", "浏览器菜单") {}
        more.setOnClickListener { showMenu(more) }; bar.addView(more); root.addView(bar)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progressTintList = ColorStateList.valueOf(chrome.colors.accent); progressBackgroundTintList = ColorStateList.valueOf(chrome.colors.bar)
        }
        root.addView(progress, LinearLayout.LayoutParams(-1, chrome.dp(2)))
        web = WebView(this).apply { setBackgroundColor(chrome.colors.background) }
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; allowFileAccess = false; allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW; setSupportMultipleWindows(false)
            setSupportZoom(true); builtInZoomControls = true; displayZoomControls = false
        }
        mobileUserAgent = web.settings.userAgentString; applyDesktopSettings()
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, value: Int) {
                progress.progress = value; progress.visibility = if (isCurrent() && value < 100) View.VISIBLE else View.INVISIBLE
            }
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                if (!isCurrent()) return false
                chooser?.onReceiveValue(null); chooser = callback
                val types = params.acceptTypes.filter { it.contains('/') }.toTypedArray()
                chooseFiles.launch(if (types.isEmpty()) arrayOf("*/*") else types); return true
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val session = manager.current(kind)
                if (isCurrent() && WebPolicy.navigationUrl(request.url.toString(), session!!.port) != null) return false
                if (request.isForMainFrame && request.url.scheme == "https") openExternal(request.url)
                return true
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val session = manager.current(kind)
                if (session != null && session.id == expectedId && session.state == "ready" && WebPolicy.sameOrigin(request.url.toString(), session.port)) return null
                return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            }
            override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {
                val session = manager.current(kind)
                if (kind == "opencode" && host == "127.0.0.1" && isCurrent() && WebPolicy.sameOrigin(view.url ?: "", session!!.port)) handler.proceed("opencode", session.password)
                else handler.cancel()
            }
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { if (isCurrent()) hideStatus(); updateNavigation() }
            override fun onPageFinished(view: WebView, url: String) {
                if (clearInitialHistory && isCurrent() && WebPolicy.navigationUrl(url, manager.current(kind)!!.port) != null) {
                    clearInitialHistory = false; view.clearHistory()
                }
                updateNavigation()
            }
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) { updateNavigation() }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && isCurrent()) showStatus("页面暂时无法加载\n点击刷新重试，或在菜单中重启 $agentName。")
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame && isCurrent()) showStatus("页面返回 ${response.statusCode}\n点击刷新重试，或在菜单中重启 $agentName。")
            }
        }
        val content = FrameLayout(this)
        content.addView(web, FrameLayout.LayoutParams(-1, -1))
        status = chrome.label("正在启动 $agentName…", 14f, chrome.colors.muted).apply { gravity = Gravity.CENTER; setPadding(chrome.dp(24), chrome.dp(16), chrome.dp(24), chrome.dp(16)) }
        statusPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(chrome.colors.background)
            addView(chrome.label(agentName, 24f, chrome.colors.accent).apply { gravity = Gravity.CENTER }); addView(status)
        }
        content.addView(statusPanel, FrameLayout.LayoutParams(-1, -1)); root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root); chrome.insets(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = goBack() })
        try {
            val existing = manager.current(kind)
            val session = if (savedInstanceState != null && existing != null) existing else manager.request(kind)
            expectedId = session.id
            if (session.state == "starting") WebAgentService.start(this, session)
            render()
        } catch (error: Exception) { showStatus(error.message ?: "无法打开 WebUI") }
    }
    private fun isCurrent(): Boolean = manager.current(kind)?.let { it.id == expectedId && it.state == "ready" } == true
    private fun goBack() { if (isCurrent() && web.canGoBack()) web.goBack() else finish() }
    private fun updateNavigation() {
        if (!::web.isInitialized) return
        val session = manager.current(kind)
        chrome.enabled(forward, isCurrent() && web.canGoForward()); chrome.enabled(refresh, isCurrent())
        address.text = if (isCurrent()) WebPolicy.displayUrl(web.url ?: "", session!!.port).removePrefix("http://").removeSuffix("/") else agentName
        address.contentDescription = "地址栏 · ${address.text}${if (desktop) " · 桌面版网站" else ""}"
        back.contentDescription = if (isCurrent() && web.canGoBack()) "后退" else "返回工作台"
    }
    private fun showStatus(message: String) { status.text = message; statusPanel.visibility = View.VISIBLE }
    private fun hideStatus() { statusPanel.visibility = View.GONE }
    private fun render() {
        if (!::web.isInitialized) return
        val session = manager.current(kind)
        if (session?.id != expectedId) {
            chooser?.onReceiveValue(null); chooser = null
            web.stopLoading(); web.loadUrl("about:blank"); web.clearHistory(); loadedId = null; expectedId = session?.id
        }
        updateNavigation()
        if (!isCurrent()) {
            if (loadedId != null) { web.stopLoading(); web.loadUrl("about:blank"); loadedId = null }
            progress.visibility = if (session?.state == "starting") View.VISIBLE else View.INVISIBLE
            progress.isIndeterminate = session?.state == "starting"
            showStatus(session?.message ?: "会话不可用，可在菜单中重新启动"); return
        }
        session ?: return
        if (loadedId == session.id) return
        val base = "http://127.0.0.1:${session.port}/"
        if (!installPageScripts(base)) return
        loadedId = session.id; progress.isIndeterminate = false
        val restored = savedWebState?.takeIf { savedSessionId == session.id }
        savedWebState = null
        fun load() {
            if (!isCurrent() || loadedId != session.id) return
            hideStatus()
            if (restored != null && web.restoreState(restored) != null) return
            clearInitialHistory = true
            web.clearHistory()
            if (kind == "opencode") web.loadUrl(base, mapOf("Authorization" to WebHealth.basic(session.password))) else web.loadUrl(base)
        }
        if (kind == "dsh") {
            val cookie = session.cookie ?: return
            CookieManager.getInstance().setCookie(base, "$cookie; Path=/; HttpOnly; SameSite=Strict") { load() }
        } else load()
    }
    private fun installPageScripts(base: String): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            showStatus("请更新 Android System WebView 后重试"); return false
        }
        compatibilityScript?.remove()
        var source = assets.open("opencode-web-compat.js").bufferedReader().use { it.readText() }
        if (desktop) source += "\n" + assets.open("desktop-viewport.js").bufferedReader().use { it.readText() }
        compatibilityScript = WebViewCompat.addDocumentStartJavaScript(web, source, setOf(base.removeSuffix("/")))
        return true
    }
    private fun applyDesktopSettings() {
        web.settings.userAgentString = if (desktop) WebPolicy.desktopUserAgent(mobileUserAgent) else mobileUserAgent
        web.settings.useWideViewPort = true; web.settings.loadWithOverviewMode = desktop
    }
    internal fun setDesktopMode(enabled: Boolean) {
        desktop = enabled; preferences.edit().putBoolean("desktop-$kind", desktop).apply()
        if (isCurrent()) installPageScripts("http://127.0.0.1:${manager.current(kind)!!.port}/")
        applyDesktopSettings(); if (isCurrent()) { hideStatus(); web.reload() }; updateNavigation()
    }
    private fun showMenu(anchor: View) {
        val session = manager.current(kind)
        val canRestart = session?.state !in setOf("stopping", "stop-unconfirmed")
        chrome.menu(anchor, agentName, session?.message ?: "会话不可用", listOf(
            SessionChrome.Item("back", "返回工作台") { finish() },
            SessionChrome.Item("desktop", "桌面版网站", checked = desktop) {
                setDesktopMode(!desktop)
            },
            SessionChrome.Item("external", "在浏览器中打开", isCurrent()) { openInBrowser() },
            SessionChrome.Item("refresh", "重启 $agentName", canRestart) {
                chrome.confirm("重启 $agentName？", "当前 Web 服务会停止，页面将重新连接。", "重启") {
                    runCatching { manager.restart(kind) }.onFailure { showStatus(it.message ?: "重启失败") }
                }
            },
            SessionChrome.Item("stop", "停止 Web 服务", session?.active == true && session.state != "stopping", danger = true) {
                chrome.confirm("停止 $agentName？", "当前 Web 服务将结束。", "停止") { manager.stop(kind) }
            }
        ))
    }
    private fun editAddress() {
        val session = manager.current(kind) ?: return
        if (!isCurrent()) return
        val input = EditText(this).apply {
            setText(WebPolicy.displayUrl(web.url ?: "", session.port)); setSingleLine(); selectAll(); textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI; imeOptions = EditorInfo.IME_ACTION_GO
            backgroundTintList = ColorStateList.valueOf(chrome.colors.accent)
        }
        val container = LinearLayout(this).apply { setPadding(chrome.dp(24), chrome.dp(8), chrome.dp(24), 0); addView(input, LinearLayout.LayoutParams(-1, -2)) }
        val dialog = chrome.show(AlertDialog.Builder(this).setTitle("访问地址").setView(container).setNegativeButton("取消", null).setPositiveButton("前往", null).create())
        fun navigate() {
            if (!isCurrent() || manager.current(kind)?.id != session.id) { dialog.dismiss(); return }
            val entered = input.text.toString().trim()
            val url = WebPolicy.navigationUrl(entered, session.port)
            if (url != null) { hideStatus(); web.loadUrl(url); dialog.dismiss() }
            else if (runCatching { java.net.URI(entered).let { it.scheme == "https" && it.host != null && it.rawUserInfo == null } }.getOrDefault(false)) { openExternal(Uri.parse(entered)); dialog.dismiss() }
            else input.error = "请输入当前服务地址；外部 HTTPS 网站会在浏览器打开"
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { navigate() }
        input.setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_GO) { navigate(); true } else false }
        input.requestFocus(); dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }
    private fun openInBrowser() {
        val session = manager.current(kind)?.takeIf { isCurrent() } ?: return
        val base = "http://127.0.0.1:${session.port}/"
        if (kind == "dsh") {
            val token = session.token ?: return
            openExternal(Uri.parse("${base}?token=$token"))
        } else {
            // Browser authentication is separate from WebView. Explicitly copy only on user action.
            chrome.confirm("在浏览器打开 OpenCode", "浏览器首次访问需要登录。用户名：opencode\n\n点击下方按钮复制本次服务密码，再在浏览器登录框中粘贴。", "复制密码并打开") {
                if (isCurrent() && manager.current(kind)?.id == session.id) {
                    val clip = ClipData.newPlainText("OpenCode 临时登录密码", session.password)
                    clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                    openExternal(Uri.parse(WebPolicy.displayUrl(web.url ?: base, session.port)))
                }
            }
        }
    }
    private fun openExternal(uri: Uri) { runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)) }.onFailure { Toast.makeText(this, "未找到可打开此链接的浏览器", Toast.LENGTH_SHORT).show() } }
    override fun onSaveInstanceState(outState: Bundle) {
        if (::web.isInitialized && isCurrent()) { val state = Bundle(); web.saveState(state); outState.putBundle("web", state); outState.putString("sessionId", expectedId) }
        super.onSaveInstanceState(outState)
    }
    override fun onStart() { super.onStart(); manager.observe(observer); if (::web.isInitialized) render() }
    override fun onStop() { manager.unobserve(observer); super.onStop() }
    override fun onDestroy() { chooser?.onReceiveValue(null); chooser = null; compatibilityScript?.remove(); compatibilityScript = null; if (::web.isInitialized) { web.stopLoading(); web.destroy() }; super.onDestroy() }
}
