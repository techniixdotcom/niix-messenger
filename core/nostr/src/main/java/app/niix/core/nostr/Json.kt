package app.niix.core.nostr

/**
 * Just enough JSON for Nostr's wire format.
 *
 * org.json only exists on-device (unit tests get stubs), and relay messages are small arrays, so a
 * tiny parser with hard depth and size limits is simpler than another dependency.
 */
internal object Json {

    private const val MAX_DEPTH = 8

    class ParseException(message: String) : IllegalArgumentException(message)

    fun parse(text: String): Any? {
        val parser = Parser(text)
        val value = parser.readValue(0)
        parser.skipWhitespace()
        if (!parser.atEnd()) throw ParseException("Trailing data after JSON value")
        return value
    }

    fun parseOrNull(text: String): Any? = try {
        parse(text)
    } catch (_: ParseException) {
        null
    }

    /**
     * NIP-01 escaping: only `"`, `\` and \b \t \n \f \r get escapes. Any other escaping would hash
     * to a different event id than other clients compute. In the id serialisation ([forId]) the
     * remaining control characters are written raw, as NIP-01 says; on the wire they're \u-escaped
     * so the JSON stays valid. We never publish any.
     */
    fun quote(value: String, forId: Boolean = false): String {
        val sb = StringBuilder(value.length + 2).append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch < ' ' && !forId) sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        return sb.append('"').toString()
    }

    fun stringArray(items: List<String>, forId: Boolean = false): String =
        items.joinToString(",", "[", "]") { quote(it, forId) }

    private class Parser(private val s: String) {
        private var i = 0

        fun atEnd() = i >= s.length

        fun skipWhitespace() {
            while (i < s.length && s[i] in " \n\r\t") i++
        }

        fun readValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw ParseException("JSON nested too deeply")
            skipWhitespace()
            if (atEnd()) throw ParseException("Unexpected end of JSON")
            return when (val c = s[i]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                '-', in '0'..'9' -> readNumber()
                else -> throw ParseException("Unexpected character '$c'")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, i)) throw ParseException("Bad literal")
            i += word.length
            return value
        }

        private fun expect(c: Char, what: String) {
            skipWhitespace()
            if (atEnd() || s[i] != c) throw ParseException("Expected $what")
            i++
        }

        // Shared by objects and arrays: after an element, either ',' (more) or [close] (done).
        private fun more(close: Char): Boolean {
            skipWhitespace()
            if (atEnd()) throw ParseException("Unterminated JSON")
            return when (s[i++]) {
                ',' -> true
                close -> false
                else -> throw ParseException("Expected ',' or '$close'")
            }
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            i++
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (!atEnd() && s[i] == '}') { i++; return map }
            do {
                skipWhitespace()
                if (atEnd() || s[i] != '"') throw ParseException("Expected object key")
                val key = readString()
                expect(':', "':'")
                map[key] = readValue(depth + 1)
            } while (more('}'))
            return map
        }

        private fun readArray(depth: Int): List<Any?> {
            i++
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (!atEnd() && s[i] == ']') { i++; return list }
            do list.add(readValue(depth + 1)) while (more(']'))
            return list
        }

        private fun readString(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw ParseException("Unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c < ' ' -> throw ParseException("Control character in string")
                    c != '\\' -> sb.append(c)
                    atEnd() -> throw ParseException("Bad escape")
                    else -> when (val e = s[i++]) {
                        '"', '\\', '/' -> sb.append(e)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            val code = s.substring(i, minOf(i + 4, s.length)).takeIf { it.length == 4 }?.toIntOrNull(16)
                                ?: throw ParseException("Bad unicode escape")
                            sb.append(code.toChar())
                            i += 4
                        }
                        else -> throw ParseException("Bad escape '\\$e'")
                    }
                }
            }
        }

        private fun readNumber(): Any {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val text = s.substring(start, i)
            if (text.length > 32) throw ParseException("Number too long")
            return text.toLongOrNull() ?: text.toDoubleOrNull() ?: throw ParseException("Bad number")
        }
    }
}
