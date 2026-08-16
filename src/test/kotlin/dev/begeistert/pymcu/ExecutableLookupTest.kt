package dev.begeistert.pymcu

import dev.begeistert.pymcu.cli.PyMcuCli
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Finding the `pymcu` binary.
 *
 * The bug: an IDE launched from the Dock has no login shell's PATH, and the
 * documented install (`pipx install pymcu-compiler`) puts the binary in
 * `~/.local/bin`. Looking only at the project virtualenv and PATH meant a
 * perfectly working CLI was reported as "Cannot run program pymcu" the moment a
 * project had no `.venv` — which is every project whose sync had failed.
 */
class ExecutableLookupTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val nothingOnPath: () -> File? = { null }

    private fun executable(dir: File, name: String = "pymcu"): File {
        dir.mkdirs()
        return File(dir, name).apply { writeText("#!/bin/sh\n"); setExecutable(true) }
    }

    private fun projectWithVenv(): Pair<String, File> {
        val root = folder.newFolder()
        val binary = executable(File(root, ".venv/bin"))
        return root.absolutePath to binary
    }

    // ── order ────────────────────────────────────────────────────────────────

    @Test
    fun `an explicit setting wins over everything`() {
        val (base, _) = projectWithVenv()
        val chosen = executable(folder.newFolder(), "pymcu")
        assertEquals(
            chosen,
            PyMcuCli.resolve(chosen.absolutePath, base, nothingOnPath, listOf(folder.newFolder()))
        )
    }

    /** A wrong explicit path is an error to surface, not a reason to guess. */
    @Test
    fun `an explicit setting that does not exist resolves to nothing`() {
        val (base, _) = projectWithVenv()
        assertNull(PyMcuCli.resolve("/nowhere/pymcu", base, nothingOnPath, emptyList()))
    }

    @Test
    fun `the project virtualenv beats PATH and the install directories`() {
        val (base, venvBinary) = projectWithVenv()
        val onPath = executable(folder.newFolder())
        val installed = folder.newFolder().also { executable(it) }

        assertEquals(venvBinary, PyMcuCli.resolve("pymcu", base, { onPath }, listOf(installed)))
    }

    @Test
    fun `PATH beats the install directories`() {
        val onPath = executable(folder.newFolder())
        val installed = folder.newFolder().also { executable(it) }

        assertEquals(onPath, PyMcuCli.resolve("pymcu", null, { onPath }, listOf(installed)))
    }

    /** The regression: nothing in the project, nothing on PATH, pipx install. */
    @Test
    fun `a pipx install is found when the project and PATH have nothing`() {
        val localBin = folder.newFolder("home", ".local", "bin")
        val binary = executable(localBin)
        val base = folder.newFolder().absolutePath

        assertEquals(binary, PyMcuCli.resolve("pymcu", base, nothingOnPath, listOf(localBin)))
    }

    @Test
    fun `install directories are tried in order`() {
        val first = folder.newFolder("first")
        val second = folder.newFolder("second").also { executable(it) }
        // Nothing in `first`, so `second` answers.
        assertEquals(
            File(second, "pymcu"),
            PyMcuCli.resolve("pymcu", null, nothingOnPath, listOf(first, second))
        )
    }

    // ── giving up ────────────────────────────────────────────────────────────

    @Test
    fun `nothing anywhere resolves to null, so the caller can explain`() {
        assertNull(PyMcuCli.resolve("pymcu", folder.newFolder().absolutePath, nothingOnPath, emptyList()))
        assertNull(PyMcuCli.resolve("", null, nothingOnPath, emptyList()))
        assertNull(PyMcuCli.resolve(null, null, nothingOnPath, emptyList()))
    }

    @Test
    fun `a directory named like the binary is not mistaken for it`() {
        val dir = folder.newFolder()
        File(dir, PyMcuCli.binaryName).mkdirs()
        assertNull(PyMcuCli.resolve("pymcu", null, nothingOnPath, listOf(dir)))
    }

    // ── where it looks ───────────────────────────────────────────────────────

    @Test
    fun `the pipx directory is among the places searched`() {
        val home = System.getProperty("user.home")
        assertTrue(
            "~/.local/bin is where pipx puts it",
            PyMcuCli.commonInstallDirectories().any { it.path == File(home, ".local/bin").path }
        )
    }
}
