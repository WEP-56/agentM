package dev.agentm.app.packages

/** Closed path grammar for executable slots. Frontend input cannot select arbitrary files. */
object ManagedPackagePaths {
    val kinds = setOf("node", "claude", "codex")
    val agents = setOf("claude", "codex")
    fun title(kind: String) = when (kind) { "node" -> "Node.js"; "claude" -> "Claude Code"; "codex" -> "Codex"; else -> error("未知软件") }
    fun validSlot(slot: String) = slot.matches(Regex("(node|claude|codex)-[a-f0-9-]{36}"))
    fun validEntry(kind: String, entry: String): Boolean = when (kind) {
        "node" -> entry.matches(Regex("node-v[0-9.]+-linux-(x64|arm64)/bin/node"))
        "claude" -> entry == "package/claude"
        "codex" -> entry.matches(Regex("package/vendor/(x86_64|aarch64)-unknown-linux-musl/bin/codex"))
        else -> false
    }
    fun validGuest(executable: String): Boolean = executable.matches(
        Regex("/opt/agentm/slots/(node|claude|codex)-[a-f0-9-]{36}/[A-Za-z0-9./_-]+")) &&
        executable.split('/').none { it == "." || it == ".." }
}
