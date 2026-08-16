package dev.begeistert.pymcu

import dev.begeistert.pymcu.config.PyMcuConfigReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PyMcuConfigReaderTest {

    private val modernProject = """
        [build-system]
        requires = ["hatchling"]

        [project]
        name = "blinky"
        version = "0.1.0"
        dependencies = [
            "pymcu-stdlib",
            "pymcu-circuitpython",
        ]

        [tool.pymcu]
        board = "arduino_uno"   # the chip is derived from the board
        frequency = 16_000_000
        sources = "src"
        entry = "main.py"
        stdlib = ["circuitpython"]
        stdout = "uart0"
        stdout_baud = 9600

        [tool.pymcu.toolchain]
        name = "avr"

        [tool.pymcu.flash]
        programmer = "avrdude"
        port = "/dev/cu.usbmodem1101"
        baud = 57600

        [tool.other]
        irrelevant = true
    """.trimIndent()

    // ── the schema the driver writes today ───────────────────────────────────

    @Test
    fun `reads a board-based project`() {
        val config = PyMcuConfigReader.parseContent(modernProject)
        assertNotNull(config)
        assertEquals("arduino_uno", config!!.board)
        assertNull(config.target)
        assertEquals("arduino_uno", config.targetLabel)
        assertEquals("src", config.sources)
        assertEquals("main.py", config.entry)
    }

    /** `frequency = 16_000_000` is valid TOML; the previous regex reader lost it. */
    @Test
    fun `reads an integer with underscore separators`() {
        val config = PyMcuConfigReader.parseContent(modernProject)
        assertEquals(16_000_000L, config!!.frequency)
    }

    /** A trailing comment used to make the whole key fail to parse. */
    @Test
    fun `a trailing comment does not swallow the value`() {
        val config = PyMcuConfigReader.parseContent(modernProject)
        assertEquals("arduino_uno", config!!.board)
    }

    @Test
    fun `reads the target key, which is the canonical spelling`() {
        val toml = """
            [tool.pymcu]
            target = "rp2040"
            frequency = 125000000
        """.trimIndent()
        val config = PyMcuConfigReader.parseContent(toml)
        assertEquals("rp2040", config!!.target)
        assertEquals("rp2040", config.explicitChip)
        assertFalse(config.usesDeprecatedChipKey)
        assertEquals("ARM", config.architecture())
    }

    @Test
    fun `reads the deprecated chip key and flags it`() {
        val toml = """
            [tool.pymcu]
            chip = "atmega328p"
        """.trimIndent()
        val config = PyMcuConfigReader.parseContent(toml)
        assertEquals("atmega328p", config!!.chip)
        assertEquals("atmega328p", config.explicitChip)
        assertTrue(config.usesDeprecatedChipKey)
    }

    @Test
    fun `flags board and target set together, which the driver rejects`() {
        val toml = """
            [tool.pymcu]
            board = "arduino_uno"
            target = "atmega328p"
        """.trimIndent()
        assertTrue(PyMcuConfigReader.parseContent(toml)!!.hasConflictingTarget)
    }

    @Test
    fun `flags a section with no target at all`() {
        val config = PyMcuConfigReader.parseContent("[tool.pymcu]\nfrequency = 16000000")
        assertTrue(config!!.hasNoTarget)
    }

    // ── nested tables ────────────────────────────────────────────────────────

    @Test
    fun `reads the flash table`() {
        val flash = PyMcuConfigReader.parseContent(modernProject)!!.flash
        assertEquals("avrdude", flash.programmer)
        assertEquals("/dev/cu.usbmodem1101", flash.port)
        assertEquals(57600, flash.baud)
    }

    @Test
    fun `falls back to the pre-0_15 programmer table the driver still honours`() {
        val toml = """
            [tool.pymcu]
            board = "arduino_uno"

            [tool.pymcu.programmer]
            name = "avrdude"
        """.trimIndent()
        assertEquals("avrdude", PyMcuConfigReader.parseContent(toml)!!.flash.programmer)
    }

    @Test
    fun `reads toolchain, stdout and stdlib`() {
        val config = PyMcuConfigReader.parseContent(modernProject)!!
        assertEquals("avr", config.toolchain)
        assertEquals("uart0", config.stdout)
        assertEquals(9600, config.stdoutBaud)
        assertEquals(listOf("circuitpython"), config.stdlib)
        assertEquals("circuitpython", config.flavor)
    }

    @Test
    fun `reads PIC configuration words`() {
        val toml = """
            [tool.pymcu]
            target = "pic16f84a"

            [tool.pymcu.config]
            FOSC = "XT"
            WDTE = "OFF"
        """.trimIndent()
        val config = PyMcuConfigReader.parseContent(toml)!!
        assertEquals(mapOf("FOSC" to "XT", "WDTE" to "OFF"), config.configWords)
        assertEquals("PIC", config.architecture())
    }

    @Test
    fun `detects an ffi section`() {
        val toml = """
            [tool.pymcu]
            board = "arduino_uno"

            [tool.pymcu.ffi]
            sources = ["native/driver.c"]
        """.trimIndent()
        assertTrue(PyMcuConfigReader.parseContent(toml)!!.hasFfi)
        assertFalse(PyMcuConfigReader.parseContent(modernProject)!!.hasFfi)
    }

    @Test
    fun `reads a multi-line stdlib array`() {
        val toml = """
            [tool.pymcu]
            board = "arduino_uno"
            stdlib = [
                "circuitpython",
            ]
        """.trimIndent()
        assertEquals(listOf("circuitpython"), PyMcuConfigReader.parseContent(toml)!!.stdlib)
    }

    // ── absence ──────────────────────────────────────────────────────────────

    @Test
    fun `returns null without a tool_pymcu section`() {
        assertNull(PyMcuConfigReader.parseContent("[project]\nname = \"other\""))
        assertNull(PyMcuConfigReader.parseContent(""))
        assertNull(PyMcuConfigReader.parseContent("[tool.poetry]\nname = \"x\""))
    }

    @Test
    fun `does not read keys from the following section`() {
        val toml = """
            [tool.pymcu]
            board = "arduino_uno"

            [tool.ruff]
            entry = "not-mine.py"
            sources = "not-mine"
        """.trimIndent()
        val config = PyMcuConfigReader.parseContent(toml)!!
        assertEquals("main.py", config.entry)
        assertEquals("src", config.sources)
    }

    // ── architecture inference, matching core/boards.default_toolchain ───────

    @Test
    fun `infers the architecture from the chip family`() {
        fun archOf(chip: String) = PyMcuConfigReader.parseContent(
            "[tool.pymcu]\ntarget = \"$chip\""
        )!!.architecture()

        assertEquals("AVR", archOf("atmega328p"))
        assertEquals("AVR", archOf("attiny85"))
        assertEquals("ARM", archOf("rp2350"))
        assertEquals("RISC-V", archOf("ch32v203"))
        assertEquals("PIC", archOf("pic16f84a"))
        assertNull(archOf("something-else"))
    }
}
