package dev.begeistert.pymcu.resolver

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import dev.begeistert.pymcu.cli.PyMcuBoardCatalogService
import dev.begeistert.pymcu.project.PyMcuProjectService
import dev.begeistert.pymcu.venv.PyMcuVenv
import java.util.concurrent.ConcurrentHashMap

/** A chip id and the architecture its own definition declares. */
data class ChipIdentity(val chip: String, val arch: String)

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
     * The chip this project targets and its architecture, or null when either
     * cannot be established. Cheap after the first call.
     */
    fun identity(): ChipIdentity? {
        val config = PyMcuProjectService.config(project) ?: return null
        val chip = config.explicitChip
            ?: config.board?.let {
                PyMcuBoardCatalogService.getInstance(project).cachedOrFallback().chipOf(it)
            }
            ?: return null

        archByChip[chip]?.let { return ChipIdentity(chip, it) }

        val arch = readArch(chip) ?: return null
        archByChip[chip] = arch
        return ChipIdentity(chip, arch)
    }

    private fun readArch(chip: String): String? {
        val basePath = project.basePath ?: return null
        val definition = PyMcuVenv.sitePackages(basePath)
            ?.resolve("pymcu/chips/$chip.py")
            ?.toFile()
            ?.takeIf { it.isFile }
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
