package dev.begeistert.pymcu

import dev.begeistert.pymcu.newproject.PyMcuClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clock the New Project wizard writes into `[tool.pymcu].frequency`.
 *
 * The wizard always passes `--freq`, so the driver never gets to correct a bad
 * default: whatever these functions return is what the generated project runs
 * at. The expected values below are `core/boards.py`, not opinions.
 */
class ClockTest {

    // ── defaults, against core/boards.py ─────────────────────────────────────

    @Test
    fun `the RP chips get the clock their HAL assumes`() {
        assertEquals(125_000_000L, PyMcuClock.forChip("rp2040"))
        assertEquals(150_000_000L, PyMcuClock.forChip("rp2350"))
    }

    @Test
    fun `PIC defaults to 4 MHz, not to the AVR fallback`() {
        assertEquals(4_000_000L, PyMcuClock.forChip("pic16f877a"))
        assertEquals(4_000_000L, PyMcuClock.forChip("pic10f200"))
    }

    @Test
    fun `the RISC-V parts split on the series`() {
        assertEquals(48_000_000L, PyMcuClock.forChip("ch32v003"))
        assertEquals(144_000_000L, PyMcuClock.forChip("ch32v203"))
        assertEquals(144_000_000L, PyMcuClock.forChip("ch32v307"))
    }

    /** Everything else, an AVR included, runs off its internal RC at 8 MHz. */
    @Test
    fun `an unlisted chip falls back to 8 MHz`() {
        assertEquals(8_000_000L, PyMcuClock.forChip("atmega328p"))
        assertEquals(8_000_000L, PyMcuClock.forChip("attiny85"))
        assertEquals(8_000_000L, PyMcuClock.forChip(null))
    }

    @Test
    fun `a board with a crystal overrides its chip's default`() {
        assertEquals(16_000_000L, PyMcuClock.forTarget("arduino_uno", "atmega328p"))
        assertEquals(16_000_000L, PyMcuClock.forTarget("arduino_mega", "atmega2560"))
    }

    /** V-USB needs 16.5 MHz; the ATtiny default of 8 MHz would break USB. */
    @Test
    fun `the Digispark and the Trinket keep their 16 point 5 MHz crystal`() {
        assertEquals(16_500_000L, PyMcuClock.forTarget("digispark", "attiny85"))
        assertEquals(16_500_000L, PyMcuClock.forTarget("adafruit_trinket", "attiny85"))
    }

    @Test
    fun `a board with no entry of its own follows the chip`() {
        assertEquals(125_000_000L, PyMcuClock.forTarget("raspberry_pi_pico", "rp2040"))
        assertEquals(8_000_000L, PyMcuClock.forTarget("attiny85", "attiny85"))
    }

    // ── suggestions ──────────────────────────────────────────────────────────

    @Test
    fun `the suggestions are the ones the architecture can plausibly run`() {
        assertEquals(
            listOf(16_500_000L, 16_000_000L, 8_000_000L, 1_000_000L),
            PyMcuClock.suggestions("arduino_uno", "atmega328p"),
        )
        assertEquals(
            listOf(150_000_000L, 125_000_000L),
            PyMcuClock.suggestions("raspberry_pi_pico", "rp2040"),
        )
        assertEquals(listOf(4_000_000L), PyMcuClock.suggestions(null, "pic16f877a"))
    }

    @Test
    fun `the target's own default is always one of the suggestions`() {
        for (chip in listOf("atmega328p", "attiny85", "rp2040", "rp2350", "pic16f84a", "ch32v003")) {
            val suggestions = PyMcuClock.suggestions(null, chip)
            assertTrue(chip, suggestions.contains(PyMcuClock.forChip(chip)))
        }
        assertTrue(PyMcuClock.suggestions("digispark", "attiny85").contains(16_500_000L))
    }

    /** An unrecognised chip should still offer something rather than nothing. */
    @Test
    fun `an unknown chip is offered every clock PyMCU knows`() {
        val suggestions = PyMcuClock.suggestions(null, "msp430")
        assertTrue(suggestions.contains(16_000_000L))
        assertTrue(suggestions.contains(125_000_000L))
        assertTrue(suggestions.contains(4_000_000L))
    }

