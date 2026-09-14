package dev.begeistert.pymcu

import dev.begeistert.pymcu.venv.PyMcuVenv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Path

/**
 * Where `import pwmio` finds its files when the compat layer is installed
 * **editable**.
 *
 * The bug this pins: the layer was looked for at `site-packages/pymcu_<flavor>`
 * and nowhere else. An editable install puts nothing there — it drops a `.pth`
 * naming the checkout's source root — so every bare compat name went red in the
 * editor while `import pymcu` kept working, because PyCharm reads those `.pth`
 * files when it builds the interpreter's paths and the plugin did not.
 *
 * The driver never had the gap: `build.py` asks `importlib.util.find_spec`.
 */
class VenvPackageLookupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var sitePackages: Path

    @Before
    fun setUp() {
        // The cache is keyed by the directory's mtime, whose resolution is
        // coarser than the gap between two tests writing into fresh folders.
        PyMcuVenv.clearCaches()
        sitePackages = tmp.newFolder("venv", "lib", "python3.12", "site-packages").toPath()
    }

    private fun pth(name: String, vararg lines: String) {
        sitePackages.resolve(name).toFile().writeText(lines.joinToString("\n"))
        PyMcuVenv.clearCaches()
    }

    private fun dir(path: Path): Path = path.also { it.toFile().mkdirs() }

    // ── a normal install ─────────────────────────────────────────────────────

    @Test
    fun `a package installed under site-packages is found where it lies`() {
        val installed = dir(sitePackages.resolve("pymcu_circuitpython"))
        assertEquals(installed, PyMcuVenv.packageDir(sitePackages, "pymcu_circuitpython"))
    }

    @Test
    fun `a package that is installed nowhere is not invented`() {
        assertNull(PyMcuVenv.packageDir(sitePackages, "pymcu_micropython"))
    }

    // ── editable installs ────────────────────────────────────────────────────

    /** uv and hatchling: `_editable_impl_<dist>.pth` holding the source root. */
    @Test
    fun `an editable layer is found through the pth that names its checkout`() {
        val checkout = dir(tmp.root.toPath().resolve("repos/pymcu-circuitpython/src"))
        dir(checkout.resolve("pymcu_circuitpython"))
        pth("_editable_impl_pymcu_circuitpython.pth", checkout.toString())

        assertEquals(
            checkout.resolve("pymcu_circuitpython"),
            PyMcuVenv.packageDir(sitePackages, "pymcu_circuitpython")
        )
    }

    @Test
    fun `a pth path relative to site-packages is resolved against it`() {
        val checkout = dir(sitePackages.resolve("../../../../src").normalize())
        dir(checkout.resolve("pymcu_micropython"))
        pth("editable.pth", "../../../../src")

        assertEquals(
            checkout.resolve("pymcu_micropython"),
            PyMcuVenv.packageDir(sitePackages, "pymcu_micropython")
        )
    }

    /** site.py executes `import` lines; there is nothing to take as a path there. */
    @Test
    fun `an import hook line is not mistaken for a directory`() {
        pth("__editable__.pymcu_circuitpython.pth", "import __editable___pymcu_circuitpython_finder")
        assertNull(PyMcuVenv.packageDir(sitePackages, "pymcu_circuitpython"))
    }

    /** setuptools strict mode: the path lives in the finder's MAPPING dict. */
    @Test
    fun `a strict-mode editable install is read out of the finder mapping`() {
        val checkout = dir(tmp.root.toPath().resolve("repos/cp/src/pymcu_circuitpython"))
        pth("__editable__.pymcu_circuitpython.pth", "import __editable___pymcu_cp_finder")
        sitePackages.resolve("__editable___pymcu_cp_0_1_finder.py").toFile().writeText(
            "MAPPING = {'pymcu_circuitpython': '$checkout'}\n"
        )
        PyMcuVenv.clearCaches()

        assertEquals(checkout, PyMcuVenv.packageDir(sitePackages, "pymcu_circuitpython"))
    }

    @Test
    fun `blank lines and comments in a pth are skipped`() {
        val checkout = dir(tmp.root.toPath().resolve("src"))
        dir(checkout.resolve("pymcu_circuitpython"))
        pth("mixed.pth", "", "# a comment", "/no/such/directory", checkout.toString())

        assertEquals(
            checkout.resolve("pymcu_circuitpython"),
            PyMcuVenv.packageDir(sitePackages, "pymcu_circuitpython")
        )
    }

    // ── order, and the namespace package ─────────────────────────────────────

    /** site-packages comes before anything a `.pth` appends, as it does in Python. */
    @Test
    fun `an installed copy outranks an editable one`() {
        val installed = dir(sitePackages.resolve("pymcu_circuitpython"))
        val checkout = dir(tmp.root.toPath().resolve("src"))
        dir(checkout.resolve("pymcu_circuitpython"))
        pth("editable.pth", checkout.toString())

        assertEquals(installed, PyMcuVenv.packageDir(sitePackages, "pymcu_circuitpython"))
    }

    /**
     * `pymcu` is a namespace package. With the stdlib editable, the portion
     * under site-packages holds only the backend's subpackages — a caller that
     * stops at the first portion finds a directory without `chips/` in it.
     */
    @Test
    fun `every portion of a namespace package is reported`() {
        val fromBackend = dir(sitePackages.resolve("pymcu/backend"))
        val checkout = dir(tmp.root.toPath().resolve("lib/src"))
        val fromStdlib = dir(checkout.resolve("pymcu/chips"))
        pth("_editable_impl_pymcu_stdlib.pth", checkout.toString())

        val portions = PyMcuVenv.packageDirs(sitePackages, "pymcu")
        assertEquals(listOf(fromBackend.parent, fromStdlib.parent), portions)
        assertTrue(
            "the stdlib's chips/ must be reachable",
            portions.any { it.resolve("chips").toFile().isDirectory }
        )
    }

    @Test
    fun `a site-packages with no pth files yields no editable roots`() {
        dir(sitePackages.resolve("pymcu"))
        assertEquals(
            listOf(sitePackages.resolve("pymcu")),
            PyMcuVenv.packageDirs(sitePackages, "pymcu")
        )
    }
}
