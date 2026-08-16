package dev.begeistert.pymcu.venv

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.impl.SdkConfigurationUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.jetbrains.python.sdk.PythonSdkType
import java.io.File

/**
 * Points the project at the interpreter in its own virtualenv.
 *
 * WHY the plugin has to do this at all: it contributes a plain
 * [com.intellij.platform.DirectoryProjectGenerator], which — unlike PyCharm's
 * own `PythonProjectGenerator` — has no interpreter step. A project created by
 * the wizard therefore had no SDK, and without one PyCharm resolves nothing:
 * not the compat layers, not `pymcu.*`, not the standard library. Every other
 * piece of resolution this plugin adds sits on top of an interpreter existing.
 *
 * It only ever fills a gap. An SDK the user chose deliberately is left alone,
 * because "the project has a virtualenv" is not evidence that they want it —
 * a monorepo checkout with a `.venv` in a subdirectory is a normal thing to
 * open with a different interpreter selected.
 */
object PyMcuInterpreter {

    private val log = Logger.getInstance(PyMcuInterpreter::class.java)

    /** The interpreter inside the project's virtualenv, if one has been created. */
    fun venvInterpreter(basePath: String): File? {
        for (venv in listOf(".venv", "venv")) {
            val candidate = interpreterIn(File(basePath, venv))
            if (candidate != null) return candidate
        }
        return null
    }

    /** `<venv>/bin/python` on POSIX, `<venv>\Scripts\python.exe` on Windows. */
    fun interpreterIn(venv: File): File? {
        val candidates = if (isWindows) {
            listOf(File(venv, "Scripts/python.exe"))
        } else {
            // python3 first: a venv always has both, and the unsuffixed name is a
            // symlink to it, so naming it directly keeps the SDK label honest.
            listOf(File(venv, "bin/python3"), File(venv, "bin/python"))
        }
        return candidates.firstOrNull { it.isFile }
    }

    /**
     * Whether [project] still needs an interpreter.
     *
     * True when it has none, or when the one it has points at somewhere that no
     * longer exists — a venv deleted and recreated leaves a dangling SDK behind,
     * and that reads to the user exactly like the plugin not working.
     */
    fun needsInterpreter(project: Project): Boolean {
        val current = ProjectRootManager.getInstance(project).projectSdk ?: return true
        val home = current.homePath ?: return true
        return !File(home).exists()
    }

    /**
     * Sets the project interpreter to the virtualenv's, if the project needs one
     * and the virtualenv exists. Returns the SDK in use, or null if nothing was
     * done. Safe to call from a background thread.
     */
    fun configureIfNeeded(project: Project): Sdk? {
        val basePath = project.basePath ?: return null
        if (!needsInterpreter(project)) return null
        val interpreter = venvInterpreter(basePath) ?: return null

        return try {
            attach(project, interpreter)
        } catch (e: Exception) {
            log.warn("PyMCU: could not set the project interpreter to $interpreter", e)
            null
        }
    }

    private fun attach(project: Project, interpreter: File): Sdk? {
        val path = interpreter.absolutePath
        val sdkType = PythonSdkType.getInstance()

        // Reuse before creating: registering a second SDK for the same home
        // leaves the user with duplicate entries in the interpreter list.
        val existing = ProjectJdkTable.getInstance()
            .getSdksOfType(sdkType)
            .firstOrNull { it.homePath?.let(::File)?.canonicalPathOrNull() == File(path).canonicalPathOrNull() }

        var result: Sdk? = existing
        ApplicationManager.getApplication().invokeAndWait {
            ApplicationManager.getApplication().runWriteAction {
                val sdk = existing ?: SdkConfigurationUtil.createAndAddSDK(path, sdkType)
                if (sdk != null) {
                    // setDirectoryProjectSdk, not ProjectRootManager: PyCharm
                    // projects are directory-based, and this sets the module's
                    // SDK too, which is what resolution actually consults.
                    SdkConfigurationUtil.setDirectoryProjectSdk(project, sdk)
                }
                result = sdk
            }
        }
        if (result != null) log.info("PyMCU: project interpreter set to $path")
        return result
    }

    private fun File.canonicalPathOrNull(): String? = try {
        canonicalPath
    } catch (_: Exception) {
        absolutePath
    }

    private val isWindows: Boolean get() = System.getProperty("os.name").startsWith("Windows", true)
}
