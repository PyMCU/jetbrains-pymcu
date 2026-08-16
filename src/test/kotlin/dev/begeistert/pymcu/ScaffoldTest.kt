package dev.begeistert.pymcu

import dev.begeistert.pymcu.config.PyMcuConfigReader
import dev.begeistert.pymcu.newproject.PyMcuNewProjectSettings
import dev.begeistert.pymcu.newproject.PyMcuScaffold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the New Project wizard writes to disk.
 *
 * The regression that prompted these: `pyproject.toml` came out indented twelve
 * spaces, with the interpolated lines flush left, because `trimIndent()` runs
 * after interpolation and a multi-line `$value` at column 0 dragged the common
 * indent to zero.
 */
class ScaffoldTest {

    private val microPython = PyMcuNewProjectSettings(
        board = "arduino_uno", chip = null, frequency = 16_000_000,
        packageManager = "uv", stdlib = "micropython", resolvedChip = "atmega328p",
    )

    // ── formatting ───────────────────────────────────────────────────────────

    @Test
    fun `top-level keys start at column zero`() {
        val toml = PyMcuScaffold.pyproject("blink", microPython)
        val offenders = toml.lines().filter { line ->
            line.isNotBlank() && line.first().isWhitespace() && !line.trimStart().startsWith("\"")
        }
        assertTrue("these lines are indented: $offenders", offenders.isEmpty())
    }

    @Test
    fun `only the dependency entries are indented, by four spaces`() {
        val indented = PyMcuScaffold.pyproject("blink", microPython)
            .lines().filter { it.isNotBlank() && it.first().isWhitespace() }
        assertTrue(indented.isNotEmpty())
        assertTrue(indented.all { it.startsWith("    \"") && !it.startsWith("     ") })
    }

    @Test
    fun `sections and the trailing newline are where they should be`() {
        val toml = PyMcuScaffold.pyproject("blink", microPython)
        assertTrue(toml.startsWith("[project]\n"))
        assertTrue(toml.contains("\n[tool.pymcu]\n"))
        assertTrue(toml.endsWith("\n"))
        assertFalse("no blank line should end the file twice", toml.endsWith("\n\n"))
    }

    // ── round trip through the plugin's own reader ───────────────────────────

    @Test
    fun `the generated file parses back to the choices made`() {
        val config = PyMcuConfigReader.parseContent(PyMcuScaffold.pyproject("blink", microPython))
        assertNotNull(config)
        assertEquals("arduino_uno", config!!.board)
        assertEquals(16_000_000L, config.frequency)
        assertEquals("src", config.sources)
        assertEquals("main.py", config.entry)
        assertEquals(listOf("micropython"), config.stdlib)
        assertFalse(config.hasNoTarget)
        assertFalse(config.hasConflictingTarget)
        assertFalse(config.usesDeprecatedChipKey)
    }

    @Test
    fun `a bare chip project writes target, never the deprecated key`() {
        val settings = PyMcuNewProjectSettings(
            board = null, chip = "attiny85", stdlib = "", resolvedChip = null,
        )
        val toml = PyMcuScaffold.pyproject("tiny", settings)
        val config = PyMcuConfigReader.parseContent(toml)!!

        assertEquals("attiny85", config.target)
        assertEquals(null, config.board)
        assertFalse(config.usesDeprecatedChipKey)
        assertTrue(config.stdlib.isEmpty())
        // The key, not the substring: `pymcu-stdlib` is a dependency either way.
        assertFalse(
            "no stdlib key when none was chosen",
            toml.lines().any { it.trimStart().startsWith("stdlib") }
        )
    }

    // ── dependencies ─────────────────────────────────────────────────────────

    /** pip only considers pre-releases when the specifier names one. */
    @Test
    fun `every dependency admits the alpha releases`() {
        val deps = PyMcuScaffold.dependencies(microPython)
        assertTrue(deps.isNotEmpty())
        assertTrue("bare names would make pip refuse every published build",
            deps.all { it.contains(">=") && it.substringAfter(">=").contains("a") })
    }

    /** Without the extra, the compiler installs with no backend for the chip. */
    @Test
    fun `the compiler carries the backend extra for the chip family`() {
        assertEquals("[avr]", PyMcuScaffold.compilerExtra("atmega328p"))
        assertEquals("[avr]", PyMcuScaffold.compilerExtra("attiny85"))
        assertEquals("[arm]", PyMcuScaffold.compilerExtra("rp2040"))
        assertEquals("[arm]", PyMcuScaffold.compilerExtra("rp2350"))
        // No riscv extra is published, so an empty one is correct there.
        assertEquals("", PyMcuScaffold.compilerExtra("ch32v003"))
        assertEquals("[pic]", PyMcuScaffold.compilerExtra("pic16f84a"))
        assertEquals("", PyMcuScaffold.compilerExtra(null))
    }

