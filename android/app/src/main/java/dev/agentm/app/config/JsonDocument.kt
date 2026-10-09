package dev.agentm.app.config

/** Strict JSON with source spans. Edits replace only selected values/members, never serialize the document. */
class JsonDocument(val source: String, private val input: String = source) {
    data class Member(val name: String, val start: Int, val value: Value)
    data class Value(val start: Int, val end: Int, val kind: Char, val text: String? = null, val members: List<Member> = emptyList()) {
        fun member(name: String) = members.firstOrNull { it.name == name }
    }
    private var offset = if (input.startsWith('\uFEFF')) 1 else 0
    val root: Value = parse(0).also { whitespace(); requireJson(offset == input.length) }
    private fun requireJson(condition: Boolean) { if (!condition) throw IllegalArgumentException("JSON 格式无效（位置 $offset）") }
    private fun whitespace() { while (offset < input.length && input[offset] in " \t\r\n") offset++ }
    private fun take(character: Char): Boolean { whitespace(); return if (offset < input.length && input[offset] == character) { offset++; true } else false }
    private fun parse(depth: Int): Value {
        requireJson(depth <= 64); whitespace(); requireJson(offset < input.length)
        val start = offset
        return when (val character = input[offset]) {
            '{' -> {
                offset++; val members = mutableListOf<Member>(); val names = mutableSetOf<String>()
                if (!take('}')) while (true) {
                    whitespace(); val keyStart = offset; val key = string()
                    requireJson(names.add(key)); requireJson(take(':'))
                    members += Member(key, keyStart, parse(depth + 1))
                    if (take('}')) break
                    requireJson(take(','))
                }
                Value(start, offset, '{', members = members)
            }
            '[' -> {
                offset++
                if (!take(']')) while (true) { parse(depth + 1); if (take(']')) break; requireJson(take(',')) }
                Value(start, offset, '[')
            }
            '"' -> Value(start, 0, '"', string()).let { it.copy(end = offset) }
            't', 'f', 'n' -> {
                val literal = when (character) { 't' -> "true"; 'f' -> "false"; else -> "null" }
                requireJson(input.startsWith(literal, offset)); offset += literal.length
                Value(start, offset, character)
            }
            else -> {
                val match = NUMBER.find(input, offset)
                requireJson(match != null && match.range.first == offset)
                offset = match!!.range.last + 1
                Value(start, offset, '0')
            }
        }
    }
    private fun string(): String {
        requireJson(offset < input.length && input[offset++] == '"')
        val result = StringBuilder()
        while (offset < input.length) {
            val character = input[offset++]
            if (character == '"') return result.toString()
            requireJson(character.code >= 32)
            if (character != '\\') { result.append(character); continue }
            requireJson(offset < input.length)
            when (val escape = input[offset++]) {
                '"', '\\', '/' -> result.append(escape)
                'b' -> result.append('\b')
                'f' -> result.append('\u000c')
                'n' -> result.append('\n')
                'r' -> result.append('\r')
                't' -> result.append('\t')
                'u' -> {
                    requireJson(offset + 4 <= input.length)
                    val digits = input.substring(offset, offset + 4)
                    requireJson(digits.all { it in "0123456789abcdefABCDEF" })
                    val code = digits.toIntOrNull(16)
                    requireJson(code != null); result.append(code!!.toChar()); offset += 4
                }
                else -> requireJson(false)
            }
        }
        requireJson(false); return ""
    }
    fun editObject(obj: Value, key: String, rawValue: String?): String {
        require(obj.kind == '{')
        val index = obj.members.indexOfFirst { it.name == key }
        if (index >= 0) {
            val member = obj.members[index]
            if (rawValue != null) return source.replaceRange(member.value.start, member.value.end, rawValue)
            return when {
                index < obj.members.lastIndex -> source.removeRange(member.start, obj.members[index + 1].start)
                index > 0 -> source.removeRange(obj.members[index - 1].value.end, member.value.end)
                else -> source.removeRange(member.start, if (input !== source) obj.end - 1 else member.value.end)
            }
        }
        if (rawValue == null) return source
        val last = obj.members.lastOrNull()
        val multiline = source.substring(obj.start, obj.end).contains('\n')
        val first = obj.members.firstOrNull()?.start
        val indent = if (first != null) source.substring(source.lastIndexOf('\n', first).let { it + 1 }, first).takeWhile { it == ' ' || it == '\t' } else "  "
        val separator = if (multiline) (if (source.contains("\r\n")) "\r\n" else "\n") + indent else if (last != null) " " else ""
        val insertion = (if (last != null) "," else "") + separator + quote(key) + ": " + rawValue
        val position = last?.value?.end ?: (obj.start + 1)
        return source.substring(0, position) + insertion + source.substring(position)
    }
    companion object {
        private val NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
        fun quote(value: String): String = buildString {
            append('"')
            for (character in value) when (character) {
                '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
                else -> if (character.code < 32 || character.isSurrogate()) append("\\u%04x".format(character.code)) else append(character)
            }
            append('"')
        }
    }
}
