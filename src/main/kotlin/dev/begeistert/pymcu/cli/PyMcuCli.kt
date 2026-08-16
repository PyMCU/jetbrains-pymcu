package dev.begeistert.pymcu.cli

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import dev.begeistert.pymcu.settings.PyMcuSettings
import java.io.File
import java.nio.charset.StandardCharsets

/** Result of a CLI invocation. [exitCode] is -1 when the process could not be started. */
data class CliResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val ok: Boolean get() = exitCode == 0
    val started: Boolean get() = exitCode != -1
}

/**
 * Runs the `pymcu` CLI and captures its output.
 *
 * Uses [GeneralCommandLine] rather than a bare `ProcessBuilder` so the child
 * inherits the IDE's resolved environment (PATH from the login shell on macOS,
 * where a GUI-launched IDE otherwise sees a stub PATH without `~/.local/bin`)
 * and a known charset.
 */
object PyMcuCli {

    private val log = Logger.getInstance(PyMcuCli::class.java)

    /**
     * The executable to run, in the order the driver itself resolves things:
     * an explicit setting wins, then the project's own virtualenv (which is
     * what `pymcu` re-execs into anyway), then bare `pymcu` off PATH.
     */
    fun executable(project: Project?): String {
        val configured = PyMcuSettings.getInstance().executablePath.trim()
        if (configured.isNotEmpty() && configured != "pymcu") return configured

        val basePath = project?.basePath
        if (basePath != null) {
            for (venv in listOf(".venv", "venv")) {
                val candidate = if (isWindows)
                    File(basePath, "$venv/Scripts/pymcu.exe")
                else
                    File(basePath, "$venv/bin/pymcu")
                if (candidate.isFile) return candidate.absolutePath
            }
        }
        return "pymcu"
    }

    /** Runs `pymcu <args>` in the project directory, capturing stdout and stderr. */
    fun run(project: Project?, vararg args: String, timeoutMs: Int = 60_000): CliResult =
        runIn(project?.basePath, executable(project), args.toList(), timeoutMs)

    /** Runs an arbitrary command line in [workDir], capturing its output. */
    fun runIn(workDir: String?, exe: String, args: List<String>, timeoutMs: Int = 60_000): CliResult {
        val commandLine = GeneralCommandLine(listOf(exe) + args)
            .withCharset(StandardCharsets.UTF_8)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        if (workDir != null) commandLine.withWorkDirectory(workDir)

        return try {
            val output = CapturingProcessHandler(commandLine).runProcess(timeoutMs, true)
            CliResult(output.exitCode, output.stdout, output.stderr)
        } catch (e: Exception) {
            log.debug("PyMCU: could not run ${commandLine.commandLineString}", e)
            CliResult(-1, "", e.message.orEmpty())
        }
    }

    /** True when the CLI can be executed at all. */
    fun isAvailable(project: Project?): Boolean =
        run(project, "--version", timeoutMs = 15_000).started

    /**
     * The package manager to use for [basePath], as evidenced by the project
     * itself. Returns null when the project carries no marker.
     *
     * WHY look at the project at all: the configured manager is application-wide,
     * so a single setting decides for every project the IDE opens. A checkout with
     * a `poetry.lock` is a Poetry project no matter what that setting says, and
     * running `uv sync` in it creates a second, competing environment.
     */
    fun detectPackageManager(basePath: String?): String? {
        if (basePath == null) return null
        val root = File(basePath)
        return when {
            File(root, "uv.lock").isFile -> "uv"
            File(root, "poetry.lock").isFile -> "poetry"
            File(root, "Pipfile").isFile -> "pipenv"
            File(root, "requirements.txt").isFile -> "pip"
            // No lock file yet, but the section says who owns the dependencies.
            File(root, "pyproject.toml").takeIf { it.isFile }
                ?.readTextOrNull()?.contains("[tool.poetry]") == true -> "poetry"
            else -> null
        }
    }

    /**
     * The command that installs this project's dependencies.
     *
     * [override] wins (the New Project wizard knows what the user just picked),
     * then what the project itself shows, then the configured default.
     */
    fun syncCommand(basePath: String? = null, override: String? = null): List<String> {
        val manager = override
            ?: detectPackageManager(basePath)
            ?: PyMcuSettings.getInstance().packageManager
        return when (manager) {
            "poetry" -> listOf("poetry", "install")
            "pipenv" -> listOf("pipenv", "install")
            "pip" -> listOf("pip", "install", "-e", ".")
            else -> listOf("uv", "sync")
        }
    }

    private fun File.readTextOrNull(): String? = try {
        readText()
    } catch (_: Exception) {
        null
    }

    private val isWindows: Boolean get() = System.getProperty("os.name").startsWith("Windows", true)
}
