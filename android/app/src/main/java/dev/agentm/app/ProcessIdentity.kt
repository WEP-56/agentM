package dev.agentm.app

data class ProcessIdentity(val pid: Int, val parent: Int, val group: Int, val session: Int, val startTicks: Long) {
    companion object {
        fun parse(stat: String): ProcessIdentity? = runCatching {
            val pid = stat.substringBefore('(').trim().toInt()
            val fields = stat.substringAfterLast(") ").trim().split(Regex("\\s+"))
            ProcessIdentity(pid, fields[1].toInt(), fields[2].toInt(), fields[3].toInt(), fields[19].toLong())
        }.getOrNull()
    }
}
