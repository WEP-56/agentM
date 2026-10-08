package dev.agentm.app

import android.app.Application

class AgentMApplication : Application() {
    val maintenance = Any()
    val logs = AppLogs()
    val terminals by lazy { TerminalManager(this) }
    val linux by lazy { dev.agentm.app.linux.LinuxManager(this) }
    val packages by lazy { dev.agentm.app.packages.PackageManager(this) }
    val configs by lazy { dev.agentm.app.config.ClaudeConfigManager(this) }
}
