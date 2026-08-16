package dev.begeistert.pymcu

import dev.begeistert.pymcu.config.PyMcuConfigReader
import dev.begeistert.pymcu.config.TomlWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The conditions the pyproject inspection reports, and the edits its quick fixes
 * apply. The inspection class itself needs a live PSI file, so what is pinned
 * here is the pair that decides the behaviour: the detection flags on the parsed
 * config, and the ranges and rewrites the fixes are built from.
 */
class PyprojectDiagnosticsTest {

    private val section = "tool.pymcu"

    // ── detection ────────────────────────────────────────────────────────────

    @Test
    fun `a well-formed board project reports nothing`() {
        val config = PyMcuConfigReader.parseContent(
            """
            [tool.pymcu]
            board = "arduino_uno"
            frequency = 16000000
            """.trimIndent()
        )!!
        assertFalse(config.usesDeprecatedChipKey)
        assertFalse(config.hasConflictingTarget)
        assertFalse(config.hasNoTarget)
    }

    @Test
    fun `the deprecated chip key is detected on its own`() {
        val config = PyMcuConfigReader.parseContent("[tool.pymcu]\nchip = \"atmega328p\"")!!
        assertTrue(config.usesDeprecatedChipKey)
        assertFalse(config.hasNoTarget)
        assertFalse(config.hasConflictingTarget)
    }

    @Test
    fun `board plus target is a conflict, board plus chip too`() {
        val withTarget = PyMcuConfigReader.parseContent(
            "[tool.pymcu]\nboard = \"arduino_uno\"\ntarget = \"atmega328p\""
        )!!
        val withChip = PyMcuConfigReader.parseContent(
            "[tool.pymcu]\nboard = \"arduino_uno\"\nchip = \"atmega328p\""
        )!!
        assertTrue(withTarget.hasConflictingTarget)
        assertTrue(withChip.hasConflictingTarget)
    }

    @Test
    fun `a section with no target at all is detected`() {
        val config = PyMcuConfigReader.parseContent("[tool.pymcu]\nfrequency = 16000000")!!
        assertTrue(config.hasNoTarget)
    }

    // ── highlight ranges ─────────────────────────────────────────────────────

    private val document = """
        [project]
        name = "blinky"

        [tool.pymcu]
        # target lives here
        chip = "atmega328p"
        frequency = 16000000
    """.trimIndent() + "\n"

    @Test
    fun `the highlight covers the key, not the whole line`() {
        val range = TomlWriter.keyRange(document, section, "chip")
        assertNotNull(range)
        assertEquals("chip", document.substring(range!!.first, range.last + 1))
    }

    @Test
    fun `a key of the same name in another section is not matched`() {
        val text = """
            [tool.other]
            board = "not-mine"

            [tool.pymcu]
            target = "attiny85"
        """.trimIndent()
        assertNull(TomlWriter.keyRange(text, section, "board"))
        assertNotNull(TomlWriter.keyRange(text, section, "target"))
    }

    @Test
    fun `the section header is the fallback anchor when no key is at fault`() {
        val range = TomlWriter.sectionHeaderRange(document, section)
        assertNotNull(range)
        assertEquals("[tool.pymcu]", document.substring(range!!.first, range.last + 1))
    }

    @Test
    fun `an absent key or section has no range`() {
        assertNull(TomlWriter.keyRange(document, section, "board"))
        assertNull(TomlWriter.keyRange(document, "tool.absent", "chip"))
        assertNull(TomlWriter.sectionHeaderRange(document, "tool.absent"))
    }

    // ── what the fixes produce ───────────────────────────────────────────────

    @Test
    fun `the chip fix migrates to target and clears the warning`() {
        val fixed = TomlWriter.renameKey(document, section, "chip", "target")
        val config = PyMcuConfigReader.parseContent(fixed)!!

        assertEquals("atmega328p", config.target)
        assertFalse(config.usesDeprecatedChipKey)
        assertFalse(config.hasNoTarget)
        // The comment above the key is why these fixes rewrite text, not PSI.
        assertTrue(fixed.contains("# target lives here"))
    }

    @Test
    fun `each conflict fix leaves exactly one target key`() {
        val conflicted = """
            [tool.pymcu]
            board = "arduino_uno"
            target = "atmega328p"
        """.trimIndent()

        val keepBoard = PyMcuConfigReader.parseContent(
            TomlWriter.removeKey(conflicted, section, "target")
        )!!
        assertEquals("arduino_uno", keepBoard.board)
        assertNull(keepBoard.target)
        assertFalse(keepBoard.hasConflictingTarget)

        val keepTarget = PyMcuConfigReader.parseContent(
            TomlWriter.removeKey(conflicted, section, "board")
        )!!
        assertEquals("atmega328p", keepTarget.target)
        assertNull(keepTarget.board)
        assertFalse(keepTarget.hasConflictingTarget)
    }

    @Test
    fun `a fix that changes nothing is a no-op on the document`() {
        assertEquals(document, TomlWriter.renameKey(document, section, "board", "target"))
    }
}
