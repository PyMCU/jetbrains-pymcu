package dev.begeistert.pymcu.venv

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ProjectRootManager
import dev.begeistert.pymcu.project.PyMcuProjectService
import java.io.File
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/** Locates the project virtualenv the driver itself re-execs into. */
object PyMcuVenv {

    /** `<project>/.venv/lib/pythonX.Y/site-packages`, or the Windows equivalent. */
    fun sitePackages(basePath: String): Path? {
        for (venvName in listOf(".venv", "venv")) {
            val venv = File(basePath, venvName)
            if (!venv.isDirectory) continue

            // Windows: <venv>/Lib/site-packages, no interpreter version in the path.
            val windows = venv.resolve("Lib/site-packages").toPath()
            if (windows.exists()) return windows

            val libDir = venv.resolve("lib")
            val pythonDir = libDir.listFiles()?.firstOrNull { it.name.startsWith("python") } ?: continue
            val sitePackages = pythonDir.resolve("site-packages").toPath()
            if (sitePackages.exists()) return sitePackages
        }
        return null
    }

    /**
     * Where `import <packageName>` would actually find its files.
     *
     * A normal install puts the package straight under site-packages, and that
     * is the whole of what this used to look for. An **editable** install puts
     * nothing there: it drops a `.pth` naming the checkout's source root, and
     * the package lives in the user's repo. `pip install -e`, `uv pip install
     * -e` and a `[tool.uv.sources]` entry with `editable = true` all do this.
     *
     * The symptom when this is missed is narrow and confusing: `import pymcu`
     * keeps working, because PyCharm reads the `.pth` files when it builds the
     * interpreter's paths, but the compat layer's bare names — `pwmio`,
     * `digitalio`, `machine` — go red, because those are not importable through
     * the interpreter at all and are resolved by this plugin alone.
     *
     * The driver has no such gap: `build.py` asks `importlib.util.find_spec`,
     * which follows the `.pth` like any other import. This is the offline
     * equivalent, and the order matches Python's: an installed copy under
     * site-packages wins over a path a `.pth` appends to `sys.path`.
     */
    fun packageDir(sitePackages: Path, packageName: String): Path? =
        packageDirs(sitePackages, packageName).firstOrNull()

    /**
     * Every directory [packageName] is spread over, in import order.
     *
     * `pymcu` is a namespace package: the stdlib, the SDK and each backend
     * plugin contribute a portion, and with the stdlib installed editable the
     * portion under site-packages holds only the backend's subpackages. A
     * caller looking for one file — `pymcu/chips/<chip>.py`, say — has to look
     * in all of them, and one that stops at the first finds a directory that
     * exists and does not contain what it came for.
     */
    fun packageDirs(sitePackages: Path, packageName: String): List<Path> = buildList {
        val installed = sitePackages.resolve(packageName)
        if (installed.isDirectory()) add(installed)
        for (root in editableRoots(sitePackages)) {
            val candidate = root.resolve(packageName)
            if (candidate.isDirectory() && candidate !in this) add(candidate)
        }
        finderMapping(sitePackages, packageName)?.let { if (it !in this) add(it) }
    }

    /**
     * Directories named [packageName] reachable through the project's configured
     * interpreter, wherever its class roots point.
     *
     * The project need not own a `.venv` at all — a shared or foreign SDK still
     * carries site-packages, and PyCharm adds every directory a `.pth` names as
     * a class root too, so `pymcu` and the compat layer stay findable for a
     * project that resolves `import pymcu` through the IDE's interpreter but has
     * no venv directory under its base path.
     */
    fun sdkPackageDirs(project: Project, packageName: String): List<Path> {
        val sdk = ProjectRootManager.getInstance(project).projectSdk ?: return emptyList()
        return sdk.rootProvider.getFiles(OrderRootType.CLASSES)
            .mapNotNull { it.findChild(packageName)?.takeIf { dir -> dir.isDirectory } }
            .map { Path.of(it.path) }
            .distinct()
    }

