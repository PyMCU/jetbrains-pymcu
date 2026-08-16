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
 * `pymcu sync` so `dist/_generated/board.py` matches the configured board, and
 * regenerates the IDE stubs.
 */
class PyMcuSyncAction : PyMcuAction("Sync Project", "Install dependencies and regenerate IDE support files", AllIcons.Actions.Refresh) {
    override fun perform(project: Project) = PyMcuSyncTask.launch(project)
}

object PyMcuSyncTask {

    fun launch(project: Project) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Syncing PyMCU project", true) {
            override fun run(indicator: ProgressIndicator) = execute(project, indicator)
        })
    }

    /** Blocking. Safe to call from any background thread. */
    fun execute(project: Project, indicator: ProgressIndicator?) {
        val basePath = project.basePath ?: return
        val packageManager = PyMcuCli.syncCommand()

        indicator?.text = "Installing dependencies (${packageManager.joinToString(" ")})…"
        val install = PyMcuCli.runIn(basePath, packageManager.first(), packageManager.drop(1), timeoutMs = 300_000)
        if (!install.started) {
            PyMcuNotifications.warn(
                project, "PyMCU sync",
                "Could not run `${packageManager.joinToString(" ")}`. Check the package manager in " +
                    "Settings | Tools | PyMCU."
            )
            return
        }

        indicator?.text = "Generating board module (pymcu sync)…"
        PyMcuCli.run(project, "sync", timeoutMs = 120_000)

        indicator?.text = "Generating IDE stubs (pymcu stubs)…"
        val stubs = PyMcuCli.run(project, "stubs", "--out", STUBS_DIR, "--remap-types", timeoutMs = 120_000)

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
        if (!stubs.ok && stubs.started) {
            // A CLI older than `pymcu stubs` is a normal situation, not an error.
            PyMcuNotifications.info(
                project, "PyMCU stubs",
                "This pymcu version has no `stubs` command — IDE resolution falls back to the " +
                    "installed sources. Upgrade with `pymcu upgrade` for typed completions."
            )
        }
    }

    /** Where `pymcu stubs` writes, and what the library-roots provider indexes. */
    const val STUBS_DIR: String = "dist/_generated/stubs"

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

class PyMcuRegenerateStubsAction : PyMcuAction(
    "Regenerate IDE Stubs",
    "Run pymcu stubs and re-index the generated .pyi tree",
    AllIcons.Actions.ForceRefresh
) {
    override fun perform(project: Project) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Generating PyMCU stubs", true) {
            override fun run(indicator: ProgressIndicator) {
                val result = PyMcuCli.run(
                    project, "stubs", "--out", PyMcuSyncTask.STUBS_DIR, "--remap-types",
                    timeoutMs = 120_000
                )
                val basePath = project.basePath
                if (basePath != null) {
                    LocalFileSystem.getInstance()
                        .refreshAndFindFileByPath("$basePath/${PyMcuSyncTask.STUBS_DIR}")
                        ?.refresh(false, true)
                }
                dev.begeistert.pymcu.resolver.PyMcuLibraryRootsRefresher.refresh(project)
                if (result.ok) {
                    PyMcuNotifications.info(project, "PyMCU stubs", result.stdout.trim().ifEmpty { "Stubs generated." })
                } else {
                    PyMcuNotifications.warn(
                        project, "PyMCU stubs",
                        "`pymcu stubs` failed: ${(result.stderr + result.stdout).trim().takeLast(400)}"
                    )
                }
            }
        })
    }
}
