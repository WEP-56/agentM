package dev.agentm.app

/** Pure policy shared by the WebView adapter and unit tests. */
object BridgePolicy {
    const val ORIGIN = "https://appassets.androidplatform.net"
    const val MAX_BYTES = 65536
    val methods = setOf("inspect", "openTerminal", "stopTerminal", "openPermission", "clearLogs", "setAppearance", "installLinux", "cancelLinuxInstall", "checkLinux", "managePackages",
        "readClaudeConfig", "previewClaudeConfig", "previewClaudeRestore", "applyClaudeConfig",
        "listClaudeProfiles", "saveClaudeProfile", "deleteClaudeProfile", "previewClaudeProfile", "openWeb", "stopWeb", "storageUsage", "listStorage",
        "listProviders", "readProvider", "saveProvider", "copyProvider", "deleteProvider", "switchProvider", "fetchProviderModels")
    fun accepts(origin: String, mainFrame: Boolean, method: String, requestId: String, bytes: Int): Boolean =
        origin == ORIGIN && mainFrame && method in methods &&
            requestId.matches(Regex("[A-Za-z0-9_-]{1,96}")) && bytes in 1..MAX_BYTES
}
