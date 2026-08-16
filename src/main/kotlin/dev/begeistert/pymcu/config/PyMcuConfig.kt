package dev.begeistert.pymcu.config

/** `[tool.pymcu.flash]` — what `pymcu flash` reads. */
data class PyMcuFlashConfig(
    val programmer: String? = null,
    val port: String? = null,
    val baud: Int? = null,
    val fuseLow: String? = null,
    val fuseHigh: String? = null,
    val fuseExt: String? = null,
)

/**
 * The parsed `[tool.pymcu]` section.
 *
 * Key precedence mirrors the driver (`commands/build.py`): `board` implies a
 * chip, `target` names one directly, and `chip` is the deprecated spelling of
 * `target` that the driver still honours with a warning.
 */
data class PyMcuConfig(
    /** Canonical chip key. */
    val target: String? = null,
    /** Board alias, e.g. "arduino_uno". Mutually exclusive with [target]. */
    val board: String? = null,
    /** Deprecated spelling of [target]. */
    val chip: String? = null,
    val frequency: Long? = null,
    val sources: String = "src",
    val entry: String = "main.py",
    /** Compat flavors: `stdlib = ["micropython"]` / `["circuitpython"]`. */
    val stdlib: List<String> = emptyList(),
    val flash: PyMcuFlashConfig = PyMcuFlashConfig(),
    /** `[tool.pymcu.toolchain] name = "..."`. */
    val toolchain: String? = null,
    /** `[tool.pymcu.config]` — PIC configuration words (FOSC, WDTE…). */
    val configWords: Map<String, String> = emptyMap(),
    /** `stdout` / `stdout_baud` — the device `print()` is wired to. */
    val stdout: String? = null,
    val stdoutBaud: Int? = null,
    /** True when a `[tool.pymcu.ffi]` section is present. */
    val hasFfi: Boolean = false,
) {
    /** The chip named directly, ignoring any board alias. Prefers the canonical key. */
    val explicitChip: String? get() = target ?: chip

    /** Whatever identifies the target, board alias included. */
    val targetLabel: String? get() = board ?: target ?: chip

    /** The single compat flavor in use, or null for bare PyMCU. */
    val flavor: String? get() = stdlib.firstOrNull { it == "micropython" || it == "circuitpython" }

    /** True when the deprecated `chip` key is the only target given. */
    val usesDeprecatedChipKey: Boolean get() = chip != null

    /** True when `board` and `target`/`chip` are both set — the driver rejects this. */
    val hasConflictingTarget: Boolean get() = board != null && explicitChip != null

    val hasNoTarget: Boolean get() = targetLabel == null

    /**
     * Architecture inferred from the chip id, matching `core/boards.default_toolchain`.
     * Returns null when no chip is known (a board alias alone needs the catalog).
     */
    fun architecture(resolvedChip: String? = null): String? {
        val c = (resolvedChip ?: explicitChip)?.lowercase() ?: return null
        return when {
            c.startsWith("at") -> "AVR"
            c.startsWith("rp2") -> "ARM"
            c.startsWith("ch32v") -> "RISC-V"
            c.startsWith("pic") || Regex("""^(10|12|16|18)f""").containsMatchIn(c) -> "PIC"
            else -> null
        }
    }
}
