package dev.begeistert.pymcu

import dev.begeistert.pymcu.newproject.PyMcuDriverScaffold
import dev.begeistert.pymcu.newproject.PyMcuNewProjectSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The command line handed to `pymcu new`.
 *
 * This is the whole contract now: the driver writes the project, so the only
 * thing that can be wrong on this side is the arguments. Getting them wrong is
 * how the plugin's own scaffold silently omitted `[tool.pymcu.toolchain]` and
 * `[tool.pymcu.flash]` and produced projects that could not be flashed.
 */
class DriverScaffoldTest {

    private fun args(settings: PyMcuNewProjectSettings, name: String = "blink") =
        PyMcuDriverScaffold.arguments(name, settings)

    private val board = PyMcuNewProjectSettings(
        board = "arduino_uno", chip = null, frequency = 16_000_000,
        packageManager = "pip", stdlib = "micropython", resolvedChip = "atmega328p",
    )

    // ── shape ────────────────────────────────────────────────────────────────

    @Test
    fun `the sub-command and the project name come first`() {
        assertEquals(listOf("new", "blink"), args(board).take(2))
    }

    @Test
    fun `a board project passes the board, never a chip`() {
        val a = args(board)
        assertEquals("arduino_uno", a[a.indexOf("--board") + 1])
        assertFalse("board and chip together are rejected by the driver", a.contains("--chip"))
    }

    @Test
    fun `a bare chip passes the chip, never a board`() {
        val a = args(board.copy(board = null, chip = "attiny85", resolvedChip = null))
        assertEquals("attiny85", a[a.indexOf("--chip") + 1])
        assertFalse(a.contains("--board"))
    }

    @Test
    fun `the wizard's frequency and compat layer are passed through`() {
        val a = args(board)
        assertEquals("16000000", a[a.indexOf("--freq") + 1])
        assertEquals("micropython", a[a.indexOf("--stdlib") + 1])
    }

    @Test
    fun `no compat layer means no stdlib flag`() {
        assertFalse(args(board.copy(stdlib = "")).contains("--stdlib"))
    }

    // ── package manager ──────────────────────────────────────────────────────

    @Test
    fun `the chosen package manager is passed through`() {
        assertEquals("pip", args(board)[args(board).indexOf("--pkg-manager") + 1])
        val poetry = args(board.copy(packageManager = "poetry"))
        assertEquals("poetry", poetry[poetry.indexOf("--pkg-manager") + 1])
    }

    /** `pymcu new` scaffolds for uv, pip and poetry; pipenv would be rejected. */
    @Test
    fun `an unsupported manager falls back rather than being passed on`() {
        val a = args(board.copy(packageManager = "pipenv"))
        assertEquals("uv", a[a.indexOf("--pkg-manager") + 1])
        assertFalse(a.contains("pipenv"))
    }

    @Test
    fun `the offered managers are the ones the driver can scaffold for`() {
        assertEquals(listOf("uv", "pip", "poetry"), PyMcuDriverScaffold.SUPPORTED_PACKAGE_MANAGERS)
    }

    // ── the rest ─────────────────────────────────────────────────────────────

    /** The IDE initialises the repository; a second one would be moved on top. */
    @Test
    fun `git init is left to the IDE`() {
        assertTrue(args(board).contains("--no-git"))
    }

    @Test
    fun `every flag that takes a value actually has one`() {
        val a = args(board)
        for (flag in listOf("--board", "--freq", "--stdlib", "--pkg-manager")) {
            val index = a.indexOf(flag)
            if (index < 0) continue
            assertTrue("$flag has no value", index + 1 < a.size)
            assertFalse("$flag is followed by another flag", a[index + 1].startsWith("--"))
        }
    }
}
