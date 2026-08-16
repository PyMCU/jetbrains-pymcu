package dev.begeistert.pymcu.run

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import dev.begeistert.pymcu.actions.PyMcuSyncTask
import dev.begeistert.pymcu.notifications.PyMcuNotifications
import dev.begeistert.pymcu.resolver.PyMcuLibraryRootsRefresher

/** Fired after `pymcu install` / `uninstall` finishes, however it finished. */
fun interface PyMcuLibraryChangeListener {
    fun librariesChanged()

    companion object {
        val TOPIC: Topic<PyMcuLibraryChangeListener> =
            Topic.create("PyMCU libraries changed", PyMcuLibraryChangeListener::class.java)
    }
}

/**
 * Turns the end of a PyMCU run into the obvious next step.
 *
 * A finished build is almost always followed by a flash, and a failed one by a
 * look at the console — offering both saves a trip to the menu. This mirrors the
 * follow-up actions the VS Code extension attaches to its task-end event.
 */
class PyMcuExecutionListener(private val project: Project) : ExecutionListener {

    override fun processTerminated(
        executorId: String,
        env: ExecutionEnvironment,
        handler: ProcessHandler,
        exitCode: Int
    ) {
        val configuration = env.runProfile as? PyMcuRunConfiguration ?: return

        when (configuration.command) {
            "build" -> onBuildFinished(exitCode)
            "sync", "stubs" -> if (exitCode == 0) PyMcuLibraryRootsRefresher.refresh(project)
            "flash" -> if (exitCode != 0) {
                PyMcuNotifications.warn(
                    project, "PyMCU flash failed",
                    "Exit code $exitCode. The console output says which step failed."
                )
            }

            "install", "uninstall" -> {
                // Announce on failure too: a rejected install still rolled back,
                // and the panel's "installed" column has to reflect that.
                if (exitCode == 0) PyMcuLibraryRootsRefresher.refresh(project)
                project.messageBus.syncPublisher(PyMcuLibraryChangeListener.TOPIC).librariesChanged()
            }
        }
    }

    private fun onBuildFinished(exitCode: Int) {
        if (exitCode == 0) {
            // The build writes dist/_generated; re-index so `import board` resolves
            // even for a project that has never been synced.
            PyMcuLibraryRootsRefresher.refresh(project)
            PyMcuNotifications.info(
                project, "PyMCU build succeeded", "Firmware written to dist/.",
                PyMcuNotifications.action("Flash") {
                    PyMcuTaskRunner.run(project, "flash")
                },
            )
        } else {
            PyMcuNotifications.error(
                project, "PyMCU build failed",
                "Exit code $exitCode. Compiler diagnostics in the console link to the source.",
                PyMcuNotifications.action("Sync project") { PyMcuSyncTask.launch(project) },
            )
        }
    }

    companion object {
        /** Subscribes the listener for [project]; called from the startup activity. */
        fun subscribe(project: Project) {
            project.messageBus.connect(project)
                .subscribe(ExecutionManager.EXECUTION_TOPIC, PyMcuExecutionListener(project))
        }
    }
}
