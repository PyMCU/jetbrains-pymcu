package dev.begeistert.pymcu.cli

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
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
    fun executable(project: Project?): String =
        findExecutable(project)?.absolutePath ?: "pymcu"

    /**
     * The `pymcu` binary, or null when it cannot be found anywhere.
     *
     * WHY it looks past PATH: an IDE launched from the Dock or the Start menu
     * does not inherit a login shell's PATH, and the usual install is
     * `pipx install pymcu-compiler`, which puts the binary in `~/.local/bin` —
     * a directory such an IDE has never heard of. Falling back to the bare name
     * there produces "Cannot run program pymcu", which tells the user nothing
     * about a tool they can see working in their terminal.
     */
    fun findExecutable(project: Project?): File? = resolve(
        configured = PyMcuSettings.getInstance().executablePath,
        projectBase = project?.basePath,
        onPath = { PathEnvironmentVariableUtil.findInPath(binaryName) },
    )

    /**
     * The search order, separated from the services it consults so it can be
     * asserted directly. [onPath] is the PATH lookup, which only means anything
     * inside a running IDE.
     */
    fun resolve(
        configured: String?,
        projectBase: String?,
        onPath: () -> File?,
        installDirectories: List<File> = commonInstallDirectories(),
    ): File? {
        val explicit = configured?.trim().orEmpty()
        if (explicit.isNotEmpty() && explicit != "pymcu") {
            // An explicit setting is an instruction, not a hint: if it is wrong,
            // say so rather than quietly running some other pymcu.
            return File(explicit).takeIf { it.isFile }
        }

        // The project's own environment first: its pinned versions are the ones
        // that match the project, and the CLI re-execs into it anyway.
        if (projectBase != null) {
            for (venv in listOf(".venv", "venv")) {
                venvExecutable(File(projectBase, venv))?.let { return it }
            }
        }

        onPath()?.takeIf { it.isFile }?.let { return it }

        return installDirectories.map { File(it, binaryName) }.firstOrNull { it.isFile }
    }

    private fun venvExecutable(venv: File): File? {
        val candidate = if (isWindows) File(venv, "Scripts/pymcu.exe") else File(venv, "bin/pymcu")
        return candidate.takeIf { it.isFile }
    }

    /** Where the documented install methods put it, in order of likelihood. */
    fun commonInstallDirectories(): List<File> {
        val home = System.getProperty("user.home") ?: return emptyList()
        return if (isWindows) {
            listOf(File(home, ".local/bin"), File(home, "AppData/Roaming/Python/Scripts"))
        } else {
            listOf(
                File(home, ".local/bin"),      // pipx, pip --user
                File("/opt/homebrew/bin"),     // Homebrew on Apple silicon
                File("/usr/local/bin"),        // Homebrew on Intel, manual installs
            )
        }
    }

    val binaryName: String get() = if (isWindows) "pymcu.exe" else "pymcu"

    /** Runs `pymcu <args>` in the project directory, capturing stdout and stderr. */
    fun run(project: Project?, vararg args: String, timeoutMs: Int = 60_000): CliResult =
        runIn(project?.basePath, executable(project), args.toList(), timeoutMs)

    /**
     * Runs an arbitrary command line in [workDir], capturing its output.
     *
     * [stdin] is for the few driver commands that still ask a question once
     * every flag has been supplied; without it they see EOF and abort.
     */
    fun runIn(
        workDir: String?,
        exe: String,
        args: List<String>,
        timeoutMs: Int = 60_000,
        stdin: File? = null,
    ): CliResult {
        val commandLine = GeneralCommandLine(listOf(exe) + args)
            .withCharset(StandardCharsets.UTF_8)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        if (workDir != null) commandLine.withWorkDirectory(workDir)
        if (stdin != null) commandLine.withInput(stdin)

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
