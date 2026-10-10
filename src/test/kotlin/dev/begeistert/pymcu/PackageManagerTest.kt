package dev.begeistert.pymcu

import dev.begeistert.pymcu.cli.PyMcuCli
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Which package manager a sync uses.
 *
 * The bug this pins: the New Project wizard collected a choice and nothing read
 * it, so picking pip still ran `uv sync`. The override is the wizard's answer;
 * detection covers every project opened afterwards, where the application-wide
 * setting is the wrong authority.
 */
class PackageManagerTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** With no project venv, pip runs under the system Python 3 launcher, never a bare `pip`. */
    private val PY = if (System.getProperty("os.name").startsWith("Windows", true)) "py" else "python3"

    private fun project(vararg files: Pair<String, String>): String {
        val root = folder.newFolder()
        for ((name, content) in files) root.resolve(name).writeText(content)
        return root.absolutePath
    }

    // ── the wizard's choice wins ─────────────────────────────────────────────

    @Test
    fun `an explicit choice is honoured`() {
        val base = project()
        assertEquals(listOf(PY, "-m", "pip", "install", "-e", "."), PyMcuCli.syncCommand(base, "pip"))
        assertEquals(listOf("poetry", "install"), PyMcuCli.syncCommand(base, "poetry"))
        assertEquals(listOf("pipenv", "install"), PyMcuCli.syncCommand(base, "pipenv"))
        assertEquals(listOf("uv", "sync"), PyMcuCli.syncCommand(base, "uv"))
    }

    /** The regression: a pip project must not be synced with uv. */
    @Test
    fun `choosing pip does not run uv`() {
        val command = PyMcuCli.syncCommand(project(), "pip")
        assertEquals(listOf(PY, "-m", "pip"), command.take(3))
    }

    @Test
    fun `the choice beats the evidence on disk`() {
        val base = project("uv.lock" to "")
        assertEquals(listOf(PY, "-m", "pip"), PyMcuCli.syncCommand(base, "pip").take(3))
    }

    /**
     * The regression: detection answers "pip" *because* requirements.txt exists,
     * and `pip install -e .` reads only `[project].dependencies`. On a project
     * whose dependencies live in requirements.txt that installs the project, no
     * dependencies, and exits 0 — so the sync reported success having installed
     * nothing.
     */
    @Test
    fun `a pip project with requirements txt installs from it`() {
        val base = project(
            "pyproject.toml" to "[tool.pymcu]\nboard = \"arduino_uno\"\n",
            "requirements.txt" to "pymcu-stdlib\n",
        )
        assertEquals(listOf(PY, "-m", "pip", "install", "-r", "requirements.txt"), PyMcuCli.syncCommand(base))
        // Also when the wizard passes the choice explicitly.
        assertEquals(listOf(PY, "-m", "pip", "install", "-r", "requirements.txt"), PyMcuCli.syncCommand(base, "pip"))
    }

    /** Without one, the project's own metadata is the only place deps can be. */
    @Test
    fun `a pip project without requirements txt installs the project itself`() {
        val base = project("pyproject.toml" to "[project]\nname = \"x\"\ndependencies = [\"pymcu-stdlib\"]\n")
        assertEquals(listOf(PY, "-m", "pip", "install", "-e", "."), PyMcuCli.syncCommand(base, "pip"))
    }

    // ── detection from the project ───────────────────────────────────────────

    @Test
    fun `a lock file identifies the manager`() {
        assertEquals("uv", PyMcuCli.detectPackageManager(project("uv.lock" to "")))
        assertEquals("poetry", PyMcuCli.detectPackageManager(project("poetry.lock" to "")))
        assertEquals("pipenv", PyMcuCli.detectPackageManager(project("Pipfile" to "")))
        assertEquals("pip", PyMcuCli.detectPackageManager(project("requirements.txt" to "")))
    }

    /** What the wizard writes for pip is also what marks the project as pip's. */
    @Test
    fun `a scaffolded pip project is detected as pip`() {
        val base = project(
            "pyproject.toml" to "[tool.pymcu]\nboard = \"arduino_uno\"\n",
            "requirements.txt" to "pymcu-stdlib\npymcu-compiler\n",
        )
        assertEquals("pip", PyMcuCli.detectPackageManager(base))
        assertEquals(listOf(PY, "-m", "pip", "install", "-r", "requirements.txt"), PyMcuCli.syncCommand(base))
    }

    @Test
    fun `a poetry section counts before any lock file exists`() {
        val base = project("pyproject.toml" to "[tool.poetry]\nname = \"x\"\n")
        assertEquals("poetry", PyMcuCli.detectPackageManager(base))
    }

    @Test
    fun `a lock file outranks a bare pyproject`() {
        val base = project(
            "pyproject.toml" to "[tool.poetry]\nname = \"x\"\n",
            "uv.lock" to "",
        )
        assertEquals("uv", PyMcuCli.detectPackageManager(base))
    }

    // ── nothing to go on ─────────────────────────────────────────────────────

    @Test
    fun `a project with no marker is not guessed at`() {
        assertNull(PyMcuCli.detectPackageManager(project("pyproject.toml" to "[project]\nname = \"x\"\n")))
        assertNull(PyMcuCli.detectPackageManager(null))
        assertNull(PyMcuCli.detectPackageManager("/nonexistent/path/nowhere"))
    }

    // ── pip runs under the project's own interpreter ─────────────────────────

    /**
     * The regression: macOS often has no `pip` on PATH (only `pip3`), and the one
     * there is belongs to whichever Python came last. The project's venv
     * interpreter with `-m pip` installs into the project and nowhere else.
     */
    @Test
    fun `pip runs under the project venv interpreter when there is one`() {
        val base = project("requirements.txt" to "pymcu-stdlib\n")
        val bin = java.io.File(base, ".venv/bin").apply { mkdirs() }
        val python = java.io.File(bin, "python3").apply { writeText(""); setExecutable(true) }
        if (System.getProperty("os.name").startsWith("Windows", true)) return
        assertEquals(
            listOf(python.path, "-m", "pip", "install", "-r", "requirements.txt"),
            PyMcuCli.syncCommand(base, "pip"),
        )
        assertNull(PyMcuCli.ensurePipEnvironment(base))
    }

    @Test
    fun `a pip project without a venv gets one created first`() {
        val base = project("requirements.txt" to "pymcu-stdlib\n")
        assertEquals(listOf(PY, "-m", "venv", ".venv"), PyMcuCli.ensurePipEnvironment(base))
    }
}
