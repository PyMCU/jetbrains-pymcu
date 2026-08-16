package dev.begeistert.pymcu.cli

/**
 * A minimal JSON reader for the machine-readable CLI output
 * (`pymcu boards --json`, `pymcu lint --json`).
 *
 * WHY not Gson/Jackson: both ship inside the IntelliJ Platform but neither is a
 * documented, version-stable part of the plugin SDK — a plugin that binds to
 * them compiles fine and then fails at runtime on an IDE build that shades or
 * relocates them. The payloads here are small and flat, so a self-contained
 * reader removes that whole class of deployment risk.
 *
 * Values map to `String`, `Double`, `Boolean`, `null`, `List<Any?>` and
 * `Map<String, Any?>`.
 */
object JsonLite {

    /** Parses [text]; returns null when it is not valid JSON. */
    fun parse(text: String): Any? = try {
        val cursor = Cursor(text)
        cursor.skipWhitespace()
        val value = cursor.readValue()
        value
    } catch (_: Exception) {
        null
    }

    /** Parses [text] and returns it as an object, or null. */
    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?>? = parse(text) as? Map<String, Any?>

    // ── accessors: JSON numbers are Doubles, callers usually want Int/String ──

    fun Any?.str(): String? = this as? String
    fun Any?.int(): Int? = (this as? Double)?.toInt()
    fun Map<String, Any?>.obj(key: String): Map<String, Any?>? {
        @Suppress("UNCHECKED_CAST")
        return this[key] as? Map<String, Any?>
    }
    fun Map<String, Any?>.arr(key: String): List<Any?> = this[key] as? List<Any?> ?: emptyList()

    private class Cursor(private val text: String) {
        private var i = 0

        fun skipWhitespace() {
            while (i < text.length && text[i].isWhitespace()) i++
        }

        fun readValue(): Any? {
            skipWhitespace()
            if (i >= text.length) return null
            return when (text[i]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> readNumber()
            }
        }

        private fun expect(literal: String) {
            require(text.startsWith(literal, i)) { "expected $literal at $i" }
            i += literal.length
        }

        private fun readObject(): Map<String, Any?> {
            i++ // '{'
            val out = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (i < text.length && text[i] == '}') { i++; return out }
            while (i < text.length) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                require(text[i] == ':') { "expected ':' at $i" }
                i++
                out[key] = readValue()
                skipWhitespace()
                when (text[i]) {
                    ',' -> i++
                    '}' -> { i++; return out }
                    else -> throw IllegalStateException("expected ',' or '}' at $i")
                }
            }
            return out
        }

        private fun readArray(): List<Any?> {
            i++ // '['
            val out = mutableListOf<Any?>()
            skipWhitespace()
            if (i < text.length && text[i] == ']') { i++; return out }
            while (i < text.length) {
                out.add(readValue())
                skipWhitespace()
                when (text[i]) {
                    ',' -> i++
                    ']' -> { i++; return out }
                    else -> throw IllegalStateException("expected ',' or ']' at $i")
                }
            }
            return out
        }

        private fun readString(): String {
            require(text[i] == '"') { "expected string at $i" }
            i++
            val sb = StringBuilder()
            while (i < text.length) {
                when (val c = text[i++]) {
                    '"' -> return sb.toString()
                    '\\' -> when (val esc = text[i++]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> {
                            sb.append(text.substring(i, i + 4).toInt(16).toChar())
                            i += 4
                        }
                        else -> sb.append(esc)   // covers \" \\ \/
                    }
                    else -> sb.append(c)
                }
            }
            throw IllegalStateException("unterminated string")
        }

        private fun readNumber(): Double {
            val start = i
            while (i < text.length && (text[i].isDigit() || text[i] in "+-.eE")) i++
            return text.substring(start, i).toDouble()
        }
    }
}
