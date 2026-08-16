package dev.begeistert.pymcu

import dev.begeistert.pymcu.cli.PyMcuBoardCatalogService
import dev.begeistert.pymcu.lint.PyMcuLint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract tests against the shapes `pymcu boards --json` and `pymcu lint --json` emit. */
class CliJsonTest {

    // ── pymcu boards --json ──────────────────────────────────────────────────

    private val boardsJson = """
        {"boards": [
           {"name": "arduino_uno", "chip": "atmega328p", "group": "Arduino",
            "toolchain": "avr", "programmer": "avrdude"},
           {"name": "raspberry_pi_pico", "chip": "rp2040", "group": "Raspberry Pi",
            "toolchain": "rp2040", "programmer": "rp2040"},
           {"name": "attiny85", "chip": "attiny85", "group": null,
            "toolchain": "avr", "programmer": "avrdude"}],
         "groups": {"Arduino": ["arduino_uno"], "Raspberry Pi": ["raspberry_pi_pico"]},
         "chips": ["atmega328p", "rp2040", "ch32v203"]}
    """.trimIndent()

    @Test
    fun `parses the board catalog`() {
        val catalog = PyMcuBoardCatalogService.parse(boardsJson)
        assertNotNull(catalog)
        assertEquals(3, catalog!!.boards.size)
        assertEquals("atmega328p", catalog.chipOf("arduino_uno"))
        assertEquals("rp2040", catalog.chipOf("raspberry_pi_pico"))
        assertEquals(listOf("atmega328p", "rp2040", "ch32v203"), catalog.chips)
    }

    @Test
    fun `a null group is not a parse failure`() {
        val catalog = PyMcuBoardCatalogService.parse(boardsJson)!!
        assertNull(catalog.boards.first { it.name == "attiny85" }.group)
    }

    @Test
    fun `groups only list boards the catalog actually has`() {
        val grouped = PyMcuBoardCatalogService.parse(boardsJson)!!.groupedBoards()
        assertEquals(listOf("Arduino", "Raspberry Pi"), grouped.map { it.first })
        assertEquals(listOf("arduino_uno"), grouped.first().second.map { it.name })
    }

    @Test
    fun `garbage yields null so the caller can fall back`() {
        assertNull(PyMcuBoardCatalogService.parse("not json"))
        assertNull(PyMcuBoardCatalogService.parse(""))
        assertNull(PyMcuBoardCatalogService.parse("""{"boards": []}"""))
    }

    // ── pymcu lint --json ────────────────────────────────────────────────────

    private val lintJson = """
        {"flavor": "micropython",
         "files": [{"path": "/w/src/main.py", "findings": [
            {"line": 12, "col": 5, "severity": "error", "code": "no-dynamic-list",
             "message": "Lists grow on the heap, which PyMCU has none of.",
             "suggestion": "Use a fixed-size array."},
            {"line": 3, "col": 1, "severity": "info", "code": "compat-mp",
             "message": "`machine` maps to the PyMCU MicroPython compat layer.",
             "suggestion": "Supported -- no change needed."}]}],
         "summary": {"errors": 1, "warnings": 0, "info": 1, "file_count": 1}}
    """.trimIndent()

    @Test
    fun `parses a lint report`() {
        val report = PyMcuLint.parse(lintJson)
        assertNotNull(report)
        assertEquals("micropython", report!!.flavor)
        assertEquals(1, report.files.size)
        assertEquals(2, report.allFindings.size)

        val first = report.allFindings.first()
        assertEquals(12, first.line)
        assertEquals(5, first.col)
        assertEquals("error", first.severity)
        assertEquals("no-dynamic-list", first.code)
        assertTrue(first.suggestion.isNotEmpty())
    }

    /** `lint` reports "no sources found" as an object with an `error` key. */
    @Test
    fun `an error payload is not a report`() {
        assertNull(PyMcuLint.parse("""{"error": "No Python sources found at src"}"""))
    }

    @Test
    fun `an empty report is a report with no findings`() {
        val report = PyMcuLint.parse("""{"flavor": null, "files": []}""")
        assertNotNull(report)
        assertNull(report!!.flavor)
        assertTrue(report.allFindings.isEmpty())
    }

    @Test
    fun `non-json output yields null rather than throwing`() {
        assertNull(PyMcuLint.parse("Traceback (most recent call last):"))
    }
}
