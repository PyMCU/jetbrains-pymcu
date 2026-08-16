package dev.begeistert.pymcu.project

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.messages.Topic
import dev.begeistert.pymcu.config.PyMcuConfig
import dev.begeistert.pymcu.config.PyMcuConfigReader

/** Fired on the project message bus whenever `[tool.pymcu]` changes on disk. */
fun interface PyMcuConfigListener {
    fun configChanged(config: PyMcuConfig?)

    companion object {
        val TOPIC: Topic<PyMcuConfigListener> =
            Topic.create("PyMCU config changed", PyMcuConfigListener::class.java)
    }
}

/**
 * Single source of truth for "is this a PyMCU project, and what does it target".
 *
 * WHY a cache: [PyMcuConfigReader.findConfig] reads and parses `pyproject.toml`
 * on every call, and the callers are hot — the additional-library-roots
 * provider runs under a read action on every resolve, the status bar asks for
 * its tooltip on hover, and the run-configuration producer asks on every
 * context menu. Re-reading there showed up as visible stutter.
 *
 * The cache is invalidated by a VFS listener on `pyproject.toml`, which also
 * broadcasts [PyMcuConfigListener] so the UI refreshes without polling.
 */
@Service(Service.Level.PROJECT)
class PyMcuProjectService(private val project: Project) : Disposable {

    private val tracker = SimpleModificationTracker()

    @Volatile
    private var cached: PyMcuConfig? = null

    @Volatile
    private var cachedAtStamp: Long = -1

    init {
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.none { it.path.endsWith("pyproject.toml") }) return
                    invalidate()
                    val config = config()
                    if (!project.isDisposed) {
                        project.messageBus.syncPublisher(PyMcuConfigListener.TOPIC).configChanged(config)
                    }
                }
            }
        )
    }

    /** The parsed `[tool.pymcu]` section, or null when this is not a PyMCU project. */
    fun config(): PyMcuConfig? {
        val stamp = tracker.modificationCount
        if (stamp == cachedAtStamp) return cached
        val fresh = PyMcuConfigReader.findConfig(project)
        cached = fresh
        cachedAtStamp = stamp
        return fresh
    }

    val isPyMcuProject: Boolean get() = config() != null

    fun invalidate() {
        tracker.incModificationCount()
    }

    /**
     * The chip this project ultimately builds for, resolving a board alias
     * against the catalog when needed. May touch the CLI on a cache miss, so
     * call it off the EDT; [dev.begeistert.pymcu.cli.PyMcuBoardCatalogService]
     * falls back to a static table when the CLI is unavailable.
     */
    fun resolvedChip(catalogLookup: (String) -> String?): String? {
        val config = config() ?: return null
        config.explicitChip?.let { return it }
        return config.board?.let(catalogLookup)
    }

    override fun dispose() = Unit

    companion object {
        fun getInstance(project: Project): PyMcuProjectService =
            project.getService(PyMcuProjectService::class.java)

        /** Shorthand used by the many callers that only need the config. */
        fun config(project: Project): PyMcuConfig? = getInstance(project).config()
    }
}
