package dev.begeistert.pymcu.newproject

import dev.begeistert.pymcu.config.PyMcuConfig

/**
 * The CPU clock the New Project wizard writes into `[tool.pymcu].frequency`.
 *
 * WHY these numbers are duplicated from the driver instead of asked for: the
 * wizard always passes `--freq`, so `pymcu new` never gets to apply its own
 * default and whatever this object decides is what lands on disk. The previous
 * panel offered 16 MHz for everything that was not an ATtiny, which is wrong
 * for a bare ATmega (8 MHz), for PIC (4 MHz) and for RISC-V (48/144 MHz), and
 * it ignored the 16.5 MHz crystal the Digispark and the Trinket ship with.
 *
 * Mirrors `core/boards.py`: `BOARD_FREQUENCIES` first, then
 * `default_frequency(chip)`.
 *
 * Pure, so the mapping can be asserted in a test rather than eyeballed in a
 * running IDE — the same reason [PyMcuScaffold] is a separate object.
 */
object PyMcuClock {

    /**
     * The lowest clock the wizard accepts.
     *
     * A bare number is read as Hz, which means "16" is sixteen hertz — almost
     * certainly a user who meant 16 MHz and left the unit off. Nothing PyMCU
     * targets runs below a kilohertz, so rejecting the value and saying how to
     * write it is better than silently scaffolding a project that cannot work.
     */
    const val MINIMUM_HZ: Long = 1_000L

    /** `BOARD_FREQUENCIES` — the boards that do not run at their chip's default. */
    private val BOARD_CLOCKS = mapOf(
        "arduino_uno" to 16_000_000L,
        "arduino_nano" to 16_000_000L,
        "arduino_mega" to 16_000_000L,
        "arduino_micro" to 16_000_000L,
        // Both ship the 16.5 MHz crystal V-USB needs.
        "digispark" to 16_500_000L,
        "adafruit_trinket" to 16_500_000L,
    )

    // The clocks worth one click per architecture: the driver's own defaults,
    // plus 1 MHz for a factory AVR still running its internal RC through CKDIV8.
    // Anything else is typed — the field is editable precisely so an unusual
    // crystal does not need a code change here.
    private val AVR_CLOCKS = listOf(16_000_000L, 16_500_000L, 8_000_000L, 1_000_000L)
    private val ARM_CLOCKS = listOf(150_000_000L, 125_000_000L)
    private val PIC_CLOCKS = listOf(4_000_000L)
    private val RISCV_CLOCKS = listOf(144_000_000L, 48_000_000L)

    /** `default_frequency(chip)`. */
    fun forChip(chip: String?): Long {
        val c = chip?.lowercase() ?: return 8_000_000L
        return when {
            c == "rp2040" -> 125_000_000L
            c == "rp2350" -> 150_000_000L
            c.startsWith("pic") -> 4_000_000L
            c.startsWith("ch32v2") || c.startsWith("ch32v3") -> 144_000_000L
            c.startsWith("ch32v") -> 48_000_000L
            else -> 8_000_000L
        }
    }

    /** `board_frequency(board)`, falling back to the chip's default. */
    fun forTarget(board: String?, chip: String?): Long =
        board?.let(BOARD_CLOCKS::get) ?: forChip(chip)

    /** The clocks offered for this target, always including the one it defaults to. */
    fun suggestions(board: String?, chip: String?): List<Long> {
        val offered = when (PyMcuConfig(target = chip).architecture()) {
            "AVR" -> AVR_CLOCKS
            "ARM" -> ARM_CLOCKS
            "PIC" -> PIC_CLOCKS
            "RISC-V" -> RISCV_CLOCKS
            else -> AVR_CLOCKS + ARM_CLOCKS + PIC_CLOCKS + RISCV_CLOCKS
        }
        return (offered + forTarget(board, chip)).distinct().sortedDescending()
    }

    /** Hz as the shortest exact reading of it: 16500000 is "16.5 MHz", never "16 MHz". */
    fun format(hz: Long): String = when {
        hz >= 1_000_000L -> "${scaled(hz, 1_000_000L)} MHz"
        hz >= 1_000L -> "${scaled(hz, 1_000L)} kHz"
        else -> "$hz Hz"
    }

    /**
     * Hz from whatever the user typed: "16 MHz", "16mhz", "16_000_000" and
     * "16 000 000" all mean the same thing. A value with no unit is Hz, as it
     * is everywhere else in PyMCU; see [MINIMUM_HZ] for why that is safe.
     */
    fun parse(text: String): Long? {
        val cleaned = text.trim().replace("_", "").replace(",", "").replace(" ", "")
        val match = PATTERN.matchEntire(cleaned) ?: return null
        val (number, unit) = match.destructured
        val multiplier = when (unit.lowercase()) {
            "khz" -> 1_000L
            "mhz" -> 1_000_000L
            else -> 1L
        }
        val dot = number.indexOf('.')
        if (dot < 0) return number.toLongOrNull()?.let { it * multiplier }

        // Scale by hand rather than through Double: 16.5 MHz has to come out as
        // exactly 16500000, and a rounded float would be off by a hertz or two.
        val digits = number.substring(dot + 1)
        val whole = number.substring(0, dot).toLongOrNull() ?: return null
        val fraction = digits.toLongOrNull() ?: return null
        var scale = 1L
        repeat(digits.length) { scale *= 10L }
        if (multiplier % scale != 0L) return null
        return whole * multiplier + fraction * (multiplier / scale)
    }

    private val PATTERN = Regex("""([0-9]+(?:\.[0-9]+)?)(hz|khz|mhz)?""", RegexOption.IGNORE_CASE)

    /** [hz] over [unit], keeping every digit that matters and no trailing zeros. */
    private fun scaled(hz: Long, unit: Long): String {
        val rest = hz % unit
        if (rest == 0L) return (hz / unit).toString()
        val fraction = rest.toString()
            .padStart(unit.toString().length - 1, '0')
            .trimEnd('0')
        return "${hz / unit}.$fraction"
    }
}
