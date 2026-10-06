package dev.begeistert.pymcu

import com.intellij.execution.process.ProcessHandler
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.begeistert.pymcu.monitor.PyMcuSerialMonitor
import dev.begeistert.pymcu.run.PyMcuExecutionListener

/**
 * The monitor sessions a flash must move out of the port's way.
 *
 * Two halves, both cheap to get wrong: which port the flash will take
 * (parsed from the run configuration's arguments), and that pausing a
 * monitor kills its process, drops it from the registry, and hands back
 * everything needed to reopen it.
 */
class MonitorFlashCoordinationTest : BasePlatformTestCase() {

    /** A ProcessHandler that records its kill instead of owning a process. */
    private class FakeHandler : ProcessHandler() {
        var destroyed = false
            private set

        init {
            startNotify() // pauseForFlash only ever sees started handlers
        }

        override fun destroyProcessImpl() {
            destroyed = true
        }

        override fun detachProcessImpl() = Unit
        override fun detachIsDefault() = true
        override fun getProcessInput() = null
    }

    private fun session(port: String, baud: Int = 115_200) =
        PyMcuSerialMonitor.Session(project, port, baud, FakeHandler())

    // ── the registry ─────────────────────────────────────────────────────────

    fun testARegisteredMonitorIsFoundByPort() {
        val session = session("/dev/cu.usbmodem1")
        try {
            PyMcuSerialMonitor.register(session)
            assertSame(session, PyMcuSerialMonitor.sessionOn("/dev/cu.usbmodem1"))
        } finally {
            PyMcuSerialMonitor.unregister(session)
        }
    }

    fun testPausingAPortKillsTheMonitorAndReturnsIt() {
        val session = session("/dev/cu.usbmodem1")
        PyMcuSerialMonitor.register(session)

        val paused = PyMcuSerialMonitor.pauseForFlash("/dev/cu.usbmodem1")

        assertEquals(listOf(session), paused)
        assertTrue("the monitor's process must die", (session.handler as FakeHandler).destroyed)
        assertNull("the port must read as free", PyMcuSerialMonitor.sessionOn("/dev/cu.usbmodem1"))
    }

    fun testPausingAnUnwatchedPortTouchesNothing() {
        val session = session("/dev/cu.usbmodem1")
        PyMcuSerialMonitor.register(session)
        try {
            val paused = PyMcuSerialMonitor.pauseForFlash("/dev/ttyUSB0")
            assertTrue(paused.isEmpty())
            assertSame(session, PyMcuSerialMonitor.sessionOn("/dev/cu.usbmodem1"))
        } finally {
            PyMcuSerialMonitor.unregister(session)
        }
    }

    fun testUnregisterOnlyDropsTheSessionItNames() {
        val stale = session("/dev/cu.usbmodem1")
        val live = session("/dev/cu.usbmodem1")
        PyMcuSerialMonitor.register(stale)
        PyMcuSerialMonitor.register(live) // same port reopened: the newer session owns it
        try {
            PyMcuSerialMonitor.unregister(stale)
            assertSame(live, PyMcuSerialMonitor.sessionOn("/dev/cu.usbmodem1"))
        } finally {
            PyMcuSerialMonitor.unregister(live)
        }
    }

    // ── which port the flash will use ────────────────────────────────────────

    fun testNoPortArgumentMeansNoPort() {
        assertNull(PyMcuExecutionListener.portFromArguments(""))
        assertNull(PyMcuExecutionListener.portFromArguments("--verbose"))
    }

    fun testTheLongAndShortSpellingsNameThePort() {
        assertEquals("/dev/x", PyMcuExecutionListener.portFromArguments("--port /dev/x"))
        assertEquals("/dev/x", PyMcuExecutionListener.portFromArguments("--port=/dev/x"))
        assertEquals("/dev/x", PyMcuExecutionListener.portFromArguments("-P /dev/x"))
        assertEquals("/dev/x", PyMcuExecutionListener.portFromArguments("-P/dev/x"))
    }

    fun testThePortArgumentWinsWhereverItSits() {
        assertEquals(
            "/dev/board",
            PyMcuExecutionListener.portFromArguments("--verbose --port /dev/board --reset")
        )
    }
}
