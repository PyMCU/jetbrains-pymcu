package dev.begeistert.pymcu.cli

import com.intellij.openapi.project.Project
import dev.begeistert.pymcu.cli.JsonLite.arr
import dev.begeistert.pymcu.cli.JsonLite.str

/**
 * A library, whether installed in the project or only listed in the index.
 *
 * [reasons] is the driver's verdict — the measured reasons this library cannot
 * serve the project's chip. Empty means nothing stops it.
 */
data class PyMcuLibrary(
    val name: String,
    val distribution: String,
    val version: String,
    val summary: String,
    val categories: List<String>,
    val layer: String,
    val repository: String,
    val reasons: List<String>,
    val installed: Boolean,
    /** Modules this library exposes. Known only for installed libraries. */
    val modules: List<String> = emptyList(),
) {
    val fits: Boolean get() = reasons.isEmpty()
}

data class InstalledLibraries(
    val chip: String,
    val board: String,
    val flavors: List<String>,
    val libraries: List<PyMcuLibrary>,
    val collisions: List<String>,
    val invalid: List<String>,
)

data class LibrarySearchResults(
    val chip: String,
    val flavors: List<String>,
    /** "network" or "cache" — the driver says where the index came from. */
    val source: String,
    val libraries: List<PyMcuLibrary>,
)

/**
 * Wraps the driver's library commands.
 *
 * The compatibility verdict is the interesting part and it is *measured* — the
 * index records whether each library actually built for each chip. Recomputing
 * that in Kotlin would be inventing an answer the driver already knows, so the
 * plugin only renders `--json` and never decides for itself.
 */
object PyMcuLibraries {

    /** `pymcu libraries --json --all`. Blocking; call off the EDT. */
    fun installed(project: Project): InstalledLibraries? {
        val result = PyMcuCli.run(project, "libraries", "--all", "--json", timeoutMs = 60_000)
        if (!result.started) return null
        return parseInstalled(result.stdout)
    }

    /** `pymcu search --json`. Blocking; call off the EDT. */
    fun search(
        project: Project,
        query: String = "",
        allTargets: Boolean = true,
        refresh: Boolean = false
    ): LibrarySearchResults? {
        val args = buildList {
            add("search")
            if (query.isNotBlank()) add(query)
            if (allTargets) add("--all")
            if (refresh) add("--refresh")
            add("--json")
        }
        val result = PyMcuCli.run(project, *args.toTypedArray(), timeoutMs = 90_000)
        if (!result.started) return null
        return parseSearch(result.stdout)
    }

    // ── parsing ──────────────────────────────────────────────────────────────

    /** Exposed for tests. */
    fun parseInstalled(json: String): InstalledLibraries? {
        val root = JsonLite.parseObject(json) ?: return null
        if (root.containsKey("error")) return null
        if (!root.containsKey("libraries")) return null
        return InstalledLibraries(
            chip = root["chip"].str().orEmpty(),
            board = root["board"].str().orEmpty(),
            flavors = root.arr("flavors").mapNotNull { it as? String },
            libraries = root.arr("libraries").mapNotNull { entry ->
                @Suppress("UNCHECKED_CAST")
                val map = entry as? Map<String, Any?> ?: return@mapNotNull null
                library(map, installed = true)
            },
            collisions = root.arr("collisions").mapNotNull { it as? String },
            invalid = root.arr("invalid").mapNotNull { it as? String },
        )
    }

    /** Exposed for tests. */
    fun parseSearch(json: String): LibrarySearchResults? {
        val root = JsonLite.parseObject(json) ?: return null
        if (root.containsKey("error")) return null
        if (!root.containsKey("libraries")) return null
        return LibrarySearchResults(
            chip = root["chip"].str().orEmpty(),
            flavors = root.arr("flavors").mapNotNull { it as? String },
            source = root["source"].str().orEmpty(),
            libraries = root.arr("libraries").mapNotNull { entry ->
                @Suppress("UNCHECKED_CAST")
                val map = entry as? Map<String, Any?> ?: return@mapNotNull null
                library(map, installed = map["installed"] == true)
            },
        )
    }

    /**
     * Both payloads describe a library. They differ in the name of the boolean
     * verdict (`usable` when installed, `fits` in the index) — which is why this
     * reads `reasons` instead: it is the field both derive from, so one branch
     * covers both and neither can disagree with the driver.
     */
    private fun library(map: Map<String, Any?>, installed: Boolean): PyMcuLibrary? {
        val name = map["name"].str() ?: return null
        return PyMcuLibrary(
            name = name,
            distribution = map["distribution"].str().orEmpty(),
            version = map["version"].str().orEmpty(),
            summary = map["summary"].str().orEmpty(),
            categories = (map["categories"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
            layer = map["layer"].str() ?: "native",
            repository = map["repository"].str().orEmpty(),
            reasons = (map["reasons"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
            installed = installed,
            modules = (map["modules"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
        )
    }
}
