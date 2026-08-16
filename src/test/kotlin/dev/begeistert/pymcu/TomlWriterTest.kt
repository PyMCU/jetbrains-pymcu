package dev.begeistert.pymcu

import dev.begeistert.pymcu.config.PyMcuConfigReader
import dev.begeistert.pymcu.config.TomlWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TomlWriterTest {

    private val original = """
        [project]
        name = "blinky"

        [tool.pymcu]
        # The board decides the chip.
        board = "arduino_uno"
        frequency = 16000000
        sources = "src"

        [tool.pymcu.flash]
        programmer = "avrdude"
    """.trimIndent() + "\n"

    // ── set ──────────────────────────────────────────────────────────────────

    @Test
    fun `replaces an existing key in place`() {
        val out = TomlWriter.setKey(original, "tool.pymcu", "board", "\"raspberry_pi_pico\"")
        assertTrue(out.contains("""board = "raspberry_pi_pico""""))
        assertFalse(out.contains("arduino_uno"))
    }

    @Test
    fun `keeps comments, ordering and neighbouring sections`() {
        val out = TomlWriter.setKey(original, "tool.pymcu", "frequency", "8000000")
        assertTrue(out.contains("# The board decides the chip."))
        assertTrue(out.contains("""name = "blinky""""))
        assertTrue(out.contains("[tool.pymcu.flash]"))
        assertEquals(8_000_000L, PyMcuConfigReader.parseContent(out)!!.frequency)
    }

    @Test
    fun `adds a missing key at the end of the section`() {
        val out = TomlWriter.setKey(original, "tool.pymcu", "entry", "\"main.py\"")
        assertEquals("main.py", PyMcuConfigReader.parseContent(out)!!.entry)
        // …and not after the following section header.
        assertTrue(out.indexOf("entry = ") < out.indexOf("[tool.pymcu.flash]"))
    }

    @Test
    fun `creates a missing section`() {
        val out = TomlWriter.setKey(original, "tool.pymcu.ffi", "sources", "[\"native/x.c\"]")
        assertTrue(out.contains("[tool.pymcu.ffi]"))
        assertTrue(PyMcuConfigReader.parseContent(out)!!.hasFfi)
    }

    @Test
    fun `writes into a nested section without touching its parent`() {
        val out = TomlWriter.setKey(original, "tool.pymcu.flash", "port", "\"/dev/ttyACM0\"")
        val config = PyMcuConfigReader.parseContent(out)!!
        assertEquals("/dev/ttyACM0", config.flash.port)
        assertEquals("arduino_uno", config.board)
    }

    // ── remove and rename ────────────────────────────────────────────────────

    @Test
    fun `removes a key`() {
        val out = TomlWriter.removeKey(original, "tool.pymcu", "sources")
        assertFalse(out.contains("sources ="))
        // The default applies once the key is gone.
        assertEquals("src", PyMcuConfigReader.parseContent(out)!!.sources)
        assertEquals("arduino_uno", PyMcuConfigReader.parseContent(out)!!.board)
    }

    @Test
    fun `removing an absent key is a no-op`() {
        assertEquals(original, TomlWriter.removeKey(original, "tool.pymcu", "nothing_here"))
    }

    /** The `chip` → `target` migration the driver warns about. */
    @Test
    fun `renames a key, keeping its value`() {
        val legacy = "[tool.pymcu]\nchip = \"atmega328p\"\nfrequency = 16000000\n"
        val out = TomlWriter.renameKey(legacy, "tool.pymcu", "chip", "target")
        val config = PyMcuConfigReader.parseContent(out)!!
        assertEquals("atmega328p", config.target)
        assertFalse(config.usesDeprecatedChipKey)
    }

    // ── quoting ──────────────────────────────────────────────────────────────

    @Test
    fun `quotes values that contain quotes or backslashes`() {
        val out = TomlWriter.setKey(
            original, "tool.pymcu.flash", "port", TomlWriter.quote("""C:\dev\"COM3"""")
        )
        assertEquals("""C:\dev\"COM3"""", PyMcuConfigReader.parseContent(out)!!.flash.port)
    }

    // ── round trip ───────────────────────────────────────────────────────────

    @Test
    fun `a board to bare-chip switch leaves exactly one target key`() {
        var out = TomlWriter.removeKey(original, "tool.pymcu", "chip")
        out = TomlWriter.removeKey(out, "tool.pymcu", "board")
        out = TomlWriter.setKey(out, "tool.pymcu", "target", "\"attiny85\"")

        val config = PyMcuConfigReader.parseContent(out)!!
        assertEquals("attiny85", config.target)
        assertEquals(null, config.board)
        assertFalse(config.hasConflictingTarget)
    }
}
