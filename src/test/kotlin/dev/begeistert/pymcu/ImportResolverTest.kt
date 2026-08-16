package dev.begeistert.pymcu

import dev.begeistert.pymcu.resolver.PyMcuImportResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

/**
 * Where the import resolver looks for a module, and in what order.
 *
 * The order is the contract: it mirrors the compiler's include path, so what
 * the editor resolves to is the file the build will compile. Getting it wrong
 * is silent — the import resolves, to the wrong thing.
 */
class ImportResolverTest {

    private val stubsFlavor = Path.of("/p/dist/_generated/stubs/pymcu_micropython")
    private val sitePackages = Path.of("/p/.venv/lib/python3.12/site-packages/pymcu_micropython")
    private val generated = Path.of("/p/dist/_generated")
    private val stubs = Path.of("/p/dist/_generated/stubs")

    private val roots = listOf(stubsFlavor, sitePackages, generated, stubs)

    private fun candidates(vararg components: String) =
        PyMcuImportResolver.candidatePaths(roots, components.toList())

    // ── ordering ─────────────────────────────────────────────────────────────

    @Test
    fun `a typed stub outranks the implementation of the same module`() {
        val paths = candidates("machine")
        val stub = paths.indexOf(stubsFlavor.resolve("machine.pyi"))
        val source = paths.indexOf(sitePackages.resolve("machine.py"))

        assertTrue("the stub must be looked for", stub >= 0)
        assertTrue("the implementation must be looked for", source >= 0)
        assertTrue("the stub must come first", stub < source)
    }

    @Test
    fun `within one root, pyi beats py and both beat the package directory`() {
        val paths = candidates("machine").filter { it.startsWith(stubsFlavor) }
        assertEquals(
            listOf(
                stubsFlavor.resolve("machine.pyi"),
                stubsFlavor.resolve("machine.py"),
                stubsFlavor.resolve("machine"),
            ),
            paths
        )
    }

    /** `import board` is served by the file `pymcu sync` regenerates. */
    @Test
    fun `the generated directory is searched for the board module`() {
        assertTrue(candidates("board").contains(generated.resolve("board.py")))
    }

    @Test
    fun `every root is searched`() {
        val paths = candidates("machine")
        for (root in roots) {
            assertTrue("$root was not searched", paths.any { it.startsWith(root) })
        }
    }

    // ── dotted names ─────────────────────────────────────────────────────────

    @Test
    fun `a dotted name becomes nested directories`() {
        val paths = candidates("pymcu", "hal", "gpio")
        assertTrue(paths.contains(stubs.resolve("pymcu/hal/gpio.pyi")))
        assertTrue(paths.contains(stubs.resolve("pymcu/hal/gpio.py")))
    }

    @Test
    fun `a single component is looked for directly under each root`() {
        assertTrue(candidates("digitalio").contains(stubsFlavor.resolve("digitalio.pyi")))
    }

    // ── refusals ─────────────────────────────────────────────────────────────

    @Test
    fun `a name that could climb out of a root is refused`() {
        assertTrue(candidates("..", "etc", "passwd").isEmpty())
        assertTrue(candidates("machine", "..").isEmpty())
        assertTrue(candidates("a/b").isEmpty())
        assertTrue(candidates(".").isEmpty())
    }

    @Test
    fun `an empty name yields nothing to look at`() {
        assertTrue(PyMcuImportResolver.candidatePaths(roots, emptyList()).isEmpty())
        assertTrue(candidates("").isEmpty())
    }

    @Test
    fun `no roots means no candidates`() {
        assertTrue(PyMcuImportResolver.candidatePaths(emptyList(), listOf("machine")).isEmpty())
    }

    // ── a project with no compat layer ───────────────────────────────────────

    @Test
    fun `a native project still resolves the generated and stub roots`() {
        val nativeRoots = listOf(generated, stubs)
        val paths = PyMcuImportResolver.candidatePaths(nativeRoots, listOf("pymcu", "time"))
        assertTrue(paths.contains(stubs.resolve("pymcu/time.pyi")))
        assertTrue(paths.none { it.startsWith(sitePackages) })
    }
}
