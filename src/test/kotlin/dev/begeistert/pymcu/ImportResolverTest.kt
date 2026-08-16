package dev.begeistert.pymcu

import dev.begeistert.pymcu.resolver.PyMcuImportResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

/**
 * Where the import resolver looks for a module, and in what order.
 *
 * Two contracts are pinned here. The order mirrors the compiler's include path,
 * so the editor and the build agree on which file a name means. And resolution
 * always lands on **source**, never on a generated stub: reading down to the
 * register write is the point of this compiler, and a `.pyi` full of `...`
 * turns the stdlib into a black box. Getting either wrong is silent — the
 * import still resolves, just to the wrong thing.
 */
class ImportResolverTest {

    private val sitePackages = Path.of("/p/.venv/lib/python3.12/site-packages/pymcu_micropython")
    private val generated = Path.of("/p/dist/_generated")

    /** What the resolver actually searches: sources, then the generated module. */
    private val roots = listOf(sitePackages, generated)

    private fun candidates(vararg components: String) =
        PyMcuImportResolver.candidatePaths(roots, components.toList())

    // ── ordering ─────────────────────────────────────────────────────────────

    /**
     * The regression that matters: go-to-definition on `pin.value(1)` must land
     * on the code that explains the sentinel, not on `def value(...) -> int: ...`.
     */
    @Test
    fun `the implementation outranks a stub of the same module`() {
        val paths = candidates("machine")
        val source = paths.indexOf(sitePackages.resolve("machine.py"))
        val stub = paths.indexOf(sitePackages.resolve("machine.pyi"))

        assertTrue("the implementation must be looked for", source >= 0)
        assertTrue("the source must come first", source < stub)
    }

    @Test
    fun `within one root, py beats pyi and both beat the package directory`() {
        val paths = candidates("machine").filter { it.startsWith(sitePackages) }
        assertEquals(
            listOf(
                sitePackages.resolve("machine.py"),
                sitePackages.resolve("machine.pyi"),
                sitePackages.resolve("machine"),
            ),
            paths
        )
    }

    /** The generated stub tree must not be reachable through resolution at all. */
    @Test
    fun `no candidate ever points into the generated stub tree`() {
        val stubTree = Path.of("/p/dist/_generated/stubs")
        for (name in listOf("machine", "board", "digitalio")) {
            assertTrue(
                "resolution must not reach the stub tree",
                candidates(name).none { it.startsWith(stubTree) }
            )
        }
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
        assertTrue(paths.contains(sitePackages.resolve("pymcu/hal/gpio.py")))
        assertTrue(paths.contains(generated.resolve("pymcu/hal/gpio.py")))
    }

    @Test
    fun `a single component is looked for directly under each root`() {
        assertTrue(candidates("digitalio").contains(sitePackages.resolve("digitalio.py")))
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

    /** With no compat layer there is no site-packages root, only the generated one. */
    @Test
    fun `a native project searches only the generated root`() {
        val paths = PyMcuImportResolver.candidatePaths(listOf(generated), listOf("board"))
        assertTrue(paths.contains(generated.resolve("board.py")))
        assertTrue(paths.none { it.startsWith(sitePackages) })
    }
}
