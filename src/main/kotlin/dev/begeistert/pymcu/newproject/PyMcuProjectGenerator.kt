package dev.begeistert.pymcu.newproject

import com.intellij.facet.ui.ValidationResult
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.DirectoryProjectGenerator
import com.intellij.platform.ProjectGeneratorPeer
import dev.begeistert.pymcu.PyMcuIcons
import dev.begeistert.pymcu.actions.PyMcuSyncTask
import dev.begeistert.pymcu.notifications.PyMcuNotifications
import dev.begeistert.pymcu.project.PyMcuProjectService
import java.io.File
import javax.swing.Icon

/**
 * Adds "PyMCU" to the New Project wizard.
 *
 * The scaffolding is `pymcu new`'s, not this plugin's — see
 * [PyMcuDriverScaffold]. [PyMcuScaffold] is the fallback for the one case the
 * driver cannot cover: creating a project before the CLI is installed.
 */
class PyMcuProjectGenerator : DirectoryProjectGenerator<PyMcuNewProjectSettings> {

    private val log = Logger.getInstance(PyMcuProjectGenerator::class.java)

    override fun getName(): String = "PyMCU"

    override fun getDescription(): String =
        "Compile Python to bare-metal firmware for AVR, RP2040/RP2350, PIC and RISC-V targets."

    override fun getLogo(): Icon = PyMcuIcons.Logo

    override fun validate(baseDirPath: String): ValidationResult = ValidationResult.OK

    override fun createPeer(): ProjectGeneratorPeer<PyMcuNewProjectSettings> = PyMcuProjectGeneratorPeer()

    override fun generateProject(
        project: Project,
        baseDir: VirtualFile,
        settings: PyMcuNewProjectSettings,
        module: Module
    ) {
        val projectName = project.name.ifBlank { baseDir.name }
        val baseFile = File(baseDir.path)

        // Off the EDT: this shells out to the driver and then installs
        // dependencies. Both show up in the progress bar.
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Creating the PyMCU project", true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.text = "Scaffolding with pymcu new…"
                    scaffold(project, baseFile, projectName, settings)

                    baseDir.refresh(false, true)
                    PyMcuProjectService.getInstance(project).invalidate()

                    // The wizard's choice is the only record of it at this point.
                    PyMcuSyncTask.execute(project, indicator, settings.packageManager)
                }
            }
        )
    }

    private fun scaffold(
        project: Project,
        baseDir: File,
        projectName: String,
        settings: PyMcuNewProjectSettings
    ) {
        when (val outcome = PyMcuDriverScaffold.scaffold(baseDir, projectName, settings)) {
            is PyMcuDriverScaffold.Outcome.Scaffolded -> Unit

            is PyMcuDriverScaffold.Outcome.Unavailable -> {
                log.info("PyMCU: falling back to the built-in scaffold (${outcome.reason})")
                writeFallback(baseDir, projectName, settings)
                PyMcuNotifications.warn(
                    project,
                    "PyMCU project created without the CLI",
                    "`pymcu new` could not run (${outcome.reason}), so the project was scaffolded " +
                        "from a built-in template. It has no toolchain or programmer settings, " +
                        "which flashing needs — install the CLI and re-create the project, or add " +
                        "them by hand.",
                )
            }
        }
    }

    /** Plain I/O: the fallback runs precisely when no CLI is available. */
    private fun writeFallback(baseDir: File, projectName: String, settings: PyMcuNewProjectSettings) {
        try {
            File(baseDir, "pyproject.toml").writeText(PyMcuScaffold.pyproject(projectName, settings))
            File(baseDir, "src").mkdirs()
            File(baseDir, "src/main.py").writeText(PyMcuScaffold.mainPy(settings))
            File(baseDir, ".gitignore").writeText(PyMcuScaffold.GITIGNORE)
            if (settings.packageManager == "pip") {
                File(baseDir, "requirements.txt").writeText(PyMcuScaffold.requirements(settings))
            }
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(baseDir)
        } catch (e: Exception) {
            log.error("Failed to scaffold the PyMCU project", e)
        }
    }
}