    @Test
    fun `suggestions run from fastest to slowest, without repeats`() {
        for (chip in listOf("atmega328p", "rp2350", "ch32v203", null)) {
            val suggestions = PyMcuClock.suggestions(null, chip)
            assertEquals(chip.toString(), suggestions.sortedDescending(), suggestions)
            assertEquals(chip.toString(), suggestions.distinct(), suggestions)
        }
    }

    // ── format ───────────────────────────────────────────────────────────────

    @Test
    fun `a round clock reads as a whole number`() {
        assertEquals("16 MHz", PyMcuClock.format(16_000_000L))
        assertEquals("1 MHz", PyMcuClock.format(1_000_000L))
        assertEquals("150 MHz", PyMcuClock.format(150_000_000L))
    }

    /** 16500000 is 16.5 MHz. Rounding it to 16 would put a wrong value on disk. */
    @Test
    fun `a fractional clock keeps the digits that matter`() {
        assertEquals("16.5 MHz", PyMcuClock.format(16_500_000L))
        assertEquals("32.768 kHz", PyMcuClock.format(32_768L))
        assertEquals("12.345678 MHz", PyMcuClock.format(12_345_678L))
    }

    @Test
    fun `anything under a kilohertz stays in hertz`() {
        assertEquals("999 Hz", PyMcuClock.format(999L))
        assertEquals("0 Hz", PyMcuClock.format(0L))
    }

    @Test
    fun `every formatted suggestion parses back to itself`() {
        for (chip in listOf("atmega328p", "rp2040", "pic16f877a", "ch32v003", null)) {
            for (hz in PyMcuClock.suggestions(null, chip)) {
                assertEquals(hz, PyMcuClock.parse(PyMcuClock.format(hz)))
            }
        }
    }

    // ── parse ────────────────────────────────────────────────────────────────

    @Test
    fun `a unit is optional and case does not matter`() {
        assertEquals(16_000_000L, PyMcuClock.parse("16 MHz"))
        assertEquals(16_000_000L, PyMcuClock.parse("16mhz"))
        assertEquals(16_000_000L, PyMcuClock.parse("16000000"))
        assertEquals(16_000_000L, PyMcuClock.parse("16000000 Hz"))
        assertEquals(32_768L, PyMcuClock.parse("32.768 KHZ"))
    }

    @Test
    fun `digit separators are ignored`() {
        assertEquals(16_000_000L, PyMcuClock.parse("16_000_000"))
        assertEquals(16_000_000L, PyMcuClock.parse("16,000,000"))
        assertEquals(16_000_000L, PyMcuClock.parse(" 16 000 000 "))
    }

    /** Through a Double, 16.5 MHz comes out a hertz or two short. */
    @Test
    fun `a fractional value scales exactly`() {
        assertEquals(16_500_000L, PyMcuClock.parse("16.5 MHz"))
        assertEquals(16_500_000L, PyMcuClock.parse("16.500 MHz"))
        assertEquals(1_234_567L, PyMcuClock.parse("1.234567 MHz"))
    }

    @Test
    fun `nonsense is rejected rather than guessed at`() {
        assertNull(PyMcuClock.parse(""))
        assertNull(PyMcuClock.parse("fast"))
        assertNull(PyMcuClock.parse("-16 MHz"))
        assertNull(PyMcuClock.parse("16 GHz"))
        assertNull(PyMcuClock.parse("99999999999999999999 MHz"))
    }

    /** A fraction finer than the unit has no integer hertz value. */
    @Test
    fun `a fraction the unit cannot express is rejected`() {
        assertNull(PyMcuClock.parse("16.5"))
        assertNull(PyMcuClock.parse("16.5 Hz"))
        assertNull(PyMcuClock.parse("1.2345 kHz"))
    }

    @Test
    fun `a bare number is hertz, which the minimum is there to catch`() {
        assertEquals(16L, PyMcuClock.parse("16"))
        assertTrue(PyMcuClock.parse("16")!! < PyMcuClock.MINIMUM_HZ)
        assertTrue(PyMcuClock.parse("32768")!! >= PyMcuClock.MINIMUM_HZ)
    }
}
