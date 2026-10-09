package dev.agentm.app

import android.app.Application

class AgentMApplication : Application() {
    val maintenance = Any()
    val logs = AppLogs()
    val terminals by lazy { TerminalManager(this) }
    val linux by lazy { dev.agentm.app.linux.LinuxManager(this) }
    val packages by lazy { dev.agentm.app.packages.PackageManager(this) }
    val configs by lazy { dev.agentm.app.config.ClaudeConfigManager(this) }
    val providers by lazy { dev.agentm.app.config.ProviderManager(this) }
    val webAgents by lazy { dev.agentm.app.web.WebAgentManager(this) }
    val workingDirectories by lazy {
        val preferences = getSharedPreferences("working-directory", MODE_PRIVATE)
        WorkingDirectories(java.io.File(filesDir.canonicalFile, "workspaces"), java.io.File(filesDir.canonicalFile, "linux-home"),
            { preferences.getString("path", null) },
            { path -> check(preferences.edit().putString("path", path).commit()) { "工作目录保存失败，请重试" } })
    }
    override fun onCreate() { super.onCreate(); providers.recover() }
}
