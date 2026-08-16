package dev.begeistert.pymcu.newproject

import com.intellij.facet.ui.ValidationResult
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.DirectoryProjectGenerator
import com.intellij.platform.ProjectGeneratorPeer
import dev.begeistert.pymcu.PyMcuIcons
import dev.begeistert.pymcu.actions.PyMcuSyncTask
import dev.begeistert.pymcu.project.PyMcuProjectService
import javax.swing.Icon

/**
 * Adds "PyMCU" to the New Project wizard.
 *
 * Scaffolds `pyproject.toml` and a blink starter for the chosen board, then
 * runs the same sync path as the Sync action — dependencies, generated board
 * module and IDE stubs — under a visible progress indicator.
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

        ApplicationManager.getApplication().runWriteAction {
            try {
                write(baseDir, "pyproject.toml", PyMcuScaffold.pyproject(projectName, settings))
                val sources = baseDir.findChild("src") ?: baseDir.createChildDirectory(this, "src")
                write(sources, "main.py", PyMcuScaffold.mainPy(settings))
                write(baseDir, ".gitignore", PyMcuScaffold.GITIGNORE)
                // `pymcu new` writes this for pip projects, and it is also what
                // marks the project as pip-managed for every later sync.
                if (settings.packageManager == "pip") {
                    write(baseDir, "requirements.txt", PyMcuScaffold.requirements(settings))
                }
            } catch (e: Exception) {
                log.error("Failed to scaffold the PyMCU project", e)
            }
        }

        PyMcuProjectService.getInstance(project).invalidate()

        // Pass the wizard's choice explicitly: nothing on disk can show it yet,
        // and the configured default is application-wide.
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Setting up the PyMCU project", true) {
                override fun run(indicator: ProgressIndicator) =
                    PyMcuSyncTask.execute(project, indicator, settings.packageManager)
            }
        )
    }

    // ── scaffolding ──────────────────────────────────────────────────────────

    private fun write(dir: VirtualFile, name: String, content: String) {
        dir.findOrCreateChildData(this, name).setBinaryContent(content.toByteArray(Charsets.UTF_8))
    }
}
