package dev.begeistert.pymcu.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import dev.begeistert.pymcu.project.PyMcuProjectService

/**
 * Offers a "pymcu build" configuration from the context menu inside a PyMCU
 * project (a `pyproject.toml` with `[tool.pymcu]` at the root).
 */
class PyMcuRunConfigurationProducer : LazyRunConfigurationProducer<PyMcuRunConfiguration>() {

    override fun getConfigurationFactory(): ConfigurationFactory = PyMcuRunConfigurationType.factory()

    override fun setupConfigurationFromContext(
        configuration: PyMcuRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>
    ): Boolean {
        val project = context.project
        if (!PyMcuProjectService.getInstance(project).isPyMcuProject) return false
        configuration.name = "pymcu build"
        configuration.command = "build"
        return true
    }

    override fun isConfigurationFromContext(
        configuration: PyMcuRunConfiguration,
        context: ConfigurationContext
    ): Boolean {
        if (configuration.command != "build") return false
        return PyMcuProjectService.getInstance(context.project).isPyMcuProject
    }
}
