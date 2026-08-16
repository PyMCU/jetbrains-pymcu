package dev.begeistert.pymcu.venv

import java.io.File
import java.nio.file.Path
import kotlin.io.path.exists

/** Locates the project virtualenv the driver itself re-execs into. */
object PyMcuVenv {

    /** `<project>/.venv/lib/pythonX.Y/site-packages`, or the Windows equivalent. */
    fun sitePackages(basePath: String): Path? {
        for (venvName in listOf(".venv", "venv")) {
            val venv = File(basePath, venvName)
            if (!venv.isDirectory) continue

            // Windows: <venv>/Lib/site-packages, no interpreter version in the path.
            val windows = venv.resolve("Lib/site-packages").toPath()
            if (windows.exists()) return windows

            val libDir = venv.resolve("lib")
            val pythonDir = libDir.listFiles()?.firstOrNull { it.name.startsWith("python") } ?: continue
            val sitePackages = pythonDir.resolve("site-packages").toPath()
            if (sitePackages.exists()) return sitePackages
        }
        return null
    }
}
