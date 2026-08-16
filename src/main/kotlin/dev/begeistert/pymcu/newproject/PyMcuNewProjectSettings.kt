package dev.begeistert.pymcu.newproject

/** The choices made in the New Project wizard panel. */
data class PyMcuNewProjectSettings(
    /** Board alias, e.g. "arduino_uno". Null when targeting a bare chip. */
    val board: String? = "arduino_uno",
    /** Chip id, used when [board] is null. */
    val chip: String? = null,
    val frequency: Long = 16_000_000,
    val packageManager: String = "uv",
    /**
     * Compat stdlib to activate:
     *  ""              — bare PyMCU (pymcu.hal directly)
     *  "micropython"   — machine, utime, …
     *  "circuitpython" — board, digitalio, busio, …
     */
    val stdlib: String = "circuitpython",
)
