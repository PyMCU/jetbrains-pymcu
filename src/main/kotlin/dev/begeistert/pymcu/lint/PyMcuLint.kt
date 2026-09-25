package dev.begeistert.pymcu.lint

import com.intellij.openapi.project.Project
import dev.begeistert.pymcu.cli.JsonLite
import dev.begeistert.pymcu.cli.JsonLite.arr
import dev.begeistert.pymcu.cli.JsonLite.int
import dev.begeistert.pymcu.cli.JsonLite.str
import dev.begeistert.pymcu.cli.PyMcuCli
import dev.begeistert.pymcu.project.PyMcuProjectService
import dev.begeistert.pymcu.settings.PyMcuSettings

/** One finding from `pymcu lint --json`. */
data class LintFinding(
    val line: Int,
    val col: Int,
    val severity: String,   // error | warn | info
    val code: String,
    val message: String,
    val suggestion: String,
)

data class LintFileReport(val path: String, val findings: List<LintFinding>)

/**
 * What `pymcu lint` looked at, as the report itself states it.
 *
 * It exists because an empty finding list was being rendered as "this should port cleanly",
 * and lint had not checked anything that sentence claims. It resolves no name, knows no type,
 * follows no import and does not know the target: measured on four programs the compiler
 * refuses (an undefined call, a literal too wide for its parameter, an unknown type, a write
 * to a field that does not exist) it reports nothing for all four.
 *
 * Read from the report rather than written here, because three renderers in three
 * repositories each wrote their own sentence and they did not agree. One report, one wording.
 */
data class LintScope(val summary: String, val forCorrectnessRun: String) {
    companion object {
        /**
         * A report with no `scope` came from a driver older than the field.
         *
         * The answer is NOT to fall back to the cheerful sentence. An older driver checked
         * exactly as little as this one; it just did not say so. This says it on its behalf.
         */
        val UNSTATED = LintScope(
            "porting idioms only; names, types and imports were not resolved",
            "pymcu build")
    }
}

data class LintReport(
    val flavor: String?,
    val files: List<LintFileReport>,
    val scope: LintScope = LintScope.UNSTATED,
) {
    val allFindings: List<LintFinding> get() = files.flatMap { it.findings }

    /**
     * What to show when nothing was found, which is the whole point of this class.
     *
     * A claim about the findings, never about whether the program builds. The two are not the
     * same and only one of them was measured.
     */
    val nothingFoundMessage: String
        get() = "No porting blockers found (${scope.summary}). " +
                "Run `${scope.forCorrectnessRun}` to check it compiles."
}

/**
 * Wraps `pymcu lint --json`, the driver's porting assistant: it flags the
 * MicroPython / CircuitPython idioms that do not fit PyMCU's statically-typed,
 * heap-free subset, each with a concrete rewrite.
 *
 * The plugin does not reimplement any of that analysis — reproducing it in
 * Kotlin would guarantee drift the first time the subset changes.
 */
object PyMcuLint {

    const val DOCS_URL: String = "https://docs.pymcu.org/compat/"

    /** Lints a single file. [overridePath] lets the caller point at a temp copy of the buffer. */
    fun lintFile(project: Project, filePath: String): LintReport? =
        runLint(project, listOf(filePath))

    /** Lints the project's configured sources directory. */
    fun lintSources(project: Project): LintReport? {
        val basePath = project.basePath ?: return null
        val config = PyMcuProjectService.config(project)
        val sources = config?.sources ?: "src"
        val target = java.io.File(basePath, sources).takeIf { it.isDirectory }?.absolutePath ?: basePath
        return runLint(project, listOf(target))
    }

    private fun runLint(project: Project, paths: List<String>): LintReport? {
        val settings = PyMcuSettings.getInstance()
        val args = buildList {
            add("lint")
            addAll(paths)
            flavorArg(project)?.let { add("--flavor"); add(it) }
            if (settings.lintErrorsOnly) add("--errors-only")
            add("--json")
        }
        // `lint` exits 1 when it finds hard errors, so a non-zero code is
        // expected output, not a failure — only an unstartable process is.
        val result = PyMcuCli.run(project, *args.toTypedArray(), timeoutMs = 30_000)
        if (!result.started) return null
        return parse(result.stdout)
    }

    private fun flavorArg(project: Project): String? {
        val configured = PyMcuSettings.getInstance().lintFlavor
        if (configured != "auto") return configured
        return PyMcuProjectService.config(project)?.flavor
    }

    /** Exposed for tests. */
    fun parse(json: String): LintReport? {
        val root = JsonLite.parseObject(json) ?: return null
        if (root.containsKey("error")) return null
        val files = root.arr("files").mapNotNull { entry ->
            @Suppress("UNCHECKED_CAST")
            val map = entry as? Map<String, Any?> ?: return@mapNotNull null
            val path = map["path"].str() ?: return@mapNotNull null
            val findings = (map["findings"] as? List<*>)?.mapNotNull { raw ->
                @Suppress("UNCHECKED_CAST")
                val f = raw as? Map<String, Any?> ?: return@mapNotNull null
                LintFinding(
                    line = f["line"].int() ?: return@mapNotNull null,
                    col = f["col"].int() ?: 1,
                    severity = f["severity"].str() ?: "info",
                    code = f["code"].str() ?: "",
                    message = f["message"].str() ?: "",
                    suggestion = f["suggestion"].str() ?: "",
                )
            } ?: emptyList()
            LintFileReport(path, findings)
        }
        @Suppress("UNCHECKED_CAST")
        val scope = (root["scope"] as? Map<String, Any?>)?.let {
            LintScope(
                summary = it["summary"].str() ?: LintScope.UNSTATED.summary,
                forCorrectnessRun = it["for_correctness_run"].str()
                    ?: LintScope.UNSTATED.forCorrectnessRun)
        } ?: LintScope.UNSTATED
        return LintReport(root["flavor"].str(), files, scope)
    }
}
