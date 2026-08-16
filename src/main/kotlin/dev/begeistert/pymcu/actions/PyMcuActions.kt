package dev.begeistert.pymcu.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import dev.begeistert.pymcu.cli.PyMcuBoardCatalogService
import dev.begeistert.pymcu.cli.PyMcuCli
import dev.begeistert.pymcu.cli.SerialPorts
import dev.begeistert.pymcu.configure.PyMcuConfigureDialog
import dev.begeistert.pymcu.lint.PyMcuLintRunner
import dev.begeistert.pymcu.notifications.PyMcuNotifications
import dev.begeistert.pymcu.project.PyMcuProjectService
import dev.begeistert.pymcu.run.PyMcuTaskRunner
import dev.begeistert.pymcu.venv.PyMcuInterpreter
import javax.swing.Icon

/**
 * Base for every PyMCU action: they all need a project that actually is one,
 * and they all resolve their enabled state off the EDT-safe cached config.
 */
abstract class PyMcuAction(text: String, description: String, icon: Icon?) :
    AnAction(text, description, icon), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible =
            project != null && PyMcuProjectService.getInstance(project).isPyMcuProject
    }

    final override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        perform(project)
    }

    protected abstract fun perform(project: Project)
}

// ── build / clean ────────────────────────────────────────────────────────────

class PyMcuBuildAction : PyMcuAction("Build", "Compile the project (pymcu build)", AllIcons.Actions.Compile) {
    override fun perform(project: Project) = PyMcuTaskRunner.run(project, "build")
}

class PyMcuCleanAction : PyMcuAction("Clean", "Remove build artifacts (pymcu clean)", AllIcons.Actions.GC) {
    override fun perform(project: Project) = PyMcuTaskRunner.run(project, "clean")
}

class PyMcuBuildExplainAction : PyMcuAction(
    "Build and Explain",
    "Compile and list the setup the compiler injected implicitly (pymcu build --explain)",
    AllIcons.Actions.Show
) {
    override fun perform(project: Project) = PyMcuTaskRunner.run(project, "build", listOf("--explain"))
}

// ── flash ────────────────────────────────────────────────────────────────────

/**
 * Flashes the firmware, asking which port to use when the project has not
 * pinned one — the driver refuses to guess when several boards are connected,
 * so answering here is faster than reading the error and editing the TOML.
 */
class PyMcuFlashAction : PyMcuAction("Flash", "Write the firmware to the board (pymcu flash)", AllIcons.Actions.Execute) {

    override fun perform(project: Project) {
        val config = PyMcuProjectService.config(project)
        if (config?.flash?.port != null) {
            PyMcuTaskRunner.run(project, "flash")
            return
        }

        val ports = SerialPorts.list()
        if (ports.isEmpty()) {
            PyMcuTaskRunner.run(project, "flash")
            return
        }

        val choices = ports + AUTO_DETECT
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(choices)
            .setTitle("Serial Port to Flash")
            .setItemChosenCallback { chosen ->
                val args = if (chosen == AUTO_DETECT) emptyList() else listOf("--port", chosen)
                PyMcuTaskRunner.run(project, "flash", args)
            }
            .createPopup()
            .showCenteredInCurrentWindow(project)
    }

    private companion object {
        const val AUTO_DETECT = "Let pymcu auto-detect"
    }
}

// ── sync ─────────────────────────────────────────────────────────────────────

/**
 * Installs dependencies with the configured package manager, then runs
 * `pymcu sync` so `dist/_generated/board.py` matches the configured board.
 *
 * It deliberately does not generate `.pyi` stubs: the IDE resolves to the
 * installed sources, which is what makes the stdlib readable. See
 * [dev.begeistert.pymcu.resolver.PyMcuImportResolver].
 */
class PyMcuSyncAction : PyMcuAction("Sync Project", "Install dependencies and regenerate IDE support files", AllIcons.Actions.Refresh) {
    override fun perform(project: Project) = PyMcuSyncTask.launch(project)
}

object PyMcuSyncTask {

