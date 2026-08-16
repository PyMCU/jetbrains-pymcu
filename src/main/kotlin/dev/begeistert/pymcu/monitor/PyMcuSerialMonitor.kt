package dev.begeistert.pymcu.monitor

import com.intellij.openapi.util.SystemInfo

/**
 * Reading what the firmware prints.
 *
 * A PyMCU program's `print()` goes out of a UART — the device and speed are
 * `stdout` and `stdout_baud` in `[tool.pymcu]`, defaulting to uart0 at 115200.
 * The plugin already parsed both and had nowhere to put them: build, flash, and
 * then leave the IDE for `screen` to see whether any of it worked.
 *
 * WHY it shells out to `stty` and `cat` instead of speaking serial itself: the
 * JVM has no serial support, so the alternative is bundling a library with
 * native binaries for every platform — a large dependency for reading bytes off
 * a character device that Unix already exposes as a file. `stty` sets the line
 * discipline, `cat` reads it, and the whole thing is inspectable in the console
 * it prints to.
 *
 * The honest limitation is Windows, where the equivalent (`mode` plus a reader)
 * is not reliable enough to ship blind, and neither of us can test it. It says
 * so rather than half-working.
 *
 * A `pymcu monitor` command in the driver would be the better long-term home —
 * the driver already knows the port, the baud and how to auto-detect a board,
 * and VS Code would get the same feature for nothing.
 */
object PyMcuSerialMonitor {

    /** What `[tool.pymcu] stdout_baud` defaults to in the driver. */
    const val DEFAULT_BAUD: Int = 115_200

    sealed interface Plan {
        data class Command(val argv: List<String>) : Plan
        data class Unsupported(val reason: String) : Plan
    }

    /**
     * The command that streams [port] at [baud], or why it cannot be built.
     *
     * `raw` stops the terminal driver from rewriting the bytes; `-echo` stops it
     * from posting back what we never send. Both matter: a firmware printing
     * `\n` without `\r` comes out as a staircase otherwise.
     */
    fun plan(
        port: String,
        baud: Int,
        windows: Boolean = SystemInfo.isWindows,
        mac: Boolean = SystemInfo.isMac,
    ): Plan {
        if (windows) {
            return Plan.Unsupported(
                "Reading a serial port is not supported on Windows yet. Use a terminal " +
                    "program such as PuTTY on $port at $baud baud."
            )
        }
        if (port.isBlank()) return Plan.Unsupported("No serial port to read from.")
        if (baud <= 0) return Plan.Unsupported("Baud rate must be a positive number.")

        // -f on macOS, -F on GNU: the same flag with a different name, and the
        // wrong one makes stty read the file as a script rather than a device.
        val deviceFlag = if (mac) "-f" else "-F"
        val quoted = shellQuote(port)
        return Plan.Command(
            listOf(
                "/bin/sh", "-c",
                "stty $deviceFlag $quoted $baud raw -echo && exec cat $quoted",
            )
        )
    }

    /** The title the console tab carries, so several boards stay distinguishable. */
    fun title(port: String, baud: Int): String = "${port.substringAfterLast('/')} · $baud baud"

    /**
     * Single-quote for `/bin/sh`. Device paths do not normally need it, but the
     * port is user-supplied and ends up inside a shell command.
     */
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
