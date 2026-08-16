package dev.begeistert.pymcu

import dev.begeistert.pymcu.monitor.PyMcuSerialMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The command that streams a board's UART.
 *
 * It is assembled into a `/bin/sh -c` string, so the parts that matter are the
 * ones a shell can misread: the device flag, which differs between macOS and
 * GNU `stty` and silently means something else when wrong, and the quoting of a
 * port that ultimately comes from the user.
 */
class SerialMonitorTest {

    private fun script(
        port: String = "/dev/cu.usbmodem1101",
        baud: Int = 115_200,
        mac: Boolean = true,
    ): String {
        val plan = PyMcuSerialMonitor.plan(port, baud, windows = false, mac = mac)
        assertTrue("expected a command, got $plan", plan is PyMcuSerialMonitor.Plan.Command)
        return (plan as PyMcuSerialMonitor.Plan.Command).argv.last()
    }

    // ── the command ──────────────────────────────────────────────────────────

    @Test
    fun `the port is configured and then read`() {
        val command = script()
        assertTrue(command.contains("stty"))
        assertTrue(command.contains("115200"))
        assertTrue(command.contains("cat"))
        assertTrue("stty must run before cat", command.indexOf("stty") < command.indexOf("cat"))
    }

    /** `-f` on macOS, `-F` on GNU. The wrong one makes stty read the device as a script. */
    @Test
    fun `the device flag follows the platform`() {
        assertTrue(script(mac = true).contains("stty -f "))
        assertTrue(script(mac = false).contains("stty -F "))
    }

    /**
     * Without `raw`, the line discipline rewrites the bytes and a firmware
     * printing bare newlines comes out as a staircase.
     */
    @Test
    fun `the line discipline is raw and does not echo`() {
        val command = script()
        assertTrue(command.contains(" raw"))
        assertTrue(command.contains("-echo"))
    }

    @Test
    fun `the reader replaces the shell rather than nesting under it`() {
        // exec, so the Stop button kills cat instead of an sh that outlives it.
        assertTrue(script().contains("exec cat"))
    }

    @Test
    fun `it is run through a shell, since it is two commands`() {
        val plan = PyMcuSerialMonitor.plan("/dev/ttyACM0", 9600, windows = false, mac = false)
        val argv = (plan as PyMcuSerialMonitor.Plan.Command).argv
        assertEquals(listOf("/bin/sh", "-c"), argv.dropLast(1))
    }

    // ── quoting ──────────────────────────────────────────────────────────────

    @Test
    fun `the port is quoted, so a space cannot split it`() {
        assertTrue(script(port = "/dev/tty odd").contains("'/dev/tty odd'"))
    }

    @Test
    fun `a quote in the port cannot escape the string`() {
        assertEquals("""'a'\''b'""", PyMcuSerialMonitor.shellQuote("a'b"))
        val command = script(port = """/dev/x'; rm -rf /; '""")
        assertTrue("the injection must stay inside the quotes", command.contains("""'\''"""))
    }

    // ── refusals ─────────────────────────────────────────────────────────────

    @Test
    fun `Windows says so instead of half-working`() {
        val plan = PyMcuSerialMonitor.plan("COM3", 115_200, windows = true, mac = false)
        assertTrue(plan is PyMcuSerialMonitor.Plan.Unsupported)
        // The message has to leave the user able to do it themselves.
        val reason = (plan as PyMcuSerialMonitor.Plan.Unsupported).reason
        assertTrue(reason.contains("COM3"))
        assertTrue(reason.contains("115200"))
    }

    @Test
    fun `a missing port or a nonsense baud is refused`() {
        assertTrue(PyMcuSerialMonitor.plan("", 115_200, false, true) is PyMcuSerialMonitor.Plan.Unsupported)
        assertTrue(PyMcuSerialMonitor.plan("/dev/x", 0, false, true) is PyMcuSerialMonitor.Plan.Unsupported)
        assertTrue(PyMcuSerialMonitor.plan("/dev/x", -1, false, true) is PyMcuSerialMonitor.Plan.Unsupported)
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