    /**
     * The `sys.path` entries the `.pth` files in [sitePackages] add.
     *
     * Every non-blank line that is not a comment and not an `import` hook is a
     * path, relative to site-packages when it is not absolute — that is site.py's
     * own rule. The `import` lines are setuptools' strict-mode finders, handled
     * by [finderMapping] instead; there is nothing to execute here.
     *
     * Cached against the directory's timestamp, which moves when a package is
     * installed or removed. Resolution runs under a read action on every import,
     * and re-reading a dozen small files there is not free.
     */
    private fun editableRoots(sitePackages: Path): List<Path> {
        val directory = sitePackages.toFile()
        val stamp = directory.lastModified()
        cache[sitePackages]?.let { if (it.stamp == stamp) return it.roots }

        val roots = buildList {
            for (file in directory.listFiles().orEmpty().sortedBy { it.name }) {
                if (!file.isFile || !file.name.endsWith(".pth")) continue
                val lines = runCatching { file.readLines() }.getOrNull() ?: continue
                for (line in lines) {
                    val entry = line.trim()
                    if (entry.isEmpty() || entry.startsWith("#")) continue
                    if (entry.startsWith("import ") || entry.startsWith("import\t")) continue
                    val path = runCatching { sitePackages.resolve(entry).normalize() }.getOrNull()
                    if (path != null && path.isDirectory()) add(path)
                }
            }
        }
        cache[sitePackages] = CachedRoots(stamp, roots)
        return roots
    }

    /**
     * True when [path] lies in the `pymcu` the project itself builds against:
     * a portion of the project's own venv, or the checkout `stdlib_path` names.
     *
     * A foreign or shared SDK can carry a different `pymcu` — cp-servo pointed
     * at another project's interpreter served a stdlib months older than the
     * editable checkout in its own `.venv`. When resolution offers both, this
     * is what separates "the file the compiler reads" from "the same file as it
     * looked a release ago".
     */
    fun isProjectStdlibFile(project: Project, path: String): Boolean {
        val basePath = project.basePath ?: return false
        val normalized = path.replace('\\', '/')
        PyMcuProjectService.config(project)?.stdlibPath?.let {
            val root = Path.of(basePath).resolve(it).normalize().toString().replace('\\', '/')
            if (normalized.startsWith("$root/")) return true
        }
        val sitePackages = sitePackages(basePath) ?: return false
        return packageDirs(sitePackages, "pymcu").any {
            normalized.startsWith(it.toString().replace('\\', '/') + "/")
        }
    }

    /**
     * setuptools' strict editable mode ships no path at all — a `.pth` imports a
     * generated `__editable___<dist>_finder` module whose `MAPPING` dict holds
     * `'package': '/abs/path/to/package'`. Read the dict rather than run it.
     */
    private fun finderMapping(sitePackages: Path, packageName: String): Path? {
        val quoted = Regex.escape(packageName)
        val entry = Regex("""['"]$quoted['"]\s*:\s*['"]([^'"]+)['"]""")
        for (file in sitePackages.toFile().listFiles().orEmpty().sortedBy { it.name }) {
            if (!file.isFile) continue
            if (!file.name.startsWith("__editable__") || !file.name.endsWith(".py")) continue
            val text = runCatching { file.readText() }.getOrNull() ?: continue
            val match = entry.find(text) ?: continue
            val path = runCatching { sitePackages.resolve(match.groupValues[1]).normalize() }.getOrNull()
            if (path != null && path.isDirectory()) return path
        }
        return null
    }

    private class CachedRoots(val stamp: Long, val roots: List<Path>)

    private val cache = ConcurrentHashMap<Path, CachedRoots>()

    /** For tests: the cache is keyed by mtime, whose resolution is coarser than a test. */
    fun clearCaches() = cache.clear()
}
