package dev.begeistert.pymcu.cli

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import dev.begeistert.pymcu.cli.JsonLite.arr
import dev.begeistert.pymcu.cli.JsonLite.str

data class BoardInfo(
    val name: String,
    val chip: String,
    val group: String?,
    val toolchain: String?,
    val programmer: String?,
)

data class BoardCatalog(
    val boards: List<BoardInfo>,
    val groups: Map<String, List<String>>,
    val chips: List<String>,
) {
    fun chipOf(board: String): String? = boards.firstOrNull { it.name == board }?.chip

    /** Board names by group, in catalog order, skipping groups with no known boards. */
    fun groupedBoards(): List<Pair<String, List<BoardInfo>>> {
        val byName = boards.associateBy { it.name }
        return groups.map { (group, names) -> group to names.mapNotNull(byName::get) }
            .filter { it.second.isNotEmpty() }
    }
}

/**
 * The boards and chips this PyMCU installation supports, from `pymcu boards --json`.
 *
 * Asking the CLI is the whole point: the previous hardcoded list knew about four
 * Arduino boards, while the driver has since grown ATtiny, RP2040/RP2350, PIC and
 * RISC-V targets. Anything hardcoded here drifts the moment a backend ships.
 *
 * The fallback below is only for an installation too old to have `pymcu boards`,
 * or none at all — it mirrors `core/boards.BOARD_CHIPS` at the time of writing.
 */
@Service(Service.Level.PROJECT)
class PyMcuBoardCatalogService(private val project: Project) {

    @Volatile
    private var cached: BoardCatalog? = null

    /** Blocking — call from a background thread. */
    fun get(refresh: Boolean = false): BoardCatalog {
        if (!refresh) cached?.let { return it }
        val fetched = fetch() ?: FALLBACK
        cached = fetched
        return fetched
    }

    /** The catalog if it has already been fetched, without touching the CLI. */
    fun cachedOrFallback(): BoardCatalog = cached ?: FALLBACK

    fun invalidate() {
        cached = null
    }

    private fun fetch(): BoardCatalog? {
        val result = PyMcuCli.run(project, "boards", "--json", timeoutMs = 30_000)
        if (!result.ok) return null
        return parse(result.stdout)
    }

    companion object {
        fun getInstance(project: Project): PyMcuBoardCatalogService =
            project.getService(PyMcuBoardCatalogService::class.java)

        /** Exposed for tests. */
        fun parse(json: String): BoardCatalog? {
            val root = JsonLite.parseObject(json) ?: return null
            val boards = root.arr("boards").mapNotNull { entry ->
                @Suppress("UNCHECKED_CAST")
                val map = entry as? Map<String, Any?> ?: return@mapNotNull null
                val name = map["name"].str() ?: return@mapNotNull null
                val chip = map["chip"].str() ?: return@mapNotNull null
                BoardInfo(name, chip, map["group"].str(), map["toolchain"].str(), map["programmer"].str())
            }
            if (boards.isEmpty()) return null

            @Suppress("UNCHECKED_CAST")
            val groupsRaw = root["groups"] as? Map<String, Any?> ?: emptyMap()
            val groups = groupsRaw.mapValues { (_, v) ->
                (v as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
            }
            val chips = root.arr("chips").mapNotNull { it as? String }
            return BoardCatalog(boards, groups, chips)
        }

        val FALLBACK = BoardCatalog(
            boards = listOf(
                BoardInfo("arduino_uno", "atmega328p", "Arduino", "avr", "avrdude"),
                BoardInfo("arduino_nano", "atmega328p", "Arduino", "avr", "avrdude"),
                BoardInfo("arduino_mega", "atmega2560", "Arduino", "avr", "avrdude"),
                BoardInfo("arduino_micro", "atmega32u4", "Arduino", "avr", "avrdude"),
                BoardInfo("raspberry_pi_pico", "rp2040", "Raspberry Pi", "rp2040", "rp2040"),
                BoardInfo("raspberry_pi_pico2", "rp2350", "Raspberry Pi", "rp2040", "rp2040"),
                BoardInfo("adafruit_trinket", "attiny85", "Adafruit", "avr", "avrdude"),
                BoardInfo("digispark", "attiny85", "Digispark", "avr", "avrdude"),
                BoardInfo("attiny85", "attiny85", "ATtiny 8-pin (bare chip)", "avr", "avrdude"),
                BoardInfo("attiny45", "attiny45", "ATtiny 8-pin (bare chip)", "avr", "avrdude"),
                BoardInfo("attiny25", "attiny25", "ATtiny 8-pin (bare chip)", "avr", "avrdude"),
                BoardInfo("attiny13", "attiny13", "ATtiny 8-pin (bare chip)", "avr", "avrdude"),
                BoardInfo("attiny84", "attiny84", "ATtiny 14-pin (bare chip)", "avr", "avrdude"),
                BoardInfo("attiny2313", "attiny2313", "ATtiny 20-pin (bare chip)", "avr", "avrdude"),
            ),
            groups = mapOf(
                "Arduino" to listOf("arduino_uno", "arduino_nano", "arduino_mega", "arduino_micro"),
                "Raspberry Pi" to listOf("raspberry_pi_pico", "raspberry_pi_pico2"),
                "Adafruit" to listOf("adafruit_trinket"),
                "Digispark" to listOf("digispark"),
                "ATtiny 8-pin (bare chip)" to listOf("attiny85", "attiny45", "attiny25", "attiny13"),
                "ATtiny 14-pin (bare chip)" to listOf("attiny84"),
                "ATtiny 20-pin (bare chip)" to listOf("attiny2313"),
            ),
            chips = emptyList(),
        )
    }
}
