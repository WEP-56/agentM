package dev.agentm.app.packages

/** Publication must finish before retiring the old slot; failed preparation only discards staging. */
internal class SlotTransaction(private val discard: () -> Unit) : AutoCloseable {
    private var committed = false
    fun publish(select: () -> Unit, retire: () -> Unit) {
        check(!committed)
        select()
        committed = true
        runCatching(retire)
    }
    override fun close() { if (!committed) discard() }
}