    fun launch(project: Project, packageManager: String? = null) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Syncing PyMCU project", true) {
            override fun run(indicator: ProgressIndicator) = execute(project, indicator, packageManager)
        })
    }

    /**
     * Blocking. Safe to call from any background thread.
     *
     * [packageManager] overrides both the project's own evidence and the
     * configured default — the New Project wizard passes what the user just
     * chose, which nothing on disk can show yet.
     */
    fun execute(project: Project, indicator: ProgressIndicator?, packageManager: String? = null) {
        val basePath = project.basePath ?: return
        val command = PyMcuCli.syncCommand(basePath, packageManager)

        indicator?.text = "Installing dependencies (${command.joinToString(" ")})…"
        val install = PyMcuCli.runIn(basePath, command.first(), command.drop(1), timeoutMs = 300_000)
        if (!install.started) {
            PyMcuNotifications.warn(
                project, "PyMCU sync",
                "Could not run `${command.joinToString(" ")}` — is ${command.first()} installed? " +
                    "The package manager is set in Settings | Tools | PyMCU."
            )
            return
        }

        indicator?.text = "Generating board module (pymcu sync)…"
        PyMcuCli.run(project, "sync", timeoutMs = 120_000)

        // Before refreshing indexes: without an interpreter there is nothing for
        // the index to resolve against, and the wizard's generator cannot set one
        // because a plain DirectoryProjectGenerator has no interpreter step.
        indicator?.text = "Setting the project interpreter…"
        PyMcuInterpreter.configureIfNeeded(project)

        indicator?.text = "Refreshing indexes…"
        refreshGeneratedRoots(project, basePath)
        PyMcuBoardCatalogService.getInstance(project).invalidate()

        if (install.ok) {
            PyMcuNotifications.info(project, "PyMCU sync", "Project synced.")
        } else {
            PyMcuNotifications.warn(
                project, "PyMCU sync",
                "Dependency install exited with code ${install.exitCode}. " +
                    "Generated files were refreshed anyway."
            )
        }
    }

    private fun refreshGeneratedRoots(project: Project, basePath: String) {
        val lfs = LocalFileSystem.getInstance()
        val generated = lfs.refreshAndFindFileByPath("$basePath/dist/_generated")
        generated?.refresh(false, true)
        dev.begeistert.pymcu.resolver.PyMcuLibraryRootsRefresher.refresh(project)
    }
}

// ── porting assistant ────────────────────────────────────────────────────────

class PyMcuLintProjectAction : PyMcuAction(
    "Lint Project (Porting Assistant)",
    "Run pymcu lint over the project sources",
    AllIcons.Actions.Lightning
) {
    override fun perform(project: Project) = PyMcuLintRunner.lintProject(project)
}

// ── configuration ────────────────────────────────────────────────────────────

class PyMcuConfigureAction : PyMcuAction(
    "Configure Project…",
    "Board, clock, compat stdlib, programmer, port and fuses",
    AllIcons.General.Settings
) {
    override fun perform(project: Project) = PyMcuConfigureDialog.show(project)
}

// ── stubs ────────────────────────────────────────────────────────────────────

/**
 * Exports `.pyi` stubs for tooling *outside* the IDE — mypy or pyright in CI,
 * say, which cannot see through the compat layers on their own.
 *
 * The editor does not read them: it resolves to the installed sources, so the
 * implementation stays one click away. Nothing here is needed for completion.
 */
class PyMcuExportStubsAction : PyMcuAction(
    "Export Type Stubs…",
    "Run pymcu stubs, for type checkers outside the IDE",
    AllIcons.ToolbarDecorator.Export
) {
    override fun perform(project: Project) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Exporting PyMCU stubs", true) {
            override fun run(indicator: ProgressIndicator) {
                val result = PyMcuCli.run(
                    project, "stubs", "--out", STUBS_DIR, "--remap-types", timeoutMs = 120_000
                )
                project.basePath?.let {
                    LocalFileSystem.getInstance().refreshAndFindFileByPath("$it/$STUBS_DIR")
                }
                if (result.ok) {
                    PyMcuNotifications.info(
                        project, "PyMCU stubs",
                        "Written to $STUBS_DIR. The editor keeps resolving to the sources; " +
                            "point your type checker at this tree."
                    )
                } else {
                    PyMcuNotifications.warn(
                        project, "PyMCU stubs",
                        "`pymcu stubs` failed: ${(result.stderr + result.stdout).trim().takeLast(400)}"
                    )
                }
            }
        })
    }

    private companion object {
        /** Under dist/, so it is already gitignored and never indexed. */
        const val STUBS_DIR = "dist/_generated/stubs"
    }
}
