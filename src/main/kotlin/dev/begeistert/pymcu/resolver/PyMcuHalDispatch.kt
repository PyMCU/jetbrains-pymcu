package dev.begeistert.pymcu.resolver

/**
 * Which `pymcu/hal/<dir>/` directories can serve a given chip.
 *
 * The HAL facades dispatch at compile time:
 *
 * ```python
 * if __CHIP__.arch == "avr":
 *     from pymcu.hal.avr.gpio import Pin
 * elif __CHIP__.name == "rp2040":
 *     from pymcu.hal.rp2040.gpio import Pin
 * ...
 * ```
 *
 * The compiler evaluates that against the target and keeps one branch. An IDE
 * cannot, so `Pin` resolves to all six, and Go To Declaration offers a list of
 * architectures the project will never compile for. Every HAL facade dispatches
 * this way, so it is the whole surface and not one class.
 *
 * This decides, from the target, which directories are live. It is deliberately
 * conservative: a directory is demoted only when it is positively known to
 * belong to another family. Anything unrecognised rates neutral, so the worst
 * case is the behaviour we already had.
 */
object PyMcuHalDispatch {

    /** Path segment that marks the start of an architecture directory. */
    private const val HAL = "/pymcu/hal/"

    /**
     * The `pymcu/hal/<dir>` directory a file belongs to, or null when the file
     * is not under an architecture directory (the facades themselves live
     * directly in `hal/`, and shared helpers start with an underscore).
     */
    fun architectureDirectoryOf(path: String): String? {
        val normalised = path.replace('\\', '/')
        val index = normalised.indexOf(HAL)
        if (index < 0) return null
        val rest = normalised.substring(index + HAL.length)
        val directory = rest.substringBefore('/', "")
        // No slash means a facade file such as hal/gpio.py, not an arch directory.
        if (directory.isEmpty() || !rest.contains('/')) return null
        return directory.takeUnless { it.startsWith("_") }
    }

    /**
     * True when [directory] can serve a chip with this [chip] id and [arch].
     *
     * Three ways to be live, each mirroring how the facades actually dispatch:
     *  - the directory is the architecture (`avr`, `riscv`) or the chip itself
     *    (`rp2040`), which is what the `if` conditions compare against;
     *  - the directory is a prefix of the chip id, which is how the shared `rp`
     *    directory serves both rp2040 and rp2350;
     *  - both are PIC. The families share code unevenly — `hal/gpio.py` routes
     *    `pic18` to `pymcu.hal.pic14.gpio` while other facades keep them apart —
     *    so filtering within PIC would need per-facade knowledge that would rot.
     *    Filtering PIC against AVR, RISC-V and RP still removes most of the noise.
     */
    fun isLive(directory: String, chip: String, arch: String): Boolean {
        val dir = directory.lowercase()
        val chipId = chip.lowercase()
        val architecture = arch.lowercase()
        return dir == architecture ||
            dir == chipId ||
            chipId.startsWith(dir) ||
            (architecture.startsWith("pic") && dir.startsWith("pic"))
    }

    /**
     * How much to prefer a resolve target in [directory].
     *
     * Positive for the architecture this project builds for, negative for one it
     * demonstrably does not, zero when there is nothing to say.
     */
    fun rate(directory: String?, chip: String?, arch: String?): Int {
        if (directory == null || chip.isNullOrBlank() || arch.isNullOrBlank()) return NEUTRAL
        return if (isLive(directory, chip, arch)) PREFERRED else FOREIGN
    }

    const val PREFERRED: Int = 100
    const val NEUTRAL: Int = 0
    const val FOREIGN: Int = -100
}
