package dev.begeistert.pymcu.newproject

import com.intellij.openapi.diagnostic.Logger
import dev.begeistert.pymcu.cli.CliResult
import dev.begeistert.pymcu.cli.PyMcuCli
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Scaffolds a project by running `pymcu new`, rather than by writing the files
 * the plugin thinks a project needs.
 *
 * WHY: the hand-written version drifted immediately. It omitted
 * `[tool.pymcu.toolchain]` and `[tool.pymcu.flash]`, so a generated project had
 * no programmer to flash with; it omitted the Makefile; and it pinned
 * dependencies to a generic floor instead of the versions actually installed.
 * Every one of those is something `new.py` already gets right, and the list
 * would have kept growing.
 *
 * `pymcu new` is fully non-interactive once `--board`/`--chip` and
 * `--pkg-manager` are supplied, so there is nothing to reimplement.
 *
 * The one thing that is not copied out is the virtualenv: a venv records
 * absolute paths, so moving one from a temporary directory produces an
 * environment whose interpreter points at a directory that no longer exists.
 * [dev.begeistert.pymcu.actions.PyMcuSyncTask] creates it in place instead.
 */
object PyMcuDriverScaffold {

    private val log = Logger.getInstance(PyMcuDriverScaffold::class.java)

    /** Directories that must not be carried across; see the class docs. */
    private val NOT_PORTABLE = setOf(".venv", "venv")

    /** The package managers `pymcu new` knows how to scaffold for. */
    val SUPPORTED_PACKAGE_MANAGERS: List<String> = listOf("uv", "pip", "poetry")

    sealed interface Outcome {
        object Scaffolded : Outcome
        /** The CLI could not do it; [reason] is worth showing once. */
        data class Unavailable(val reason: String) : Outcome
    }

    /**
     * Runs `pymcu new` in a scratch directory and moves the result into
     * [targetDir]. Blocking; call off the EDT.
     */
    fun scaffold(targetDir: File, projectName: String, settings: PyMcuNewProjectSettings): Outcome {
        val scratch = try {
            Files.createTempDirectory("pymcu-new-")
        } catch (e: Exception) {
            return Outcome.Unavailable("could not create a temporary directory: ${e.message}")
        }

        return try {
            val result = PyMcuCli.runIn(
                scratch.toString(),
                PyMcuCli.executable(null),
                arguments(projectName, settings),
                timeoutMs = 180_000,
                stdin = blankAnswers(scratch),
            )
            when {
                !result.started -> Outcome.Unavailable("the pymcu CLI could not be run")
                !result.ok -> Outcome.Unavailable(failureDetail(result))
                else -> {
                    val produced = scratch.resolve(projectName)
                    if (!produced.toFile().isDirectory) {
                        Outcome.Unavailable("`pymcu new` reported success but produced nothing")
                    } else {
                        moveInto(produced, targetDir.toPath())
                        Outcome.Scaffolded
                    }
                }
            }
        } catch (e: Exception) {
            log.warn("PyMCU: `pymcu new` scaffolding failed", e)
            Outcome.Unavailable(e.message ?: e.javaClass.simpleName)
        } finally {
            scratch.toFile().deleteRecursively()
        }
    }

    /**
     * `pymcu new` takes the project name as a positional argument and creates a
     * directory of that name, which is why this runs in a scratch directory:
     * the wizard has already created the real one, and the driver refuses to
     * write into a directory that exists.
     */
    fun arguments(projectName: String, settings: PyMcuNewProjectSettings): List<String> = buildList {
        add("new")
        add(projectName)
        settings.board?.let { add("--board"); add(it) }
            ?: settings.chip?.let { add("--chip"); add(it) }
        add("--freq")
        add(settings.frequency.toString())
        settings.stdlib.takeIf { it.isNotEmpty() }?.let { add("--stdlib"); add(it) }
        add("--pkg-manager")
        add(settings.packageManager.takeIf { it in SUPPORTED_PACKAGE_MANAGERS } ?: "uv")
        // The IDE initialises the repository itself; a second `git init` inside
        // the scratch directory would only be moved on top of it.
        add("--no-git")
    }

    /**
     * A file of empty lines, so any prompt takes its default instead of hitting
     * EOF and aborting.
     *
     * One prompt survives a fully-specified command line: in advanced mode
     * (`--chip`) the driver asks for a compat flavor even when the rest is
     * given, and its default is "none". That is exactly what the wizard already
     * asked and recorded, so accepting the default is correct rather than a
     * guess. Anything unexpected still fails the scaffold and falls back with a
     * message, rather than silently answering a question nobody saw.
     */
    private fun blankAnswers(scratch: Path): File? = try {
        Files.writeString(scratch.resolve("answers"), "\n".repeat(4)).toFile()
    } catch (_: Exception) {
        null
    }

    private fun failureDetail(result: CliResult): String {
        val output = (result.stderr + "\n" + result.stdout).trim()
        val lastLine = output.lines().lastOrNull { it.isNotBlank() }?.trim()
        return lastLine?.takeIf { it.isNotEmpty() } ?: "exit code ${result.exitCode}"
    }

    private fun moveInto(source: Path, target: Path) {
        Files.createDirectories(target)
        source.toFile().listFiles()
            ?.filter { it.name !in NOT_PORTABLE }
            ?.forEach { child ->
                val destination = target.resolve(child.name)
                try {
                    Files.move(child.toPath(), destination, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: Exception) {
                    // Different filesystems, or a directory that already exists:
                    // fall back to a copy, which handles both.
                    child.copyRecursively(destination.toFile(), overwrite = true)
                }
            }
    }
}
