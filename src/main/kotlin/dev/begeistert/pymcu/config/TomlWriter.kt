package dev.begeistert.pymcu.config

/**
 * Surgical, format-preserving edits to a TOML document.
 *
 * WHY text patches instead of re-serializing: [TomlLite] gives a correct read,
 * but writing the parsed model back would drop every comment, blank line and
 * key ordering the user put there. `pyproject.toml` is a hand-edited file, so
 * the edits touch only the bytes that change — the same approach the VS Code
 * extension takes in `pyproject.ts`.
 */
object TomlWriter {

    /**
     * Sets `key = value` inside `[section]`, creating either if missing.
     * [value] must already be valid TOML — quote strings before calling.
     */
    fun setKey(content: String, section: String, key: String, value: String): String {
        val bounds = sectionBounds(content, section)
            ?: run {
                val separator = if (content.isEmpty() || content.endsWith("\n")) "" else "\n"
                return "$content$separator\n[$section]\n$key = $value\n"
            }

        val body = content.substring(bounds.first, bounds.second)
        val keyRe = Regex("""^([ \t]*)${Regex.escape(key)}[ \t]*=.*$""", RegexOption.MULTILINE)
        val existing = keyRe.find(body)
        if (existing != null) {
            val patched = replaceRange(body, existing, "${existing.groupValues[1]}$key = $value")
            return content.substring(0, bounds.first) + patched + content.substring(bounds.second)
        }

        // Append at the end of the section body, before any trailing blank lines.
        val trimmed = body.trimEnd()
        val trailing = body.substring(trimmed.length)
        val suffix = if (trailing.contains("\n")) trailing else "\n"
        return content.substring(0, bounds.first) +
            "$trimmed\n$key = $value$suffix" +
            content.substring(bounds.second)
    }

    /** Removes `key` from `[section]` when present. */
    fun removeKey(content: String, section: String, key: String): String {
        val bounds = sectionBounds(content, section) ?: return content
        val body = content.substring(bounds.first, bounds.second)
        val keyRe = Regex("""^[ \t]*${Regex.escape(key)}[ \t]*=.*\n?""", RegexOption.MULTILINE)
        val match = keyRe.find(body) ?: return content
        return content.substring(0, bounds.first) +
            replaceRange(body, match, "") +
            content.substring(bounds.second)
    }

    /** Renames `oldKey` to `newKey` inside `[section]`, keeping its value in place. */
    fun renameKey(content: String, section: String, oldKey: String, newKey: String): String {
        val bounds = sectionBounds(content, section) ?: return content
        val body = content.substring(bounds.first, bounds.second)
        val keyRe = Regex("""^([ \t]*)${Regex.escape(oldKey)}([ \t]*=)""", RegexOption.MULTILINE)
        val match = keyRe.find(body) ?: return content
        val renamed = "${match.groupValues[1]}$newKey${match.groupValues[2]}"
        return content.substring(0, bounds.first) +
            replaceRange(body, match, renamed) +
            content.substring(bounds.second)
    }

    /** Replaces only [match] in [text] — `Regex.replace` with a lambda hits every occurrence. */
    private fun replaceRange(text: String, match: MatchResult, replacement: String): String =
        text.substring(0, match.range.first) + replacement + text.substring(match.range.last + 1)

    /**
     * Character range of `key` inside `[section]`, or null when either is absent.
     * Used to anchor inspection highlights on the offending key rather than on
     * the whole file.
     */
    fun keyRange(content: String, section: String, key: String): IntRange? {
        val bounds = sectionBounds(content, section) ?: return null
        val body = content.substring(bounds.first, bounds.second)
        val match = Regex("""^[ \t]*(${Regex.escape(key)})[ \t]*=""", RegexOption.MULTILINE)
            .find(body) ?: return null
        val group = match.groups[1] ?: return null
        val start = bounds.first + group.range.first
        return start..(bounds.first + group.range.last)
    }

    /** Character range of the `[section]` header itself, or null when absent. */
    fun sectionHeaderRange(content: String, section: String): IntRange? {
        val match = Regex("""^\[[ \t]*${Regex.escape(section)}[ \t]*]""", RegexOption.MULTILINE)
            .find(content) ?: return null
        return match.range
    }

    /** Quotes a string as a TOML basic string. */
    fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /**
     * Character range of the body of `[section]` — from just after the header
     * line to just before the next header (or end of file).
     */
    private fun sectionBounds(content: String, section: String): Pair<Int, Int>? {
        val header = Regex("""^\[[ \t]*${Regex.escape(section)}[ \t]*]([ \t]*(#.*)?)$""",
            RegexOption.MULTILINE)
        val match = header.find(content) ?: return null
        var start = match.range.last + 1
        if (start < content.length && content[start] == '\r') start++
        if (start < content.length && content[start] == '\n') start++

        val next = Regex("""^\[""", RegexOption.MULTILINE).find(content, start)
        return start to (next?.range?.first ?: content.length)
    }
}
