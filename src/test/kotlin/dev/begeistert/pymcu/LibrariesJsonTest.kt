package dev.begeistert.pymcu

import dev.begeistert.pymcu.cli.PyMcuLibraries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract tests against `pymcu libraries --json` and `pymcu search --json`. */
class LibrariesJsonTest {

    // ── pymcu libraries --json ───────────────────────────────────────────────

    private val installedJson = """
        {"chip": "atmega328p", "board": "arduino_uno", "flavors": ["circuitpython"],
         "libraries": [
           {"name": "ledbar", "distribution": "pymcu-lib-ledbar", "version": "0.1.0",
            "summary": "LED bar driver", "modules": ["ledbar"], "categories": ["display"],
            "layer": "native", "repository": "", "reasons": [], "usable": true},
           {"name": "epaper", "distribution": "pymcu-lib-epaper", "version": "0.2.1",
            "summary": "E-paper driver", "modules": ["epaper"], "categories": ["display"],
            "layer": "circuitpython", "repository": "https://example.invalid/epaper",
            "reasons": ["measured as 'too-large' on atmega328p"], "usable": false}],
         "collisions": ["ledbar and bar both provide module `ledbar`"],
         "invalid": []}
    """.trimIndent()

    @Test
    fun `parses the installed list`() {
        val report = PyMcuLibraries.parseInstalled(installedJson)
        assertNotNull(report)
        assertEquals("atmega328p", report!!.chip)
        assertEquals("arduino_uno", report.board)
        assertEquals(listOf("circuitpython"), report.flavors)
        assertEquals(2, report.libraries.size)
    }

    @Test
    fun `everything in the installed list is marked installed`() {
        val report = PyMcuLibraries.parseInstalled(installedJson)!!
        assertTrue(report.libraries.all { it.installed })
    }

    /** The verdict is read from `reasons`, so it cannot disagree with the driver. */
    @Test
    fun `an installed library with reasons does not fit`() {
        val report = PyMcuLibraries.parseInstalled(installedJson)!!
        val ledbar = report.libraries.first { it.name == "ledbar" }
        val epaper = report.libraries.first { it.name == "epaper" }

        assertTrue(ledbar.fits)
        assertTrue(ledbar.reasons.isEmpty())
        assertFalse(epaper.fits)
        assertEquals("measured as 'too-large' on atmega328p", epaper.reasons.first())
    }

    @Test
    fun `reads modules and collisions`() {
        val report = PyMcuLibraries.parseInstalled(installedJson)!!
        assertEquals(listOf("ledbar"), report.libraries.first().modules)
        assertEquals(1, report.collisions.size)
    }

    @Test
    fun `an empty project is a report with no libraries`() {
        val report = PyMcuLibraries.parseInstalled(
            """{"chip": "rp2040", "board": "", "flavors": [], "libraries": [],
                "collisions": [], "invalid": []}"""
        )
        assertNotNull(report)
        assertTrue(report!!.libraries.isEmpty())
    }

    // ── pymcu search --json ──────────────────────────────────────────────────

    private val searchJson = """
        {"chip": "atmega328p", "flavors": [], "source": "cache", "libraries": [
           {"name": "ledbar", "distribution": "pymcu-lib-ledbar", "version": "0.1.0",
            "summary": "LED bar driver", "categories": ["display"], "layer": "native",
            "arch": ["avr"], "status": "active", "repository": "",
            "reasons": [], "fits": true, "installed": true},
           {"name": "wifi", "distribution": "pymcu-lib-wifi", "version": "1.0.0",
            "summary": "WiFi stack", "categories": ["net"], "layer": "native",
            "arch": ["arm"], "status": "active", "repository": "",
            "reasons": ["supports arm; this project targets atmega328p (avr)"],
            "fits": false, "installed": false}]}
    """.trimIndent()

    @Test
    fun `parses search results`() {
        val results = PyMcuLibraries.parseSearch(searchJson)
        assertNotNull(results)
        assertEquals("atmega328p", results!!.chip)
        assertEquals("cache", results.source)
        assertEquals(2, results.libraries.size)
    }

    @Test
    fun `the index reports which entries are already installed`() {
        val results = PyMcuLibraries.parseSearch(searchJson)!!
        assertTrue(results.libraries.first { it.name == "ledbar" }.installed)
        assertFalse(results.libraries.first { it.name == "wifi" }.installed)
    }

    @Test
    fun `an incompatible entry carries the reason it cannot serve the chip`() {
        val wifi = PyMcuLibraries.parseSearch(searchJson)!!.libraries.first { it.name == "wifi" }
        assertFalse(wifi.fits)
        assertTrue(wifi.reasons.first().contains("atmega328p"))
    }

    // ── failure modes ────────────────────────────────────────────────────────

    @Test
    fun `an unreachable index is not a result set`() {
        assertNull(PyMcuLibraries.parseSearch("""{"error": "Could not reach the library index."}"""))
    }

    @Test
    fun `non-json output yields null rather than throwing`() {
        assertNull(PyMcuLibraries.parseSearch("Traceback (most recent call last):"))
        assertNull(PyMcuLibraries.parseInstalled(""))
        // A payload with no `libraries` key is some other command's output.
        assertNull(PyMcuLibraries.parseInstalled("""{"chip": "rp2040"}"""))
    }
}
