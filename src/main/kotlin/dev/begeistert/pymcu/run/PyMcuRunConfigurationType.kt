package dev.begeistert.pymcu.run

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.project.Project
import dev.begeistert.pymcu.PyMcuIcons
import javax.swing.Icon

/** Registers the "PyMCU" run configuration type with the run/debug framework. */
class PyMcuRunConfigurationType : ConfigurationType {

    private val factory = object : ConfigurationFactory(this) {
        override fun getId(): String = "PyMcuConfigurationFactory"

        override fun createTemplateConfiguration(project: Project): RunConfiguration =
            PyMcuRunConfiguration(project, this, "PyMCU")

        override fun getName(): String = "PyMCU"
    }

    override fun getDisplayName(): String = "PyMCU"

    override fun getConfigurationTypeDescription(): String =
        "Run pymcu build, flash, clean, sync or stubs"

    override fun getIcon(): Icon = PyMcuIcons.PyMcu

    override fun getId(): String = "PyMcuRunConfiguration"

    override fun getConfigurationFactories(): Array<ConfigurationFactory> = arrayOf(factory)

    companion object {
        /**
         * The registered instance. Constructing a new [PyMcuRunConfigurationType]
         * would hand out a factory that is not the one the platform registered,
         * so configurations produced from it fail to round-trip.
         */
        fun instance(): PyMcuRunConfigurationType =
            ConfigurationTypeUtil.findConfigurationType(PyMcuRunConfigurationType::class.java)

        fun factory(): ConfigurationFactory = instance().configurationFactories.first()
    }
}
