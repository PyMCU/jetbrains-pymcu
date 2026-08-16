package dev.begeistert.pymcu.newproject

import dev.begeistert.pymcu.cli.BoardCatalog
import dev.begeistert.pymcu.config.PyMcuConfig

/**
 * What the New Project wizard offers as a target, and what each choice implies.
 *
 * Split out of the panel so the list and the descriptions can be asserted in a
 * test, the way [PyMcuScaffold] and [PyMcuDriverScaffold] are.
 *
 * The toolchain and programmer mappings mirror `default_toolchain` and
 * `default_programmer` in `core/boards.py`. They are only consulted for a bare
 * chip: for a board the catalog carries the driver's own answer, and that is
 * authoritative for the installation actually doing the scaffolding.
 */
object PyMcuTargets {

    /** Heading for boards the driver knows but has not put in a group. */
    const val UNGROUPED = "Other"

    /** Heading for the entry that targets a chip with no board. */
    const val ADVANCED = "Advanced"

    /** One entry of the board picker. */
    data class Choice(
        /** Board alias, e.g. "arduino_uno". Null for the bare-chip entry. */
        val board: String?,
        /** The chip the board resolves to. Null for the bare-chip entry. */
        val chip: String?,
        /** Heading drawn above this entry; null continues the previous group. */
        val heading: String?,
    ) {
        /**
         * WHY this is overridden: the popup's speed search matches against
         * `toString()`, so the generated one would have people typing "board="
         * to find a board. This way "uno" and "328" both narrow the list.
         */
        override fun toString(): String = listOfNotNull(board ?: BARE_CHIP_LABEL, chip).joinToString("  ")
    }

    /** The picker's entries, in catalog order, with the bare-chip entry last. */
    fun choices(catalog: BoardCatalog): List<Choice> {
        val byName = catalog.boards.associateBy { it.name }
        val placed = mutableSetOf<String>()
        val entries = mutableListOf<Choice>()

        for ((group, names) in catalog.groups) {
            var heading: String? = group
            for (name in names) {
                val board = byName[name] ?: continue
                entries += Choice(board.name, board.chip, heading)
                heading = null
                placed += name
            }
        }

        // A board present in the driver's BOARD_CHIPS but missing from
        // BOARD_GROUPS would otherwise never appear here, which is the exact
        // failure the catalog was introduced to stop; see PyMcuBoardCatalogService.
        var ungrouped: String? = UNGROUPED
        for (board in catalog.boards) {
            if (!placed.add(board.name)) continue
            entries += Choice(board.name, board.chip, ungrouped)
            ungrouped = null
        }

        entries += Choice(null, null, ADVANCED)
        return entries
    }

    /** What the picker shows for an entry. */
    fun label(choice: Choice): String = choice.board ?: BARE_CHIP_LABEL

    /** The dimmed half of an entry: the chip it lands on. */
    fun secondaryLabel(choice: Choice): String = choice.chip ?: "pick the chip below"

    /**
     * Chips offered for a bare-chip target. The catalog's own list where there
     * is one — it is the only place PIC and RISC-V surface, since no board in
     * the catalog names them.
     */
    fun chips(catalog: BoardCatalog): List<String> =
        (catalog.chips.takeIf { it.isNotEmpty() } ?: catalog.boards.map { it.chip })
            .distinct()
            .sorted()

    /** `default_toolchain(chip)`. */
    fun toolchainFor(chip: String): String {
        val c = chip.lowercase()
        return when {
            c.startsWith("at") -> "avr"
            c == "rp2040" || c == "rp2350" -> "rp2040"
            c.startsWith("pic") -> "gputils"
            c.startsWith("ch32v") -> "riscv"
            else -> "avr"
        }
    }

    /** `default_programmer(chip)`. */
    fun programmerFor(chip: String): String {
        val c = chip.lowercase()
        return when {
            c.startsWith("at") -> "avrdude"
            c == "rp2040" || c == "rp2350" -> "rp2040"
            c.startsWith("ch32v") -> "wch-link"
            else -> "pk2cmd"
        }
    }

    /**
     * The line under the chip field: what this target is actually built and
     * flashed with, which is the thing a board name alone does not say.
     *
     * [toolchain] and [programmer] come from the catalog when a board is
     * selected; they are derived from the chip otherwise.
     */
    fun describe(chip: String?, toolchain: String? = null, programmer: String? = null): String {
        val c = chip?.trim().orEmpty()
        if (c.isEmpty()) return "Type a chip id: atmega328p, attiny85, rp2040, pic16f877a, ch32v003."
        val architecture = PyMcuConfig(target = c).architecture() ?: "Unrecognised chip"
        val builtWith = toolchain ?: toolchainFor(c)
        val flashedWith = programmer ?: programmerFor(c)
        return "$architecture · $builtWith toolchain · flashed with $flashedWith"
    }

    private const val BARE_CHIP_LABEL = "Bare chip"
}
