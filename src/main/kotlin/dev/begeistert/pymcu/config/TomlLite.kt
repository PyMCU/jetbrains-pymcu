package dev.begeistert.pymcu.config

/**
 * A small, dependency-free TOML reader covering the subset that appears in
 * `pyproject.toml`.
 *
 * WHY not a library: the IntelliJ Platform classpath is unforgiving about
 * transitive dependencies, and the TOML PSI from `org.toml.lang` is only
 * available inside a read action on an open file — the config is also read from
 * background threads and from tests. A ~200-line reader for the subset we
 * actually consume is cheaper than either.
 *
 * WHY not regex (the previous approach): `board = "arduino_uno"  # comment` and
 * `frequency = 16_000_000` both failed to parse, silently producing a project
 * with no target.
 *
 * Supported: comments, `[table]` and `[a.b.c]` headers, `[[array of tables]]`,
 * bare/quoted/dotted keys, basic and literal strings (single and multi-line),
 * integers (decimal with `_` separators, `0x`/`0o`/`0b`), floats, booleans,
 * arrays (including multi-line and nested) and inline tables.
 *
 * Not supported (absent from pyproject.toml): dates and times, which are
 * returned as their raw string.
 */
object TomlLite {

    /** Parses [text] into a nested map. Never throws — malformed input yields a partial map. */
    fun parse(text: String): Map<String, Any?> {
        val root = LinkedHashMap<String, Any?>()
        var current: MutableMap<String, Any?> = root
        val p = Cursor(text)

        while (true) {
            p.skipTrivia()
            if (p.eof) break

            if (p.peek() == '[') {
                current = readTableHeader(p, root) ?: root
                continue
            }

            val keyPath = readKeyPath(p) ?: run { p.skipLine(); null } ?: continue
            p.skipInlineSpace()
            if (p.peek() != '=') { p.skipLine(); continue }
            p.next()
            p.skipInlineSpace()
            val value = readValue(p)
            assign(current, keyPath, value)
            p.skipLine()
        }
        return root
    }

    // ── header ───────────────────────────────────────────────────────────────

    /** Consumes a `[table]` / `[[array]]` header and returns the table to write into. */
    private fun readTableHeader(p: Cursor, root: MutableMap<String, Any?>): MutableMap<String, Any?>? {
        p.next() // '['
        val isArray = p.peek() == '['
        if (isArray) p.next()

        val path = readKeyPath(p) ?: run { p.skipLine(); return null }
        p.skipInlineSpace()
        if (p.peek() == ']') p.next()
        if (isArray && p.peek() == ']') p.next()
        p.skipLine()

        return if (isArray) descendIntoArrayOfTables(root, path) else descend(root, path)
    }

    @Suppress("UNCHECKED_CAST")
    private fun descend(root: MutableMap<String, Any?>, path: List<String>): MutableMap<String, Any?> {
        var node = root
        for (part in path) {
            val existing = node[part]
            node = if (existing is MutableMap<*, *>) existing as MutableMap<String, Any?>
                   else LinkedHashMap<String, Any?>().also { node[part] = it }
        }
        return node
    }

    @Suppress("UNCHECKED_CAST")
    private fun descendIntoArrayOfTables(
        root: MutableMap<String, Any?>,
        path: List<String>
    ): MutableMap<String, Any?> {
        val parent = descend(root, path.dropLast(1))
        val key = path.last()
        val list = (parent[key] as? MutableList<Any?>) ?: ArrayList<Any?>().also { parent[key] = it }
        val entry = LinkedHashMap<String, Any?>()
        list.add(entry)
        return entry
    }

    @Suppress("UNCHECKED_CAST")
    private fun assign(table: MutableMap<String, Any?>, path: List<String>, value: Any?) {
        var node = table
        for (part in path.dropLast(1)) {
            val existing = node[part]
            node = if (existing is MutableMap<*, *>) existing as MutableMap<String, Any?>
                   else LinkedHashMap<String, Any?>().also { node[part] = it }
        }
        node[path.last()] = value
    }

    // ── keys ─────────────────────────────────────────────────────────────────

    /** Reads `a`, `"a b"` or `a.b.c`. Returns null when nothing key-shaped is present. */
    private fun readKeyPath(p: Cursor): List<String>? {
        val parts = mutableListOf<String>()
        while (true) {
            p.skipInlineSpace()
            val part = when (p.peek()) {
                '"', '\'' -> readString(p)
                else -> {
                    val sb = StringBuilder()
                    while (!p.eof && (p.peek().isLetterOrDigit() || p.peek() == '_' || p.peek() == '-')) {
                        sb.append(p.next())
                    }
                    sb.toString().takeIf { it.isNotEmpty() }
                }
            } ?: return parts.takeIf { it.isNotEmpty() }
            parts.add(part)
            p.skipInlineSpace()
            if (p.peek() == '.') { p.next(); continue }
            return parts
        }
    }

    // ── values ───────────────────────────────────────────────────────────────

