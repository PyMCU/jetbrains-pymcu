package dev.begeistert.pymcu.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.fields.ExtendableTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.UIUtil
import javax.swing.JComponent
import javax.swing.JPanel

/** Settings | Tools | PyMCU. */
class PyMcuSettingsConfigurable : Configurable {

    private var executableField: TextFieldWithBrowseButton? = null
    private var packageManagerCombo: ComboBox<String>? = null
    private var lintRunCombo: ComboBox<String>? = null
    private var lintFlavorCombo: ComboBox<String>? = null
    private var lintErrorsOnlyCheck: JBCheckBox? = null
    private var offerSyncCheck: JBCheckBox? = null
    private var panel: JPanel? = null

    override fun getDisplayName(): String = "PyMCU"

    override fun createComponent(): JComponent {
        // The descriptor is built by hand rather than via FileChooserDescriptorFactory:
        // createSingleFileDescriptor() is deprecated, and its replacement (singleFile())
        // does not exist in the oldest supported build. This constructor is stable in all.
        val chooser = FileChooserDescriptor(true, false, false, false, false, false)
            .withTitle("PyMCU Executable")
            .withDescription(
                "Path to the pymcu CLI. Leave as \"pymcu\" to resolve it from the project " +
                    "virtualenv or PATH."
            )
        val executable = TextFieldWithBrowseButton(ExtendableTextField())
            .apply { addBrowseFolderListener(null, chooser) }
        executableField = executable

        val packageManager = ComboBox(PyMcuSettings.PACKAGE_MANAGERS)
        packageManagerCombo = packageManager

        val lintRun = ComboBox(PyMcuSettings.LINT_RUN_OPTIONS)
        lintRunCombo = lintRun

        val lintFlavor = ComboBox(PyMcuSettings.LINT_FLAVOR_OPTIONS)
        lintFlavorCombo = lintFlavor

        val lintErrorsOnly = JBCheckBox("Report only hard blockers (--errors-only)")
        lintErrorsOnlyCheck = lintErrorsOnly

        val offerSync = JBCheckBox("Offer to sync when a project opens with generated files missing")
        offerSyncCheck = offerSync

        val built = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("PyMCU executable:"), executable, 1, false)
            .addComponentToRightColumn(hint("Empty or \"pymcu\" resolves .venv/bin/pymcu, then PATH."))
            .addLabeledComponent(JBLabel("Package manager:"), packageManager, 1, false)
            .addSeparator()
            .addLabeledComponent(JBLabel("Run porting assistant:"), lintRun, 1, false)
            .addLabeledComponent(JBLabel("Compat flavor:"), lintFlavor, 1, false)
            .addComponentToRightColumn(hint("\"auto\" derives the flavor from the project's stdlib setting."))
            .addComponent(lintErrorsOnly)
            .addSeparator()
            .addComponent(offerSync)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        panel = built
        reset()
        return built
    }

    private fun hint(text: String): JComponent = JBLabel(text).apply {
        font = UIUtil.getFont(UIUtil.FontSize.SMALL, font)
        foreground = UIUtil.getContextHelpForeground()
    }

    override fun isModified(): Boolean {
        val settings = PyMcuSettings.getInstance()
        return executableField?.text != settings.executablePath ||
            packageManagerCombo?.selectedItem != settings.packageManager ||
            lintRunCombo?.selectedItem != settings.lintRun ||
            lintFlavorCombo?.selectedItem != settings.lintFlavor ||
            lintErrorsOnlyCheck?.isSelected != settings.lintErrorsOnly ||
            offerSyncCheck?.isSelected != settings.offerSyncOnOpen
    }

    override fun apply() {
        val settings = PyMcuSettings.getInstance()
        settings.executablePath = executableField?.text?.trim()?.takeIf { it.isNotBlank() } ?: "pymcu"
        settings.packageManager = packageManagerCombo?.selectedItem as? String ?: "uv"
        settings.lintRun = lintRunCombo?.selectedItem as? String ?: PyMcuSettings.LINT_ON_SAVE
        settings.lintFlavor = lintFlavorCombo?.selectedItem as? String ?: "auto"
        settings.lintErrorsOnly = lintErrorsOnlyCheck?.isSelected ?: false
        settings.offerSyncOnOpen = offerSyncCheck?.isSelected ?: true
    }

    override fun reset() {
        val settings = PyMcuSettings.getInstance()
        executableField?.text = settings.executablePath
        packageManagerCombo?.selectedItem = settings.packageManager
        lintRunCombo?.selectedItem = settings.lintRun
        lintFlavorCombo?.selectedItem = settings.lintFlavor
        lintErrorsOnlyCheck?.isSelected = settings.lintErrorsOnly
        offerSyncCheck?.isSelected = settings.offerSyncOnOpen
    }

    override fun disposeUIResources() {
        panel = null
        executableField = null
        packageManagerCombo = null
        lintRunCombo = null
        lintFlavorCombo = null
        lintErrorsOnlyCheck = null
        offerSyncCheck = null
    }
}
