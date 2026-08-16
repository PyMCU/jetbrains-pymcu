package dev.begeistert.pymcu

import dev.begeistert.pymcu.resolver.PyMcuChipInfoService
import dev.begeistert.pymcu.resolver.PyMcuHalDispatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Picking the right architecture out of the HAL's compile-time dispatch.
 *
 * `pymcu/hal/gpio.py` imports `Pin` from one of six architecture directories
 * depending on `__CHIP__`. The compiler keeps one branch; the IDE sees all six.
 * These pin which one the plugin considers live, and — as importantly — where it
 * refuses to have an opinion.
 */
class HalDispatchTest {

    private val avrPin = "/p/.venv/lib/python3.12/site-packages/pymcu/hal/avr/gpio/__init__.py"
    private val rpPin = "/p/.venv/lib/python3.12/site-packages/pymcu/hal/rp2040/gpio.py"
    private val facade = "/p/.venv/lib/python3.12/site-packages/pymcu/hal/gpio.py"

    // ── locating the architecture directory ──────────────────────────────────

    @Test
    fun `the architecture directory is read off the path`() {
        assertEquals("avr", PyMcuHalDispatch.architectureDirectoryOf(avrPin))
        assertEquals("rp2040", PyMcuHalDispatch.architectureDirectoryOf(rpPin))
    }

    /** The facades live directly in `hal/` and dispatch; they are not a target. */
    @Test
    fun `a facade is not an architecture directory`() {
        assertNull(PyMcuHalDispatch.architectureDirectoryOf(facade))
    }

    @Test
    fun `shared helpers are left alone`() {
        assertNull(PyMcuHalDispatch.architectureDirectoryOf("/p/sp/pymcu/hal/_servo/servo.py"))
    }

    @Test
    fun `anything outside the HAL is none of our business`() {
        assertNull(PyMcuHalDispatch.architectureDirectoryOf("/p/sp/pymcu/drivers/_lcd/gpio.py"))
        assertNull(PyMcuHalDispatch.architectureDirectoryOf("/p/sp/pymcu_micropython/machine.py"))
        assertNull(PyMcuHalDispatch.architectureDirectoryOf("/usr/lib/python3.12/os.py"))
    }

    @Test
    fun `windows separators are handled`() {
        assertEquals("avr", PyMcuHalDispatch.architectureDirectoryOf("""C:\p\sp\pymcu\hal\avr\gpio.py"""))
    }

    // ── which directory serves which chip ────────────────────────────────────

    @Test
    fun `the architecture directory serves its own chips`() {
        assertTrue(PyMcuHalDispatch.isLive("avr", "atmega328p", "avr"))
        assertTrue(PyMcuHalDispatch.isLive("riscv", "ch32v203", "riscv"))
    }

    /** The RP facades branch on the chip name, not the architecture. */
    @Test
    fun `an RP chip is served by its own directory, not by arm`() {
        assertTrue(PyMcuHalDispatch.isLive("rp2040", "rp2040", "arm"))
        assertFalse(PyMcuHalDispatch.isLive("rp2350", "rp2040", "arm"))
    }

    /** `hal/rp/` holds what both RP chips share. */
    @Test
    fun `the shared rp directory serves both RP chips`() {
        assertTrue(PyMcuHalDispatch.isLive("rp", "rp2040", "arm"))
        assertTrue(PyMcuHalDispatch.isLive("rp", "rp2350", "arm"))
        assertFalse("an AVR project has no business in rp/", PyMcuHalDispatch.isLive("rp", "atmega328p", "avr"))
    }

    /**
     * `hal/gpio.py` routes pic18 to `pymcu.hal.pic14.gpio` while other facades
     * keep them apart, so the plugin does not filter within the PIC family.
     */
    @Test
    fun `the PIC family is not filtered against itself`() {
        assertTrue(PyMcuHalDispatch.isLive("pic14", "pic18f4550", "pic18"))
        assertTrue(PyMcuHalDispatch.isLive("pic18", "pic18f4550", "pic18"))
        assertTrue(PyMcuHalDispatch.isLive("pic12", "pic16f84a", "pic14"))
    }

    @Test
    fun `a foreign architecture is not live`() {
        assertFalse(PyMcuHalDispatch.isLive("rp2040", "atmega328p", "avr"))
        assertFalse(PyMcuHalDispatch.isLive("pic14", "atmega328p", "avr"))
        assertFalse(PyMcuHalDispatch.isLive("avr", "rp2040", "arm"))
        assertFalse(PyMcuHalDispatch.isLive("riscv", "atmega328p", "avr"))
    }

    // ── the rating ───────────────────────────────────────────────────────────

    @Test
    fun `the live architecture is preferred and the others demoted`() {
        assertEquals(PyMcuHalDispatch.PREFERRED, PyMcuHalDispatch.rate("avr", "atmega328p", "avr"))
        assertEquals(PyMcuHalDispatch.FOREIGN, PyMcuHalDispatch.rate("riscv", "atmega328p", "avr"))
        assertTrue(PyMcuHalDispatch.PREFERRED > PyMcuHalDispatch.FOREIGN)
    }

    /** Unknown target: leave resolution exactly as it was. */
    @Test
    fun `without a chip nothing is demoted`() {
        assertEquals(PyMcuHalDispatch.NEUTRAL, PyMcuHalDispatch.rate("avr", null, null))
        assertEquals(PyMcuHalDispatch.NEUTRAL, PyMcuHalDispatch.rate("avr", "atmega328p", null))
        assertEquals(PyMcuHalDispatch.NEUTRAL, PyMcuHalDispatch.rate("avr", "", "avr"))
        assertEquals(PyMcuHalDispatch.NEUTRAL, PyMcuHalDispatch.rate(null, "atmega328p", "avr"))
    }

    // ── reading the architecture from the chip definition ────────────────────

    @Test
    fun `the architecture is read from the chip's own device_info line`() {
        assertEquals(
            "avr",
            PyMcuChipInfoService.parseArch(
                """
                from pymcu.chips import device_info
                RAM_SIZE = 2048
                device_info(chip="atmega328p", arch="avr", ram_size=RAM_SIZE)
                """.trimIndent()
            )
        )
        assertEquals(
            "arm",
            PyMcuChipInfoService.parseArch("""device_info(chip="rp2040", arch="arm", ram_size=RAM_SIZE)""")
        )
    }

    @Test
    fun `a definition with no architecture yields nothing rather than a guess`() {
        assertNull(PyMcuChipInfoService.parseArch("RAM_SIZE = 2048\n"))
        assertNull(PyMcuChipInfoService.parseArch(""))
    }
}
