package dev.begeistert.pymcu

import dev.begeistert.pymcu.cli.BoardCatalog
import dev.begeistert.pymcu.cli.BoardInfo
import dev.begeistert.pymcu.cli.PyMcuBoardCatalogService
import dev.begeistert.pymcu.newproject.PyMcuTargets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The entries the New Project wizard offers, and what it says about each one.
 *
 * The picker is built from the catalog rather than from a list here, so the
 * thing worth asserting is that nothing the driver supports can fall out of it
 * on the way — which is precisely how the original hardcoded panel came to hide
 * every RP2040, PIC and RISC-V target.
 */
class TargetsTest {

    private val catalog = BoardCatalog(
        boards = listOf(
            BoardInfo("arduino_uno", "atmega328p", "Arduino", "avr", "avrdude"),
            BoardInfo("arduino_nano", "atmega328p", "Arduino", "avr", "avrdude"),
            BoardInfo("raspberry_pi_pico", "rp2040", "Raspberry Pi", "rp2040", "rp2040"),
            BoardInfo("pico", "rp2040", null, "rp2040", "rp2040"),
        ),
        groups = mapOf(
            "Arduino" to listOf("arduino_uno", "arduino_nano", "arduino_leonardo"),
            "Raspberry Pi" to listOf("raspberry_pi_pico"),
        ),
        chips = listOf("rp2040", "atmega328p", "pic16f877a"),
    )

    private fun boards(catalog: BoardCatalog) = PyMcuTargets.choices(catalog).map { it.board }

    // ── the picker's entries ─────────────────────────────────────────────────

    @Test
    fun `boards come out in the catalog's own group order`() {
        assertEquals(
            listOf("arduino_uno", "arduino_nano", "raspberry_pi_pico", "pico", null),
            boards(catalog),
        )
    }

    @Test
    fun `the group heading is carried by the first entry of each group`() {
        val headings = PyMcuTargets.choices(catalog).map { it.board to it.heading }
        assertEquals(
            listOf(
                "arduino_uno" to "Arduino",
                "arduino_nano" to null,
                "raspberry_pi_pico" to "Raspberry Pi",
                "pico" to PyMcuTargets.UNGROUPED,
                null to PyMcuTargets.ADVANCED,
            ),
            headings,
        )
    }

    /** A board the driver ships without a BOARD_GROUPS entry must still appear. */
    @Test
    fun `an ungrouped board is offered rather than dropped`() {
        assertTrue("pico" in boards(catalog))
    }

    @Test
    fun `a group naming a board the catalog does not have skips it`() {
        assertTrue("arduino_leonardo" !in boards(catalog))
    }

    @Test
    fun `the bare-chip entry is last and names no board`() {
        val last = PyMcuTargets.choices(catalog).last()
        assertNull(last.board)
        assertNull(last.chip)
        assertEquals("Bare chip", PyMcuTargets.label(last))
    }

    @Test
    fun `every board of the built-in fallback survives the trip`() {
        val fallback = PyMcuBoardCatalogService.FALLBACK
        val offered = boards(fallback).filterNotNull().toSet()
        assertEquals(fallback.boards.map { it.name }.toSet(), offered)
    }

    /** The popup's speed search matches on toString, so it has to be searchable. */
    @Test
    fun `an entry reads as its alias and its chip`() {
        val uno = PyMcuTargets.choices(catalog).first()
        assertEquals("arduino_uno  atmega328p", uno.toString())
        assertEquals("arduino_uno", PyMcuTargets.label(uno))
        assertEquals("atmega328p", PyMcuTargets.secondaryLabel(uno))
    }

    // ── bare chips ───────────────────────────────────────────────────────────

    /** PIC and RISC-V have no board in the catalog; the chip list is the only way in. */
    @Test
    fun `the chip list is the catalog's, sorted`() {
        assertEquals(listOf("atmega328p", "pic16f877a", "rp2040"), PyMcuTargets.chips(catalog))
    }

    @Test
    fun `a catalog with no chip list falls back to what its boards use`() {
        assertEquals(
            listOf("atmega328p", "rp2040"),
            PyMcuTargets.chips(catalog.copy(chips = emptyList())),
        )
    }

    // ── toolchain and programmer, against core-boards-py ─────────────────────

    @Test
    fun `each family maps to the toolchain the driver would pick`() {
        assertEquals("avr", PyMcuTargets.toolchainFor("atmega328p"))
        assertEquals("avr", PyMcuTargets.toolchainFor("attiny85"))
        assertEquals("rp2040", PyMcuTargets.toolchainFor("rp2350"))
        assertEquals("gputils", PyMcuTargets.toolchainFor("pic16f877a"))
        assertEquals("riscv", PyMcuTargets.toolchainFor("ch32v003"))
    }

    @Test
    fun `each family maps to the programmer the driver would pick`() {
        assertEquals("avrdude", PyMcuTargets.programmerFor("atmega2560"))
        assertEquals("rp2040", PyMcuTargets.programmerFor("rp2040"))
        assertEquals("wch-link", PyMcuTargets.programmerFor("ch32v203"))
        assertEquals("pk2cmd", PyMcuTargets.programmerFor("pic16f84a"))
    }

    @Test
    fun `case does not decide the toolchain`() {
        assertEquals("avr", PyMcuTargets.toolchainFor("ATmega328P"))
        assertEquals("avrdude", PyMcuTargets.programmerFor("ATmega328P"))
    }

    // ── the line under the chip field ────────────────────────────────────────

    @Test
    fun `a board's description comes from the catalog, not from a guess`() {
        assertEquals(
            "AVR · avr toolchain · flashed with avrdude",
            PyMcuTargets.describe("atmega328p", "avr", "avrdude"),
        )
    }

    @Test
    fun `a bare chip's description is derived from the chip`() {
        assertEquals("ARM · rp2040 toolchain · flashed with rp2040", PyMcuTargets.describe("rp2040"))
        assertEquals("PIC · gputils toolchain · flashed with pk2cmd", PyMcuTargets.describe("pic16f877a"))
        assertEquals("RISC-V · riscv toolchain · flashed with wch-link", PyMcuTargets.describe("ch32v003"))
    }

    @Test
    fun `an unrecognised chip says so instead of naming an architecture`() {
        assertTrue(PyMcuTargets.describe("msp430").startsWith("Unrecognised chip · "))
    }

    @Test
    fun `no chip yet is an instruction, not an empty line`() {
        assertTrue(PyMcuTargets.describe(null).startsWith("Type a chip id"))
        assertTrue(PyMcuTargets.describe("   ").startsWith("Type a chip id"))
    }
}
