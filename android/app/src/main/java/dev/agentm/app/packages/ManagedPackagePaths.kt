package dev.agentm.app.packages

/** Closed path grammar for executable slots. Frontend input cannot select arbitrary files. */
object ManagedPackagePaths {
    val kinds = setOf("node", "claude", "codex", "pi", "opencode", "dsh")
    val agents = kinds - "node"
    val terminals = agents - "dsh"
    val installActions = mapOf("installClaude" to "claude", "installCodex" to "codex", "installPi" to "pi", "installOpenCode" to "opencode", "installDsh" to "dsh")
    val removeActions = mapOf("removeClaude" to "claude", "removeCodex" to "codex", "removePi" to "pi", "removeOpenCode" to "opencode", "removeDsh" to "dsh")
    fun title(kind: String) = when (kind) { "node" -> "Node.js"; "claude" -> "Claude Code"; "codex" -> "Codex"; "pi" -> "Pi"; "opencode" -> "OpenCode"; "dsh" -> "DSH"; else -> error("未知软件") }
    fun validSlot(slot: String) = slot.matches(Regex("(node|claude|codex|pi|opencode|dsh)-[a-f0-9-]{36}"))
    fun validVersion(version: String) = version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[a-zA-Z0-9.-]+)?"))
    fun validEntry(kind: String, entry: String): Boolean = when (kind) {
        "node" -> entry.matches(Regex("node-v[0-9.]+-linux-(x64|arm64)/bin/node"))
        "claude" -> entry == "package/claude"
        "codex" -> entry.matches(Regex("package/vendor/(x86_64|aarch64)-unknown-linux-musl/bin/codex"))
        "pi" -> entry == "bin/pi"
        "opencode" -> entry == "package/bin/opencode"
        "dsh" -> entry == "bin/dsh"
        else -> false
    }
    fun validGuest(executable: String): Boolean = executable.matches(
        Regex("/opt/agentm/slots/(node|claude|codex|pi|opencode|dsh)-[a-f0-9-]{36}/[A-Za-z0-9./_-]+")) &&
        executable.split('/').none { it == "." || it == ".." }
}
