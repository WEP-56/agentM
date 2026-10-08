package dev.agentm.app

import androidx.annotation.Keep

/** DSHA's native fork/exec handshake calls back synchronously before guest code can execute. */
object PtyIdentity {
    private val receiver = ThreadLocal<((ProcessIdentity) -> Unit)?>()
    fun capture(accept: (ProcessIdentity) -> Unit, initialize: () -> Unit) {
        check(receiver.get() == null) { "PTY 身份捕获不能嵌套" }
        receiver.set(accept)
        try { initialize() } finally { receiver.remove() }
    }
    @Keep @JvmStatic fun recordPtyIdentity(pid: Int, stat: String): Boolean {
        val callback = receiver.get() ?: return false
        val identity = ProcessIdentity.parse(stat) ?: return false
        if (identity.pid != pid || identity.parent != android.os.Process.myPid() || identity.group != pid || identity.session != pid) return false
        callback(identity)
        return true
    }
}
