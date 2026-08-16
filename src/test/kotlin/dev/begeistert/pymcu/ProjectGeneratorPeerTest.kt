package dev.begeistert.pymcu

import com.intellij.testFramework.LightPlatformTestCase
import dev.begeistert.pymcu.newproject.PyMcuClock
import dev.begeistert.pymcu.newproject.PyMcuDriverScaffold
import dev.begeistert.pymcu.newproject.PyMcuProjectGeneratorPeer
import dev.begeistert.pymcu.settings.PyMcuSettings

/**
 * The wizard panel builds, and hands back the settings it is showing.
 *
 * WHY this one test needs the platform: the Kotlin UI DSL checks its own
 * structure while the panel is being built — a bound radio group whose buttons
 * carry no value, a cell asked for a comment it was never given — and it
 * reports the problem by throwing. None of that shows up at compile time, and
 * the only symptom in a running IDE is a New Project entry that dies the
 * moment it is clicked.
 *
 * It also pins the binding: the API choice is read back through
 * [com.intellij.openapi.ui.DialogPanel.apply], so a panel that builds but does
 * not bind would quietly scaffold every project against CircuitPython.
 */
class ProjectGeneratorPeerTest : LightPlatformTestCase() {

    private var configuredExecutable = "pymcu"

    /**
     * Points the CLI at a name that cannot resolve, so the panel's background
     * fetch fails immediately instead of shelling out to whatever `pymcu` the
     * machine running the tests happens to have. The catalog is then the
     * built-in fallback, which is what the assertions below describe.
     */
    override fun setUp() {
        super.setUp()
        val settings = PyMcuSettings.getInstance()
        configuredExecutable = settings.executablePath
        settings.executablePath = "pymcu-not-installed-for-tests"
    }

    override fun tearDown() {
        try {
            PyMcuSettings.getInstance().executablePath = configuredExecutable
        } finally {
            super.tearDown()
        }
    }

    fun testThePanelBuildsAndCarriesItsDefaults() {
        val settings = PyMcuProjectGeneratorPeer().settings

        assertEquals("arduino_uno", settings.board)
        assertNull("a board project must not also name a chip", settings.chip)
        assertEquals("atmega328p", settings.resolvedChip)
        assertEquals(16_000_000L, settings.frequency)
        assertEquals("circuitpython", settings.stdlib)
        assertTrue(settings.packageManager in PyMcuDriverScaffold.SUPPORTED_PACKAGE_MANAGERS)
    }

    /** The default has to be the board's clock, not the chip's — see [PyMcuClock]. */
    fun testTheDefaultClockIsTheOneTheDriverWouldWrite() {
        val settings = PyMcuProjectGeneratorPeer().settings
        assertEquals(PyMcuClock.forTarget(settings.board, settings.resolvedChip), settings.frequency)
    }

    fun testAPanelNobodyHasTouchedReportsNoProblem() {
        assertNull(PyMcuProjectGeneratorPeer().validate())
    }
}
