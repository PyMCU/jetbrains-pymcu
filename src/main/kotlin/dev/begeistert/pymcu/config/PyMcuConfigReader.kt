package dev.begeistert.pymcu.config

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile

/**
 * Reads `[tool.pymcu]` from the `pyproject.toml` at the project base directory.
 *
 * Prefer [dev.begeistert.pymcu.project.PyMcuProjectService] over calling this
 * directly: the service caches the result and invalidates it on file changes,
 * while every call here re-reads and re-parses the file.
 */
object PyMcuConfigReader {

    fun findPyproject(project: Project): VirtualFile? {
        val basePath = project.basePath ?: return null
        return LocalFileSystem.getInstance().findFileByPath("$basePath/pyproject.toml")
    }

    fun findConfig(project: Project): PyMcuConfig? {
        val file = findPyproject(project) ?: return null
        return parseContent(String(file.contentsToByteArray(), Charsets.UTF_8))
    }

    /** Parses raw TOML. Returns null when there is no `[tool.pymcu]` section. */
    fun parseContent(content: String): PyMcuConfig? {
        val root = TomlLite.parse(content)
        val tool = root["tool"] as? Map<*, *> ?: return null
        val pymcu = tool["pymcu"] as? Map<*, *> ?: return null

        val flash = pymcu["flash"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        // [tool.pymcu.programmer] name = "..." is the pre-0.15 spelling the driver
        // still reads as a fallback; mirror that so old projects display correctly.
        val legacyProgrammer = (pymcu["programmer"] as? Map<*, *>)?.get("name")?.asString()

        return PyMcuConfig(
            target = pymcu["target"].asString(),
            board = pymcu["board"].asString(),
            chip = pymcu["chip"].asString(),
            frequency = pymcu["frequency"].asLong(),
            sources = pymcu["sources"].asString() ?: "src",
            entry = pymcu["entry"].asString() ?: "main.py",
            stdlib = (pymcu["stdlib"] as? List<*>)?.mapNotNull { it.asString() } ?: emptyList(),
            flash = PyMcuFlashConfig(
                programmer = flash["programmer"].asString() ?: legacyProgrammer,
                port = flash["port"].asString(),
                baud = flash["baud"].asLong()?.toInt(),
                fuseLow = flash["fuse_low"].asString(),
                fuseHigh = flash["fuse_high"].asString(),
                fuseExt = flash["fuse_ext"].asString(),
            ),
            stdlibPath = pymcu["stdlib_path"].asString(),
            toolchain = (pymcu["toolchain"] as? Map<*, *>)?.get("name")?.asString(),
            configWords = (pymcu["config"] as? Map<*, *>)
                ?.mapNotNull { (k, v) ->
                    val key = k?.toString() ?: return@mapNotNull null
                    val value = v.asString() ?: return@mapNotNull null
                    key to value
                }?.toMap() ?: emptyMap(),
            stdout = pymcu["stdout"].asString(),
            stdoutBaud = pymcu["stdout_baud"].asLong()?.toInt(),
            hasFfi = pymcu["ffi"] is Map<*, *>,
        )
    }

    private fun Any?.asString(): String? = when (this) {
        is String -> takeIf { it.isNotBlank() }
        null -> null
        is Map<*, *>, is List<*> -> null
        else -> toString()
    }

    private fun Any?.asLong(): Long? = when (this) {
        is Long -> this
        is Int -> toLong()
        is Double -> toLong()
        is String -> replace("_", "").trim().toLongOrNull()
        else -> null
    }
}
