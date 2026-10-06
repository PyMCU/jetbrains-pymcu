package dev.begeistert.pymcu

import dev.begeistert.pymcu.monitor.PyMcuSerialMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `pymcu monitor` invocation the console runs.
 *
 * The serial work moved to the driver — what is left here to get wrong is the
 * argument vector itself, and the refusals that keep a nonsense port or baud
 * from reaching it.
 */
class SerialMonitorTest {

    private fun argv(
        port: String = "/dev/cu.usbmodem1101",
        baud: Int = 115_200,
        executable: String = "/venv/bin/pymcu",
    ): List<String> {
        val plan = PyMcuSerialMonitor.plan(executable, port, baud)
        assertTrue("expected a command, got $plan", plan is PyMcuSerialMonitor.Plan.Command)
        return (plan as PyMcuSerialMonitor.Plan.Command).argv
    }

    // ── the command ──────────────────────────────────────────────────────────

    @Test
    fun `it runs pymcu monitor on the port at the baud`() {
        assertEquals(
            listOf("/venv/bin/pymcu", "monitor", "--port", "/dev/cu.usbmodem1101", "--baud", "115200"),
            argv()
        )
    }

    /** The executable is argv[0], never baked in — the project's own venv supplies it. */
    @Test
    fun `the executable is whatever the CLI lookup returned`() {
        assertEquals("/other/pymcu", argv(executable = "/other/pymcu").first())
    }

    @Test
    fun `the baud reaches the driver as given`() {
        assertTrue(argv(baud = 9600).contains("9600"))
    }

    // ── refusals ─────────────────────────────────────────────────────────────

    @Test
    fun `a missing port or a nonsense baud is refused`() {
        assertTrue(PyMcuSerialMonitor.plan("pymcu", "", 115_200) is PyMcuSerialMonitor.Plan.Unsupported)
        assertTrue(PyMcuSerialMonitor.plan("pymcu", "/dev/x", 0) is PyMcuSerialMonitor.Plan.Unsupported)
        assertTrue(PyMcuSerialMonitor.plan("pymcu", "/dev/x", -1) is PyMcuSerialMonitor.Plan.Unsupported)
    }

    // ── the console tab ──────────────────────────────────────────────────────

    @Test
    fun `the title names the device and the speed`() {
        assertEquals("cu.usbmodem1101 · 115200 baud", PyMcuSerialMonitor.title("/dev/cu.usbmodem1101", 115_200))
    }

    @Test
    fun `the default baud is the driver's`() {
        assertEquals(115_200, PyMcuSerialMonitor.DEFAULT_BAUD)
    }
}
