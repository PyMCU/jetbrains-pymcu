package dev.begeistert.pymcu.statusbar

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.Consumer
import dev.begeistert.pymcu.cli.PyMcuBoardCatalogService
import dev.begeistert.pymcu.config.PyMcuConfig
import dev.begeistert.pymcu.configure.PyMcuConfigureDialog
import dev.begeistert.pymcu.project.PyMcuConfigListener
import dev.begeistert.pymcu.project.PyMcuProjectService
import java.awt.event.MouseEvent

private const val WIDGET_ID = "PyMcuStatusBarWidget"

/**
 * Shows the target the project builds for, e.g. "⚙ arduino_uno (AVR)".
 * Clicking it opens the project configuration dialog — the same one-click path
 * to "change my board" the VS Code status bar item offers.
 */
class PyMcuStatusBarWidget(private val project: Project) :
    StatusBarWidget, StatusBarWidget.TextPresentation {

    private var statusBar: StatusBar? = null

    @Volatile
    private var text: String = ""

    init {
        // Connecting to `this` ties the subscription to the widget's lifetime;
        // the previous unparented connect() leaked a listener per project open.
        project.messageBus.connect(this).subscribe(PyMcuConfigListener.TOPIC, PyMcuConfigListener {
            refresh()
        })
        refresh()
    }

    override fun ID(): String = WIDGET_ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
    }

    override fun dispose() {
        statusBar = null
    }

    // ── presentation ─────────────────────────────────────────────────────────

    override fun getText(): String = text

    override fun getAlignment(): Float = 0.5f

    override fun getTooltipText(): String {
        val config = PyMcuProjectService.config(project) ?: return "No PyMCU project detected"
        val chip = resolveChip(config)
        return buildString {
            append("PyMCU target: ")
            append(config.board?.let { board -> chip?.let { "$board ($it)" } ?: board } ?: chip ?: "not set")
            config.frequency?.let { append(" @ $it Hz") }
            config.flavor?.let { append(" · $it compat") }
            config.flash.port?.let { append(" · port $it") }
            if (config.hasFfi) append(" · C/C++ FFI")
            append(" — click to configure")
        }
    }

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer {
        PyMcuConfigureDialog.show(project)
    }

    // ── state ────────────────────────────────────────────────────────────────

    private fun refresh() {
        val config = PyMcuProjectService.config(project)
        text = config?.let(::describe).orEmpty()
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) statusBar?.updateWidget(WIDGET_ID)
        }
    }

    private fun describe(config: PyMcuConfig): String {
        val chip = resolveChip(config)
        val label = config.board ?: chip ?: "no target"
        val architecture = config.architecture(chip)
        return if (architecture != null) "⚙ $label ($architecture)" else "⚙ $label"
    }

    /** Board aliases need the catalog; use whatever is cached rather than blocking the EDT. */
    private fun resolveChip(config: PyMcuConfig): String? =
        config.explicitChip
            ?: config.board?.let { PyMcuBoardCatalogService.getInstance(project).cachedOrFallback().chipOf(it) }
}

class PyMcuStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = WIDGET_ID

    override fun getDisplayName(): String = "PyMCU Target"

    override fun isAvailable(project: Project): Boolean =
        PyMcuProjectService.getInstance(project).isPyMcuProject

    override fun createWidget(project: Project): StatusBarWidget = PyMcuStatusBarWidget(project)

    /** Disposer.dispose, not widget.dispose(): the widget's message-bus
     *  connection is registered as its child and only that path releases it. */
    override fun disposeWidget(widget: StatusBarWidget) = Disposer.dispose(widget)

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}
