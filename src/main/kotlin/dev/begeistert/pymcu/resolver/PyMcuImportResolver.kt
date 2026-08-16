package dev.begeistert.pymcu.resolver

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.QualifiedName
import com.jetbrains.python.psi.impl.PyImportResolver
import com.jetbrains.python.psi.resolve.PyQualifiedNameResolveContext
import dev.begeistert.pymcu.project.PyMcuProjectService
import dev.begeistert.pymcu.venv.PyMcuVenv
import java.nio.file.Path

/**
 * Resolves the bare imports the PyMCU compiler accepts — `machine`, `board`,
 * `digitalio`, `utime` — to the files the compiler itself would use.
 *
 * WHY this exists on top of [PyMcuAdditionalLibraryRootsProvider]: that provider
 * makes the directories *indexed* — they appear under External Libraries, and
 * search and go-to-file reach them. It does not make them *import roots*.
 * Python's resolution walks the interpreter's paths and this extension point,
 * not the synthetic-library list, so `import machine` stayed unresolved even
 * with `pymcu_micropython` indexed. This is the hook PyCharm provides for
 * exactly that: a plugin that knows about paths the interpreter does not.
 *
 * **It always resolves to source, never to a stub.** An earlier version put the
 * generated `.pyi` tree first, which gave marginally tidier type hints and made
 * the whole stdlib a black box: go-to-definition on `pin.value(1)` landed on
 * `def value(self, x: int = 255) -> int: ...` instead of on the code that
 * explains why 255 means "read" and what `@inline` does to it. Being able to
 * read all the way down to the register write is most of why this compiler is
 * interesting, and an IDE that hides it is working against the project.
 *
 * Nothing is lost by it: the annotations reference `pymcu.types`, which is an
 * installed package the interpreter already resolves, so `uint8` shows up as
 * itself rather than as a stubbed `int`.
 *
 * The search order mirrors the compiler's include order, so what the editor
 * shows is the file the build compiles:
 *   1. `<site-packages>/pymcu_<flavor>` — the compat layer's implementation
 *   2. `dist/_generated`                — the generated `board` module
 */
class PyMcuImportResolver : PyImportResolver {

    override fun resolveImportReference(
        name: QualifiedName,
        context: PyQualifiedNameResolveContext,
        withRoots: Boolean
    ): PsiElement? {
        val components = name.components
        if (components.isEmpty()) return null

        val project = context.project
        // Cheap and cached; keeps non-PyMCU projects out of the hot path entirely.
        val config = PyMcuProjectService.config(project) ?: return null
        val basePath = project.basePath ?: return null

        val target = findIn(searchRoots(basePath, config.stdlib), components) ?: return null
        return PsiManager.getInstance(project).let { psi ->
            if (target.isDirectory) psi.findDirectory(target) else psi.findFile(target)
        }
    }

    /** The include path, in the order the compiler resolves it. */
    private fun searchRoots(basePath: String, flavors: List<String>): List<Path> {
        val sitePackages = PyMcuVenv.sitePackages(basePath)
        return buildList {
            if (sitePackages != null) {
                for (flavor in flavors) add(sitePackages.resolve("pymcu_$flavor"))
            }
            add(Path.of(basePath, "dist", "_generated"))
        }
    }

    /**
     * `a.b.c` under [roots] as `a/b/c.pyi`, `a/b/c.py`, or the package directory
     * `a/b/c` when it has an `__init__`.
     *
     * findFileByNioFile, not refreshAndFind: resolution runs under a read action,
     * where taking the VFS write lock would deadlock.
     */
    private fun findIn(roots: List<Path>, components: List<String>): VirtualFile? {
        val lfs = LocalFileSystem.getInstance()
        for (candidate in candidatePaths(roots, components)) {
            val file = lfs.findFileByNioFile(candidate) ?: continue
            if (file.isDirectory) {
                if (file.findChild("__init__.py") != null || file.findChild("__init__.pyi") != null) {
                    return file
                }
            } else {
                return file
            }
        }
        return null
    }

    companion object {
        /**
         * Every place `a.b.c` could live, in the order the compiler would find it.
         *
         * `.py` before `.pyi`: where a package ships both, the source is the one
         * worth opening — see the class docs. The package directory comes last
         * within a root, since a module file of the same name shadows it.
         *
         * Returns empty for anything that could climb out of a root — a qualified
         * name never legitimately contains a separator or a dot segment.
         */
        fun candidatePaths(roots: List<Path>, components: List<String>): List<Path> {
            if (components.isEmpty()) return emptyList()
            if (components.any { it.isEmpty() || it == "." || it == ".." || '/' in it || '\\' in it }) {
                return emptyList()
            }

            val leaf = components.last()
            return buildList {
                for (root in roots) {
                    var directory = root
                    for (part in components.dropLast(1)) directory = directory.resolve(part)
                    add(directory.resolve("$leaf.py"))
                    add(directory.resolve("$leaf.pyi"))
                    add(directory.resolve(leaf))
                }
            }
        }
    }
}
