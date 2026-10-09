package dev.agentm.app.config

import org.json.JSONArray
import org.json.JSONObject
import org.tomlj.Toml
import org.tomlj.TomlArray
import org.tomlj.TomlTable

/** Full TOML validation with a small source-span editor. Unselected statements retain their bytes. */
class TomlDocument(val source: String) {
    val root: JSONObject
    private data class Statement(val path: List<String>, val start: Int, val end: Int, val valueStart: Int?, val valueEnd: Int?, val array: Boolean = false)
    private val statements = mutableListOf<Statement>()
    init {
        require(source.toByteArray().size <= 1024 * 1024) { "TOML 配置超过 1 MiB" }
        val parsed = Toml.parse(source.removePrefix("\uFEFF"))
        // Parser diagnostics may contain source/keys; report only positions, never credentials.
        require(!parsed.hasErrors()) { "TOML 格式无效：${parsed.errors().firstOrNull()?.position()}" }
        root = json(parsed) as JSONObject
        scan()
    }
    fun at(path: List<String>): Any? {
        var value: Any? = root
        for (key in path) value = (value as? JSONObject)?.opt(key) ?: return null
        return value
    }
    private fun lineEnd(position: Int): Int = source.indexOf('\n', position).let { if (it < 0) source.length else it + 1 }
    private fun quotedEnd(start: Int): Int {
        val quote = source[start]
        val triple = source.startsWith("$quote$quote$quote", start)
        var p = start + if (triple) 3 else 1
        while (p < source.length) {
            if (quote == '"' && source[p] == '\\') { p += 2; continue }
            if (source[p] == quote) {
                if (!triple) return p + 1
                if (source.startsWith("$quote$quote$quote", p)) {
                    p += 3
                    repeat(2) { if (p < source.length && source[p] == quote) p++ }
                    return p
                }
            }
            p++
        }
        error("TOML 字符串未闭合")
    }
    private fun path(text: String): List<String> {
        val keys = mutableListOf<String>(); var p = 0
        while (p < text.length) {
            while (p < text.length && (text[p].isWhitespace() || text[p] == '.')) p++
            if (p == text.length) break
            val start = p
            if (text[p] == '"' || text[p] == '\'') {
                val q = text[p++]
                while (p < text.length) { if (q == '"' && text[p] == '\\') p += 2 else if (text[p++] == q) break }
                keys += Toml.parse("value = ${text.substring(start, p)}").getString("value")!!
            } else { while (p < text.length && text[p] != '.' && !text[p].isWhitespace()) p++; keys += text.substring(start, p) }
        }
        return keys
    }
    private fun scan() {
        var p = if (source.startsWith('\uFEFF')) 1 else 0
        var section = emptyList<String>(); var inArray = false
        while (p < source.length) {
            if (source[p].isWhitespace()) { p++; continue }
            if (source[p] == '#') { p = lineEnd(p); continue }
            val start = p
            if (source[p] == '[') {
                inArray = source.startsWith("[[", p); p += if (inArray) 2 else 1
                val keyStart = p
                while (source[p] != ']') { p = if (source[p] == '"' || source[p] == '\'') quotedEnd(p) else p + 1 }
                section = path(source.substring(keyStart, p)); p = lineEnd(p)
                statements += Statement(section, start, p, null, null, inArray)
            } else {
                while (source[p] != '=') { p = if (source[p] == '"' || source[p] == '\'') quotedEnd(p) else p + 1 }
                val key = section + path(source.substring(start, p)); p++
                while (p < source.length && source[p] in " \t") p++
                val valueStart = p; var depth = 0
                while (p < source.length) {
                    val c = source[p]
                    if (c == '"' || c == '\'') { p = quotedEnd(p); continue }
                    if ((c == '\n' || c == '\r' || c == '#') && depth == 0) break
                    if (c == '#') { p = lineEnd(p); continue }
                    if (c == '[' || c == '{') depth++
                    if (c == ']' || c == '}') depth--
                    p++
                }
                val valueEnd = p.let { end -> var e = end; while (e > valueStart && source[e - 1].isWhitespace()) e--; e }
                p = if (p < source.length) lineEnd(p) else p
                statements += Statement(key, start, p, valueStart, valueEnd, inArray)
            }
        }
    }
    fun set(path: List<String>, value: Any?): String {
        fun prefix(a: List<String>, b: List<String>) = a.size <= b.size && a == b.take(a.size)
        val exact = statements.firstOrNull { !it.array && it.valueStart != null && it.path == path }
        if (exact != null) return if (value == null) source.removeRange(exact.start, exact.end)
            else source.replaceRange(exact.valueStart!!, exact.valueEnd!!, literal(value))
        // Inline/dotted parent value owns its whole statement. Preserve its other members semantically.
        val parent = statements.filter { !it.array && it.valueStart != null && prefix(it.path, path) }.maxByOrNull { it.path.size }
        if (parent != null) {
            val replacement = JSONObject((at(parent.path) as? JSONObject ?: error("TOML 路径不是表")).toString())
            var table = replacement
            for (key in path.drop(parent.path.size).dropLast(1)) {
                if (!table.has(key)) { if (value == null) return source; table.put(key, JSONObject()) }
                table = table.optJSONObject(key) ?: error("TOML 路径不是表")
            }
            if (value == null) table.remove(path.last()) else table.put(path.last(), value)
            return source.replaceRange(parent.valueStart!!, parent.valueEnd!!, literal(replacement))
        }
        val owned = statements.filter { prefix(path, it.path) }
        if (owned.isNotEmpty()) {
            var cleaned = source
            for (s in owned.sortedByDescending { it.start }) cleaned = cleaned.removeRange(s.start, s.end)
            return if (value == null) cleaned else TomlDocument(cleaned).set(path, value)
        }
        if (value == null) return source
        val newline = if (source.contains("\r\n")) "\r\n" else "\n"
        val parentPath = path.dropLast(1)
        val section = statements.firstOrNull { !it.array && it.valueStart == null && it.path == parentPath }
        val insertion = "${key(path.last())} = ${literal(value)}$newline"
        if (parentPath.isEmpty()) {
            val position = statements.firstOrNull { it.valueStart == null }?.start ?: source.length
            return source.substring(0, position).let { it + if (it.isNotEmpty() && !it.endsWith('\n')) newline else "" } + insertion + source.substring(position)
        }
        if (section != null) return source.substring(0, section.end) + insertion + source.substring(section.end)
        return source + (if (source.isNotEmpty() && !source.endsWith('\n')) newline else "") + newline +
            "[${parentPath.joinToString(".", transform = ::key)}]$newline" + insertion
    }
    fun set(key: String, value: Any?) = set(listOf(key), value)
    companion object {
        private fun json(value: Any?): Any? = when (value) {
            is TomlTable -> JSONObject().also { result -> value.keySet().forEach { key -> result.put(key, json(value.get(listOf(key)))) } }
            is TomlArray -> JSONArray().also { result -> for (i in 0 until value.size()) result.put(json(value.get(i))) }
            else -> value
        }
        fun key(value: String): String = if (value.matches(Regex("[A-Za-z0-9_-]+"))) value else literal(value)
        fun literal(value: Any): String = when (value) {
            is String -> buildString {
                append('"')
                for (c in value) when (c) {
                    '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t"); '\b' -> append("\\b"); '\u000c' -> append("\\f")
                    else -> if (c.code < 32 || c.code == 127) append("\\u%04x".format(c.code)) else append(c)
                }
                append('"')
            }
            is JSONObject -> value.keys().asSequence().joinToString(", ", "{ ", " }") { "${key(it)} = ${literal(value.get(it))}" }
            is JSONArray -> (0 until value.length()).joinToString(", ", "[", "]") { literal(value.get(it)) }
            is Boolean, is Number, is java.time.temporal.TemporalAccessor -> value.toString()
            else -> error("不支持的 TOML 值类型")
        }
    }
}
