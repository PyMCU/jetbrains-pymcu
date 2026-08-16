package dev.begeistert.pymcu.newproject

/**
 * The files the New Project wizard writes.
 *
 * Pure functions with no platform dependency, so the output can be asserted in
 * a test rather than eyeballed in a running IDE — which is how the first version
 * shipped a `pyproject.toml` indented twelve spaces.
 *
 * WHY every template joins a list instead of using `trimIndent()`: Kotlin trims
 * the *interpolated* string, so a `$value` holding a newline followed by text at
 * column 0 drags the common indent to zero and nothing is stripped. The literal
 * lines then keep their source indentation while the interpolated ones sit flush
 * left. Building the lines is immune to that by construction.
 */
object PyMcuScaffold {

    /**
     * Lowest version that still admits the alpha releases, mirroring
     * `_PRERELEASE_FLOOR` in the driver's `new.py`.
     *
     * A bare `pymcu-stdlib` would make pip refuse every published build: pip only
     * considers pre-releases when the specifier itself names one.
     */
    const val PRERELEASE_FLOOR: String = "0.1.0a1"

    /** The chip's codegen backend, as a `pymcu-compiler` extra. */
    fun compilerExtra(chip: String?): String {
        val c = chip?.lowercase() ?: return ""
        return when {
            c.startsWith("at") -> "[avr]"
            c == "rp2040" || c == "rp2350" -> "[arm]"
            c.startsWith("pic") -> "[pic]"
            // No riscv extra exists: pymcu-backend-riscv is not on PyPI, and an
            // extra that cannot resolve fails harder than a missing one.
            else -> ""
        }
    }

    /** The requirement lines shared by pyproject.toml and requirements.txt. */
    fun dependencies(settings: PyMcuNewProjectSettings): List<String> = buildList {
        add("pymcu-stdlib>=$PRERELEASE_FLOOR")
        // The extra pulls the codegen backend and toolchain, so a fresh install
        // of the generated project can actually build. Without it the compiler
        // arrives with no backend for this chip.
        add("pymcu-compiler${compilerExtra(settings.effectiveChip)}>=$PRERELEASE_FLOOR")
        settings.stdlib.takeIf { it.isNotEmpty() }?.let { add("pymcu-$it>=$PRERELEASE_FLOOR") }
    }

    fun pyproject(name: String, settings: PyMcuNewProjectSettings): String = buildList {
        add("[project]")
        add("""name = "$name"""")
        add("""version = "0.1.0"""")
        add("""requires-python = ">=3.11"""")
        add("dependencies = [")
        dependencies(settings).forEach { add("""    "$it",""") }
        add("]")
        add("")
        add("[tool.pymcu]")
        settings.board
            ?.let { add("""board = "$it"""") }
            ?: add("""target = "${settings.chip ?: "atmega328p"}"""")
        add("frequency = ${settings.frequency}")
        add("""sources = "src"""")
        add("""entry = "main.py"""")
        settings.stdlib.takeIf { it.isNotEmpty() }?.let { add("""stdlib = ["$it"]""") }
    }.joinToString("\n") + "\n"

    /** Written for the pip workflow; also what marks the project as pip-managed. */
    fun requirements(settings: PyMcuNewProjectSettings): String =
        dependencies(settings).joinToString("\n") + "\n"

    fun mainPy(settings: PyMcuNewProjectSettings): String {
        val target = settings.board ?: settings.chip ?: "atmega328p"
        return when (settings.stdlib) {
            "micropython" -> microPythonBlink(target)
            "circuitpython" -> circuitPythonBlink(target)
            else -> nativeBlink(target, settings.effectiveChip)
        }
    }

    val GITIGNORE: String = listOf(
        "__pycache__/", "*.py[cod]", ".venv/", "venv/", "dist/", "build/",
    ).joinToString("\n") + "\n"

    // ── starters ─────────────────────────────────────────────────────────────
    //
    // The compat flavors scaffold as top-level scripts, matching what `pymcu new`
    // emits: that is how MicroPython and CircuitPython are written, and every
    // snippet a newcomer pastes looks like that. The native target keeps
    // `def main():`.

    private fun microPythonBlink(target: String): String = listOf(
        "# PyMCU — target: $target  ·  MicroPython compat",
        "from machine import Pin",
        "from time import sleep_ms",
        "",
        "led = Pin(13, Pin.OUT)",
        "",
        "while True:",
        "    led.value(1)",
        "    sleep_ms(500)",
        "    led.value(0)",
        "    sleep_ms(500)",
    ).joinToString("\n") + "\n"

    private fun circuitPythonBlink(target: String): String = listOf(
        "# PyMCU — target: $target  ·  CircuitPython compat",
        "import board",
        "import digitalio",
        "import time",
        "",
        "led = digitalio.DigitalInOut(board.LED)",
        "led.direction = digitalio.Direction.OUTPUT",
        "",
        "while True:",
        "    led.value = True",
        "    time.sleep(0.5)",
        "    led.value = False",
        "    time.sleep(0.5)",
    ).joinToString("\n") + "\n"

    /**
     * Register-level, per chip, mirroring `_chip_imports` in the driver.
     *
     * The previous version emitted `Pin("PB5", Pin.OUT)` for every target, which
     * is an AVR port name — `hal/rp2040/gpio.py` declares `pin: uint8`, a GP
     * index, so a native RP2040 project shipped a main.py that could not compile.
     * The driver does not use the HAL facade here at all; it writes the chip's
     * own registers, which is unambiguous per architecture.
     */
    private fun nativeBlink(target: String, chip: String?): String {
        val c = chip?.lowercase()
        val header = "# PyMCU — target: $target  ·  native registers"
        val (imports, body) = when {
            c == null -> return nativeUnknownChip(target)

            c.startsWith("at") -> listOf(
                "from pymcu.chips.$c import DDRB, PORTB, DDB5, PORTB5",
                "from pymcu.time import delay_ms",
            ) to listOf(
                "DDRB[DDB5] = 1",
                "while True:",
                "    PORTB[PORTB5] = 1",
                "    delay_ms(500)",
                "    PORTB[PORTB5] = 0",
                "    delay_ms(500)",
            )

            c.startsWith("pic") -> listOf(
                "from pymcu.chips.$c import TRISB, PORTB, RB0",
            ) to listOf(
                "TRISB[RB0] = 0",
                "PORTB[RB0] = 1",
            )

            else -> listOf(
                "from pymcu.chips.$c import PORTB",
            ) to listOf(
                "PORTB[0] = 1",
            )
        }

        // The native targets keep `def main():`, which is the shape their
        // examples and the driver's own fixtures use.
        return (listOf(header) + imports + listOf("", "", "def main():") +
            body.map { "    $it" }).joinToString("\n") + "\n"
    }

    /**
     * No chip to import registers from — the wizard cannot resolve a board alias
     * without the catalog, which is the same outage that put us in the fallback.
     * Say so in the file rather than emit something that will not compile.
     */
    private fun nativeUnknownChip(target: String): String = listOf(
        "# PyMCU — target: $target",
        "#",
        "# The chip could not be determined without the pymcu CLI, and a native",
        "# program addresses its chip's registers by name. Once the CLI is",
        "# installed, `pymcu new` scaffolds this properly; or import the registers",
        "# from pymcu.chips.<your chip> and drive them here.",
        "",
        "",
        "def main():",
        "    pass",
    ).joinToString("\n") + "\n"

}
