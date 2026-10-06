package dev.begeistert.pymcu.resolver

import com.intellij.openapi.project.Project
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
 *   1. `stdlib_path`, when the project sets one — a local stdlib checkout
 *   2. `dist/_generated`                — the generated `board` module
 *   3. `<site-packages>/pymcu_<flavor>` — the compat layer's implementation
 *   4. `pymcu` namespace parents        — for `pymcu.*` only: every sys.path
 *      entry the project's venv gives the stdlib, so a foreign SDK serving an
 *      older `pymcu` is not the only answer resolution ever sees
 *
 * The generated directory goes first because that is what `build.py` does —
 * `extra_includes.insert(0, generated_dir)`, so the board shim wins. No module
 * currently exists in both roots, so the order has never mattered; it is set
 * this way so that it keeps not mattering.
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

        val target = findIn(
            searchRoots(project, basePath, config.stdlib, config.stdlibPath, components.first() == "pymcu"),
            components
        ) ?: return null
        return PsiManager.getInstance(project).let { psi ->
            if (target.isDirectory) psi.findDirectory(target) else psi.findFile(target)
        }
    }

    /** The include path, in the order the compiler resolves it. */
    private fun searchRoots(
        project: Project,
        basePath: String,
        flavors: List<String>,
        stdlibPath: String?,
        pymcuNamespace: Boolean
    ): List<Path> {
        val sitePackages = PyMcuVenv.sitePackages(basePath)
        return buildList {
            // `stdlib_path` goes ahead of everything installed, which is what
            // build.py does with it — otherwise someone developing the stdlib has
            // the editor reading the released copy and the compiler their own.
            stdlibPath?.let { add(Path.of(basePath).resolve(it).normalize()) }
            add(Path.of(basePath, "dist", "_generated"))
            if (sitePackages != null) {
                // packageDir, not sitePackages/pymcu_<flavor>: an editable install
                // leaves nothing under site-packages, and the layer's bare names
                // are resolved here and nowhere else. See PyMcuVenv.packageDir.
                for (flavor in flavors) {
                    PyMcuVenv.packageDir(sitePackages, "pymcu_$flavor")?.let(::add)
                }
                if (pymcuNamespace) {
                    // `pymcu` is a namespace package whose portions live under
                    // the entries a `.pth` adds to sys.path — the parents are
                    // the roots `import pymcu.*` resolves through. Without this
                    // a foreign or stale SDK is the only answer the IDE has: it
                    // serves whatever pymcu it carries, and the project's own
                    // stdlib — the copy the build compiles — never enters the
                    // resolution. The project's portions go ahead of the SDK's
                    // for the same reason the compat layer's do below.
                    PyMcuVenv.packageDirs(sitePackages, "pymcu").mapNotNullTo(this) { it.parent }
                }
            }
            // A project may run on a shared or foreign interpreter and own no
            // `.venv`; the compat layer is still importable through the SDK's
            // class roots, and the project's own copy wins when both exist.
            for (flavor in flavors) {
                addAll(PyMcuVenv.sdkPackageDirs(project, "pymcu_$flavor"))
            }
            if (pymcuNamespace) {
                addAll(PyMcuVenv.sdkPackageDirs(project, "pymcu").mapNotNull { it.parent })
            }
        }.distinct()
    }

    /**
     * `a.b.c` under [roots] as `a/b/c.py`, `a/b/c.pyi`, or the package directory
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
