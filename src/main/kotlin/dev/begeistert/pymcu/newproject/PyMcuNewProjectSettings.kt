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
    /**
     * The chip [board] resolves to, from the catalog.
     *
     * Carried separately because the scaffolding needs the chip family to pick
     * the compiler's backend extra, and only the wizard panel has the catalog
     * on hand to resolve a board alias.
     */
    val resolvedChip: String? = null,
) {
    /** The chip this project targets, however it was specified. */
    val effectiveChip: String? get() = chip ?: resolvedChip
}
