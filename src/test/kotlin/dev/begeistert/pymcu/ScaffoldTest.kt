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
        assertEquals("", PyMcuScaffold.compilerExtra("pic16f84a"))
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

    @Test
    fun `the starter is not indented either`() {
        for (flavor in listOf("micropython", "circuitpython", "")) {
            val source = PyMcuScaffold.mainPy(microPython.copy(stdlib = flavor))
            assertFalse("$flavor starter starts indented", source.first().isWhitespace())
            assertTrue(source.endsWith("\n"))
        }
    }
}
