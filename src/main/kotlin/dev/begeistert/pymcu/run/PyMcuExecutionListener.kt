package dev.begeistert.pymcu.run

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.project.Project
import com.intellij.util.execution.ParametersListUtil
import com.intellij.util.messages.Topic
import dev.begeistert.pymcu.actions.PyMcuSyncTask
import dev.begeistert.pymcu.cli.SerialPorts
import dev.begeistert.pymcu.monitor.PyMcuSerialMonitor
import dev.begeistert.pymcu.monitor.PyMcuSerialMonitorAction
import dev.begeistert.pymcu.notifications.PyMcuNotifications
import dev.begeistert.pymcu.project.PyMcuProjectService
import dev.begeistert.pymcu.resolver.PyMcuLibraryRootsRefresher
import java.util.concurrent.ConcurrentHashMap

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

    /** Monitors [PyMcuSerialMonitor.pauseForFlash] emptied for an in-flight flash, by handler. */
    private val pausedMonitors = ConcurrentHashMap<
            ProcessHandler, List<PyMcuSerialMonitor.Session>>()

    override fun processStarted(
        executorId: String,
        env: ExecutionEnvironment,
        handler: ProcessHandler
    ) {
        val configuration = env.runProfile as? PyMcuRunConfiguration ?: return
        if (configuration.command != "flash") return

        // A monitor holding the port is one flash failure mode that never
        // names itself: on Windows the open is exclusive, and elsewhere the
        // monitor sits there reading bootloader bytes. Empty the port first;
        // processTerminated brings the monitor back whichever way this goes.
        val port = flashPort(configuration) ?: return
        val paused = PyMcuSerialMonitor.pauseForFlash(port)
        if (paused.isNotEmpty()) pausedMonitors[handler] = paused
    }

    override fun processTerminated(
        executorId: String,
        env: ExecutionEnvironment,
        handler: ProcessHandler,
        exitCode: Int
    ) {
        val configuration = env.runProfile as? PyMcuRunConfiguration ?: return

        pausedMonitors.remove(handler)?.forEach(PyMcuSerialMonitorAction::resume)

        when (configuration.command) {
            "build" -> onBuildFinished(exitCode)
            // clean rmtree's dist/, taking dist/_generated with it — and that is a
            // registered library root, so without this the IDE keeps resolving
            // `import board` against a directory that no longer exists.
            "sync", "stubs", "clean" -> if (exitCode == 0) PyMcuLibraryRootsRefresher.refresh(project)
            "flash" -> if (exitCode == 0) {
                // The next thing the user reaches for is almost always the
                // firmware's own output — offer it like build offers flash.
                PyMcuNotifications.info(
                    project, "PyMCU flash succeeded", "Firmware written to the board.",
                    PyMcuNotifications.action("Open Serial Monitor") {
                        PyMcuSerialMonitorAction.openInteractive(project)
                    },
                )
            } else {
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

    /**
     * The port a `flash` run will use, in the driver's own order: a `--port`/`-P`
     * argument, then `[tool.pymcu.flash] port`, then a lone detected device.
     * Null when it cannot be known — the flash then fails on its own message,
     * and no monitor had a port worth freeing anyway.
     */
    private fun flashPort(configuration: PyMcuRunConfiguration): String? =
        portFromArguments(configuration.arguments)
            ?: PyMcuProjectService.config(project)?.flash?.port
            ?: SerialPorts.list().singleOrNull()

    companion object {
        /**
         * The port a `--port`/`-P` argument names, in either spelling
         * (`--port X`, `--port=X`, `-P X`, `-PX`). Null when none was given.
         */
        internal fun portFromArguments(arguments: String): String? {
            val args = ParametersListUtil.parse(arguments)
            for ((index, arg) in args.withIndex()) {
                when {
                    arg == "--port" || arg == "-P" -> args.getOrNull(index + 1)?.let { return it }
                    arg.startsWith("--port=") -> return arg.substringAfter('=')
                    arg.startsWith("-P") && arg.length > 2 -> return arg.substring(2)
                }
            }
            return null
        }

        /** Subscribes the listener for [project]; called from the startup activity. */
        fun subscribe(project: Project) {
            project.messageBus.connect(project)
                .subscribe(ExecutionManager.EXECUTION_TOPIC, PyMcuExecutionListener(project))
        }
    }
}