    private fun readValue(p: Cursor): Any? {
        p.skipInlineSpace()
        return when {
            p.eof -> null
            p.peek() == '"' || p.peek() == '\'' -> readString(p)
            p.peek() == '[' -> readArray(p)
            p.peek() == '{' -> readInlineTable(p)
            else -> readScalar(p)
        }
    }

    private fun readArray(p: Cursor): List<Any?> {
        p.next() // '['
        val out = mutableListOf<Any?>()
        while (true) {
            p.skipTrivia()
            if (p.eof) break
            if (p.peek() == ']') { p.next(); break }
            out.add(readValue(p))
            p.skipTrivia()
            if (p.peek() == ',') p.next()
        }
        return out
    }

    private fun readInlineTable(p: Cursor): Map<String, Any?> {
        p.next() // '{'
        val out = LinkedHashMap<String, Any?>()
        while (true) {
            p.skipTrivia()
            if (p.eof) break
            if (p.peek() == '}') { p.next(); break }
            val key = readKeyPath(p) ?: break
            p.skipInlineSpace()
            if (p.peek() == '=') { p.next(); assign(out, key, readValue(p)) }
            p.skipTrivia()
            if (p.peek() == ',') p.next()
        }
        return out
    }

    /** Bare value up to a comma, closing bracket, comment or end of line. */
    private fun readScalar(p: Cursor): Any? {
        val sb = StringBuilder()
        while (!p.eof) {
            val c = p.peek()
            if (c == ',' || c == ']' || c == '}' || c == '\n' || c == '\r' || c == '#') break
            sb.append(p.next())
        }
        val raw = sb.toString().trim()
        return when {
            raw.isEmpty() -> null
            raw == "true" -> true
            raw == "false" -> false
            else -> parseNumber(raw) ?: raw
        }
    }

    private fun parseNumber(raw: String): Any? {
        val cleaned = raw.replace("_", "")
        val negative = cleaned.startsWith("-")
        val body = cleaned.removePrefix("+").removePrefix("-")
        val radix = when {
            body.startsWith("0x", true) -> 16
            body.startsWith("0o", true) -> 8
            body.startsWith("0b", true) -> 2
            else -> 10
        }
        if (radix != 10) {
            val digits = body.substring(2)
            val parsed = digits.toLongOrNull(radix) ?: return null
            return if (negative) -parsed else parsed
        }
        cleaned.toLongOrNull()?.let { return it }
        cleaned.toDoubleOrNull()?.let { return it }
        return null
    }

    // ── strings ──────────────────────────────────────────────────────────────

    private fun readString(p: Cursor): String? {
        val quote = p.peek()
        if (quote != '"' && quote != '\'') return null
        val triple = p.startsWith("$quote$quote$quote")
        val delim = if (triple) "$quote$quote$quote" else quote.toString()
        p.advance(delim.length)
        // A newline immediately after the opening delimiter is trimmed (TOML spec).
        if (triple && (p.peek() == '\n')) p.next()
        else if (triple && p.startsWith("\r\n")) p.advance(2)

        val sb = StringBuilder()
        val literal = quote == '\''
        while (!p.eof) {
            if (p.startsWith(delim)) { p.advance(delim.length); return sb.toString() }
            val c = p.next()
            if (!literal && c == '\\') {
                if (p.eof) break
                when (val esc = p.next()) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    'u' -> sb.append(readCodePoint(p, 4))
                    'U' -> sb.append(readCodePoint(p, 8))
                    '\n' -> p.skipTrivia()   // line-ending backslash: fold whitespace
                    else -> sb.append(esc)
                }
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun readCodePoint(p: Cursor, digits: Int): String {
        val sb = StringBuilder()
        repeat(digits) { if (!p.eof) sb.append(p.next()) }
        val cp = sb.toString().toIntOrNull(16) ?: return ""
        return String(Character.toChars(cp))
    }

    // ── cursor ───────────────────────────────────────────────────────────────

    private class Cursor(private val text: String) {
        private var i = 0
        val eof: Boolean get() = i >= text.length

        fun peek(): Char = if (eof) ' ' else text[i]
        fun next(): Char = text[i++]
        fun advance(n: Int) { i = minOf(i + n, text.length) }
        fun startsWith(s: String): Boolean = text.startsWith(s, i)

        fun skipInlineSpace() {
            while (!eof && (peek() == ' ' || peek() == '\t')) i++
        }

        /** Skips whitespace, newlines and whole-line or trailing comments. */
        fun skipTrivia() {
            while (!eof) {
                val c = peek()
                when {
                    c == ' ' || c == '\t' || c == '\n' || c == '\r' -> i++
                    c == '#' -> while (!eof && peek() != '\n') i++
                    else -> return
                }
            }
        }

        /** Consumes the rest of the current line, including its newline. */
        fun skipLine() {
            while (!eof && peek() != '\n') i++
            if (!eof) i++
        }
    }
}
