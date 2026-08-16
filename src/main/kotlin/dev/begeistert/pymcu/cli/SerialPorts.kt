package dev.begeistert.pymcu.cli

import com.intellij.openapi.util.SystemInfo
import java.io.File

/**
 * USB-serial devices that look like a connected board.
 *
 * Deliberately naive — this only feeds a picker and a completion list, and the
 * driver does its own detection when no port is given. Windows has no `/dev`
 * equivalent to scan without native calls, so the field stays free-text there.
 */
object SerialPorts {

    fun list(): List<String> {
        if (!SystemInfo.isMac && !SystemInfo.isLinux) return emptyList()
        val prefixes = if (SystemInfo.isMac)
            listOf("cu.usbmodem", "cu.usbserial", "cu.SLAB", "cu.wchusbserial")
        else
            listOf("ttyACM", "ttyUSB")

        return try {
            File("/dev").list()
                ?.filter { name -> prefixes.any(name::startsWith) }
                ?.sorted()
                ?.map { "/dev/$it" }
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
