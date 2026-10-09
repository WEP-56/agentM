package dev.agentm.app.packages

/** Recipes live in the APK. Registry data can select a patch, never scripts or dependency graphs. */
object UpdatePolicy {
    fun newer(candidate: String, installed: String): Boolean {
        fun parts(value: String) = value.takeIf { it.matches(Regex("[0-9]{1,8}\\.[0-9]{1,8}\\.[0-9]{1,8}(?:-[a-zA-Z0-9]+(?:[.-][a-zA-Z0-9]+)*)?")) }
            ?.substringBefore('-')?.split('.')?.map { it.toInt() }
        val a = parts(candidate) ?: return false
        val b = parts(installed) ?: return false
        for (i in 0..2) if (a[i] != b[i]) return a[i] > b[i]
        val preA = candidate.substringAfter('-', "")
        val preB = installed.substringAfter('-', "")
        if (preA.isEmpty() || preB.isEmpty()) return preA.isEmpty() && preB.isNotEmpty()
        val left = preA.split('.'); val right = preB.split('.')
        for (i in 0 until minOf(left.size, right.size)) {
            if (left[i] == right[i]) continue
            val numericA = left[i].all { it.isDigit() }; val numericB = right[i].all { it.isDigit() }
            if (numericA != numericB) return !numericA
            if (numericA && left[i].length != right[i].length) return left[i].length > right[i].length
            return left[i] > right[i]
        }
        if (left.size != right.size) return left.size > right.size
        return false
    }
    fun accepts(kind: String, pinned: String, candidate: String): Boolean =
        kind in setOf("claude", "codex", "opencode") &&
            candidate.matches(Regex("[0-9]{1,8}\\.[0-9]{1,8}\\.[0-9]{1,8}")) &&
            candidate.substringBeforeLast('.') == pinned.substringBeforeLast('.') &&
            (candidate == pinned || newer(candidate, pinned))
}
