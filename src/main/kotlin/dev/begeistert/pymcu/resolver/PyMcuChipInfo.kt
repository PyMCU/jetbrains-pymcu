package dev.begeistert.pymcu.resolver

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import dev.begeistert.pymcu.cli.PyMcuBoardCatalogService
import dev.begeistert.pymcu.project.PyMcuProjectService
import dev.begeistert.pymcu.venv.PyMcuVenv
import java.util.concurrent.ConcurrentHashMap

/**
 * Everything the compile-time dispatch is allowed to ask about a target.
 *
 * These are exactly the fields `pymcu/chips/__init__.py` exposes to HAL code —
 * `__CHIP__.name`, `__CHIP__.arch`, `__CHIP__.board` and `__FREQ__` — so a
 * facade cannot branch on anything this does not carry.
 *
 * [board] is empty when the project names a chip instead of a board, which is
 * the same empty string the compiler passes and which `hal/wifi.py` reads as
 * "the program never said".
 */
data class ChipIdentity(
    val chip: String,
    val arch: String,
    val board: String = "",
    val frequency: Long? = null,
)

/**
 * Reads a chip's architecture from the installed stdlib rather than inferring it
 * from the chip's name.
 *
 * Every chip definition carries one authoritative line:
 *
 * ```python
 * device_info(chip="atmega328p", arch="avr", ram_size=RAM_SIZE)
 * ```
 *
 * Guessing instead would mean encoding a table of prefixes here, and the real
 * values are finer-grained than a guess would produce — `pic12`, `pic14`,
 * `pic14e` and `pic18` are separate architectures that all start "pic", and
 * both RP chips report `arm`. `pymcu/chips/__init__.py` says outright that the
 * `.arch` and `.name` fields exist so IDEs can resolve HAL code branching on
 * them, so reading them is the intended route.
 */
@Service(Service.Level.PROJECT)
class PyMcuChipInfoService(private val project: Project) {

    /** Keyed by chip id; a chip definition never changes under a running IDE. */
    private val archByChip = ConcurrentHashMap<String, String>()

    /**
     * Set by tests in place of the on-disk lookup below.
     *
     * WHY a seam here rather than a fake filesystem: [readArch] reads the chip
     * definition out of a real venv through `.pth` files, which a light IDE
     * fixture has no way to produce. Everything downstream of the identity —
     * which HAL branch is live, where Go To Declaration lands — is what the
     * tests are about, and it is all reachable once the identity is given.
     */
    @Volatile
    private var overriddenIdentity: ChipIdentity? = null

    /** @see overriddenIdentity */
    @org.jetbrains.annotations.TestOnly
    fun overrideIdentity(identity: ChipIdentity?) {
        overriddenIdentity = identity
    }

    /**
     * The chip this project targets and its architecture, or null when either
     * cannot be established. Cheap after the first call.
     */
    fun identity(): ChipIdentity? {
        overriddenIdentity?.let { return it }
        val config = PyMcuProjectService.config(project) ?: return null
        val chip = config.explicitChip
            ?: config.board?.let {
                PyMcuBoardCatalogService.getInstance(project).cachedOrFallback().chipOf(it)
            }
            ?: return null

        // `board` stays empty for a project that names a chip: that is the value
        // the compiler substitutes, and `hal/wifi.py` branches on the difference.
        val board = config.board.orEmpty()
        archByChip[chip]?.let { return ChipIdentity(chip, it, board, config.frequency) }

        val arch = readArch(chip) ?: return null
        archByChip[chip] = arch
        return ChipIdentity(chip, arch, board, config.frequency)
    }

    private fun readArch(chip: String): String? {
        val basePath = project.basePath ?: return null
        val sitePackages = PyMcuVenv.sitePackages(basePath) ?: return null
        // All portions of the `pymcu` namespace package, not just the one under
        // site-packages: with pymcu-stdlib installed editable that one holds only
        // the backend's subpackages, and `chips/` is in the user's checkout.
        val definition = PyMcuVenv.packageDirs(sitePackages, "pymcu")
            .map { it.resolve("chips/$chip.py").toFile() }
            .firstOrNull { it.isFile }
            ?: return null
        return try {
            ARCH.find(definition.readText())?.groupValues?.get(1)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private val ARCH = Regex("""arch\s*=\s*["']([A-Za-z0-9_]+)["']""")

        fun getInstance(project: Project): PyMcuChipInfoService =
            project.getService(PyMcuChipInfoService::class.java)

        /** Exposed for tests. */
        fun parseArch(source: String): String? = ARCH.find(source)?.groupValues?.get(1)
    }
}
