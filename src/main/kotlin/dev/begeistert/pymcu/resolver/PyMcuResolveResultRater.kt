package dev.begeistert.pymcu.resolver

import com.intellij.psi.PsiElement
import com.jetbrains.python.psi.impl.PyResolveResultRater
import com.jetbrains.python.psi.types.PyType
import com.jetbrains.python.psi.types.TypeEvalContext

/**
 * Prefers the HAL implementation for the architecture the project targets.
 *
 * `from pymcu.hal.gpio import Pin` resolves to six candidates — one per
 * architecture branch of the facade — because an IDE cannot evaluate
 * `__CHIP__.arch == "avr"`. Go To Declaration then asks which of six the user
 * meant, when five of them are for silicon the project will never be compiled
 * for. The project's own configuration answers that question, so this rates the
 * live one up and the rest down.
 *
 * See [PyMcuHalDispatch] for how "live" is decided, and why the rule is
 * deliberately conservative.
 */
class PyMcuResolveResultRater : PyResolveResultRater {

    override fun getImportElementRate(target: PsiElement): Int = rate(target)

    override fun getMemberRate(
        member: PsiElement?,
        type: PyType?,
        context: TypeEvalContext?
    ): Int = if (member == null) PyMcuHalDispatch.NEUTRAL else rate(member)

    private fun rate(element: PsiElement): Int {
        val path = element.containingFile?.virtualFile?.path ?: return PyMcuHalDispatch.NEUTRAL

        // Cheapest possible rejection: this runs on every rated resolve in every
        // Python project, and almost none of them are under a PyMCU HAL.
        val directory = PyMcuHalDispatch.architectureDirectoryOf(path)
            ?: return PyMcuHalDispatch.NEUTRAL

        val identity = PyMcuChipInfoService.getInstance(element.project).identity()
            ?: return PyMcuHalDispatch.NEUTRAL

        return PyMcuHalDispatch.rate(directory, identity.chip, identity.arch)
    }
}
