package dev.begeistert.pymcu.startup

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.begeistert.pymcu.actions.PyMcuSyncTask
import dev.begeistert.pymcu.venv.PyMcuVenv
import dev.begeistert.pymcu.cli.PyMcuBoardCatalogService
import dev.begeistert.pymcu.cli.PyMcuCli
import dev.begeistert.pymcu.notifications.PyMcuNotifications
import dev.begeistert.pymcu.project.PyMcuProjectService
import dev.begeistert.pymcu.resolver.PyMcuLibraryRootsRefresher
import dev.begeistert.pymcu.run.PyMcuExecutionListener
import dev.begeistert.pymcu.settings.PyMcuSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Project-open hook for PyMCU projects.
 *
 * WHY it no longer runs `uv sync` unconditionally: the previous version shelled
 * out to the package manager on every project open, off any progress indicator
 * and with no way to decline. Opening a project should not mutate a virtualenv.
 * It now checks what is missing and *offers* the sync, running it under a
 * cancellable background task when the user accepts.
 *
 * [ProjectActivity] is the current API; the plugin previously used an
 * application-level `ProjectManagerListener`, which is deprecated for this use
 * and fires before the project's services are usable.
 */
class PyMcuStartupActivity : ProjectActivity {

    private val log = Logger.getInstance(PyMcuStartupActivity::class.java)

    override suspend fun execute(project: Project) {
        val config = PyMcuProjectService.getInstance(project).config() ?: return
        log.info("PyMCU project detected: ${config.targetLabel ?: "(no target)"}")

        PyMcuExecutionListener.subscribe(project)

        withContext(Dispatchers.IO) {
            // Probe the CLI before anything that shells out, so a machine without
            // pymcu installed waits on one short timeout rather than several.
            if (!PyMcuCli.isAvailable(project)) {
                warnCliMissing(project)
                return@withContext
            }

            // Warm the board catalog so the status bar and the configure dialog
            // do not have to block on the CLI the first time they are used.
            PyMcuBoardCatalogService.getInstance(project).get()

            PyMcuLibraryRootsRefresher.refresh(project)
            offerSyncIfNeeded(project)
        }
    }

    private fun warnCliMissing(project: Project) {
        PyMcuNotifications.warn(
            project,
            "PyMCU CLI not found",
            "The <code>pymcu</code> executable could not be run. Install it with " +
                "<code>pipx install pymcu-compiler</code>, or set the path in Settings | Tools | PyMCU.",
            PyMcuNotifications.action("Installation guide") {
                BrowserUtil.browse("https://github.com/PyMCU/PyMCU#readme")
            },
        )
    }

    /**
     * Offers a sync when the generated support files are absent — the state a
     * freshly cloned project is in, where `import board` and the compat imports
     * would otherwise show up unresolved.
     */
    private fun offerSyncIfNeeded(project: Project) {
        if (!PyMcuSettings.getInstance().offerSyncOnOpen) return
        val basePath = project.basePath ?: return
        // The compat layer being installed is what makes the imports resolve.
        val flavor = PyMcuProjectService.config(project)?.flavor
        val compatInstalled = flavor == null ||
            PyMcuVenv.sitePackages(basePath)?.resolve("pymcu_$flavor")?.toFile()?.isDirectory == true
        if (compatInstalled && File(basePath, "dist/_generated").isDirectory) return

        PyMcuNotifications.info(
            project,
            "PyMCU project",
            "Dependencies or generated files are missing, so the compat imports will not " +
                "resolve. Sync installs them and regenerates the board module.",
            PyMcuNotifications.action("Sync now") { PyMcuSyncTask.launch(project) },
            PyMcuNotifications.action("Don't ask again") {
                PyMcuSettings.getInstance().offerSyncOnOpen = false
            },
        )
    }
}
