package dev.begeistert.pymcu.monitor

import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

/**
 * Reading what the firmware prints.
 *
 * A PyMCU program's `print()` goes out of a UART — the device and speed are
 * `stdout` and `stdout_baud` in `[tool.pymcu]`, defaulting to uart0 at 115200.
 *
 * WHY the port lives in the driver: an earlier version assembled
 * `stty && cat` here, which reads bytes but can never send any, fails on
 * Windows, and duplicates the port rules `pymcu flash` already owns.
 * `pymcu monitor` does the serial work — the same auto-detection, the same
 * configured port, stdin forwarded to the board — so this file only builds the
 * command line and tracks which consoles hold which port.
 *
 * [sessions] exists for the flash path: a monitor that keeps the port open
 * blocks avrdude on Windows and watches bootloader bytes elsewhere, so
 * [pauseForFlash] empties the port before the write and [resumeAfterFlash]
 * brings the monitor back — usually in time to catch the boot messages.
 */
object PyMcuSerialMonitor {

    /** What `[tool.pymcu] stdout_baud` defaults to in the driver. */
    const val DEFAULT_BAUD: Int = 115_200

    sealed interface Plan {
        data class Command(val argv: List<String>) : Plan
        data class Unsupported(val reason: String) : Plan
    }

    /**
     * One open monitor console. [handler] is what a pause kills; the rest is
     * what a resume needs to bring the same session back.
     */
    data class Session(
        val project: Project,
        val port: String,
        val baud: Int,
        val handler: ProcessHandler,
    )

    /** Open monitor consoles, by port. Ports are machine-global; projects are not. */
    private val sessions = ConcurrentHashMap<String, Session>()

    /**
     * The `pymcu monitor` invocation for [port] at [baud], or why it cannot be
     * built. What the driver cannot do is decide for the user; what it can —
     * platform quirks, send support, Windows — is its own problem now.
     */
    fun plan(executable: String, port: String, baud: Int): Plan {
        if (port.isBlank()) return Plan.Unsupported("No serial port to read from.")
        if (baud <= 0) return Plan.Unsupported("Baud rate must be a positive number.")
        return Plan.Command(
            listOf(executable, "monitor", "--port", port, "--baud", baud.toString())
        )
    }

    /** The title the console tab carries, so several boards stay distinguishable. */
    fun title(port: String, baud: Int): String = "${port.substringAfterLast('/')} · $baud baud"

    // ── sessions ────────────────────────────────────────────────────────────

    fun register(session: Session) {
        sessions[session.port] = session
    }

    /** Drop [session] if it is still the one registered for its port. */
    fun unregister(session: Session) {
        sessions.remove(session.port, session)
    }

    /**
     * Kill every monitor holding [port] — it is about to belong to the
     * programmer — and return the sessions so [resumeAfterFlash] can bring
     * them back. Returns an empty list when nothing was listening.
     */
    fun pauseForFlash(port: String): List<Session> {
        val paused = mutableListOf<Session>()
        sessions.remove(port)?.let { session ->
            session.handler.destroyProcess()
            paused += session
        }
        return paused
    }

    /** Visible for tests. */
    fun sessionOn(port: String): Session? = sessions[port]
}
