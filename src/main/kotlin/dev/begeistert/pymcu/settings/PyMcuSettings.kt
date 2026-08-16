package dev.begeistert.pymcu.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Application-level plugin settings, stored in `pymcu.xml` in the IDE config
 * directory. Mirrors the `pymcu.*` keys of the VS Code extension so the two
 * behave the same when both are installed.
 */
@Service(Service.Level.APP)
@State(name = "PyMcuSettings", storages = [Storage("pymcu.xml")])
class PyMcuSettings : PersistentStateComponent<PyMcuSettings> {

    /** Path to the `pymcu` executable. Bare name means "resolve via PATH / .venv". */
    var executablePath: String = "pymcu"

    /** Package manager used for dependency sync: uv, pip, poetry or pipenv. */
    var packageManager: String = "uv"

    /** When the porting assistant runs: [LINT_ON_SAVE], [LINT_ON_TYPE] or [LINT_OFF]. */
    var lintRun: String = LINT_ON_SAVE

    /** Compat flavor for the porting assistant: auto, micropython or circuitpython. */
    var lintFlavor: String = "auto"

    /** Report only hard blockers, hiding warnings and informational findings. */
    var lintErrorsOnly: Boolean = false

    /** Offer to sync when a project opens with its generated files missing. */
    var offerSyncOnOpen: Boolean = true

    override fun getState(): PyMcuSettings = this

    override fun loadState(state: PyMcuSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        const val LINT_ON_SAVE = "onSave"
        const val LINT_ON_TYPE = "onType"
        const val LINT_OFF = "off"

        val LINT_RUN_OPTIONS = arrayOf(LINT_ON_SAVE, LINT_ON_TYPE, LINT_OFF)
        val LINT_FLAVOR_OPTIONS = arrayOf("auto", "micropython", "circuitpython")
        val PACKAGE_MANAGERS = arrayOf("uv", "pip", "poetry", "pipenv")

        fun getInstance(): PyMcuSettings =
            ApplicationManager.getApplication().getService(PyMcuSettings::class.java)
    }
}