    @Test
    fun `a board resolves to its chip family for the extra`() {
        val deps = PyMcuScaffold.dependencies(microPython)
        assertTrue(deps.any { it.startsWith("pymcu-compiler[avr]>=") })
    }

    @Test
    fun `the compat flavor is a dependency, and absent when none is chosen`() {
        assertTrue(PyMcuScaffold.dependencies(microPython).any { it.startsWith("pymcu-micropython>=") })
        val native = PyMcuNewProjectSettings(board = "arduino_uno", stdlib = "", resolvedChip = "atmega328p")
        assertTrue(PyMcuScaffold.dependencies(native).none { it.contains("micropython") })
    }

    @Test
    fun `requirements txt lists exactly the pyproject dependencies`() {
        val deps = PyMcuScaffold.dependencies(microPython)
        assertEquals(deps, PyMcuScaffold.requirements(microPython).trim().lines())
    }

    // ── starters ─────────────────────────────────────────────────────────────

    @Test
    fun `each flavor gets the starter that matches how it is written`() {
        assertTrue(PyMcuScaffold.mainPy(microPython).contains("from machine import Pin"))

        val circuit = microPython.copy(stdlib = "circuitpython")
        assertTrue(PyMcuScaffold.mainPy(circuit).contains("import digitalio"))

        // The compat flavors run at module level; the native target keeps main().
        val native = microPython.copy(stdlib = "")
        assertTrue(PyMcuScaffold.mainPy(native).contains("def main():"))
        assertFalse(PyMcuScaffold.mainPy(microPython).contains("def main():"))
    }

    /**
     * The regression: the native starter emitted `Pin("PB5", Pin.OUT)` for every
     * target. That is an AVR port name, and hal/rp2040/gpio.py takes a uint8 GP
     * index, so a native RP2040 project shipped a main.py that could not compile.
     */
    @Test
    fun `the native starter addresses the chip's own registers`() {
        val avr = PyMcuScaffold.mainPy(microPython.copy(stdlib = "", resolvedChip = "atmega328p"))
        assertTrue(avr.contains("from pymcu.chips.atmega328p import DDRB, PORTB, DDB5, PORTB5"))
        assertTrue(avr.contains("PORTB[PORTB5] = 1"))

        val pic = PyMcuScaffold.mainPy(
            PyMcuNewProjectSettings(board = null, chip = "pic16f877a", stdlib = "")
        )
        assertTrue(pic.contains("from pymcu.chips.pic16f877a import TRISB, PORTB, RB0"))

        val rp = PyMcuScaffold.mainPy(microPython.copy(stdlib = "", resolvedChip = "rp2040"))
        assertTrue(rp.contains("from pymcu.chips.rp2040 import PORTB"))
    }

    @Test
    fun `no starter claims a Pin API that the chip does not have`() {
        for (chip in listOf("atmega328p", "pic16f877a", "rp2040", "ch32v003")) {
            val source = PyMcuScaffold.mainPy(microPython.copy(stdlib = "", resolvedChip = chip))
            assertFalse("$chip got an AVR port name", source.contains("\"PB5\""))
        }
    }

    /** No catalog means no chip, and a native program needs one by name. */
    @Test
    fun `an unresolvable chip yields a starter that says so instead of one that breaks`() {
        val source = PyMcuScaffold.mainPy(
            PyMcuNewProjectSettings(board = "arduino_uno", chip = null, stdlib = "", resolvedChip = null)
        )
        // The import statement, not the substring: the explanatory comment
        // mentions `pymcu.chips.<your chip>` on purpose.
        assertFalse(source.lines().any { it.startsWith("from pymcu.chips.") })
        assertTrue(source.contains("def main():"))
        assertTrue(source.contains("pymcu.chips.<your chip>"))
    }

    @Test
    fun `the starter is not indented either`() {
        for (flavor in listOf("micropython", "circuitpython", "")) {
            val source = PyMcuScaffold.mainPy(microPython.copy(stdlib = flavor))
            assertFalse("$flavor starter starts indented", source.first().isWhitespace())
            assertTrue(source.endsWith("\n"))
        }
    }
}
