package dev.begeistert.pymcu.resolver

import com.intellij.psi.PsiElement
import com.jetbrains.python.codeInsight.PyCustomMember
import com.jetbrains.python.psi.PyAssignmentStatement
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyFromImportStatement
import com.jetbrains.python.psi.PyFunction
import com.jetbrains.python.psi.PyIfStatement
import com.jetbrains.python.psi.PyMatchStatement
import com.jetbrains.python.psi.PyStatement
import com.jetbrains.python.psi.PyTargetExpression
import com.jetbrains.python.psi.resolve.PyResolveContext
import com.jetbrains.python.psi.types.PyOverridingModuleMembersProvider
import com.jetbrains.python.psi.types.TypeEvalContext

/**
 * Picks the live branch when the facade is reached through a qualified name.
 *
 * `pymcu.hal.pwm.PWM` never produces the candidate list the rater works on:
 * `PyFile.multiResolveName` honours Python's last-binding-wins rule and returns
 * a single element — the `PWM` of the facade's last `elif`, which is why a
 * qualified reference landed on `rp2350/pwm.py` regardless of the target.
 *
 * Before that rule runs, the platform asks each `PyOverridingModuleMembersProvider`;
 * a non-null answer wins. So for the facade shape — the same name bound more
 * than once by `from` imports under `__CHIP__` branches — this resolves every
 * binding (the rated path, so the dispatch verdict applies) and returns the best
 * candidate. When nothing separates the candidates it returns null and
 * last-binding-wins stands, which keeps ordinary modules exactly as they were.
 */
class PyMcuHalModuleMembersProvider : PyOverridingModuleMembersProvider() {

    override fun getMembersByQName(
        module: PyFile,
        qName: String,
        context: TypeEvalContext
    ): Collection<PyCustomMember> = emptyList()

    override fun resolveMember(module: PyFile, name: String, context: PyResolveContext): PsiElement? {
        if (resolving.get()) return null
        if (PyMcuChipInfoService.getInstance(module.project).identity() == null) return null

        val definers = collectImports(module.statements)
            .flatMap { it.importElements.toList() }
            .filter { it.visibleName == name }
        if (definers.size < 2) return null

        // A top-level `class PWM` or `PWM = ...` binding the same name has its
        // own last-binding-wins ordering with the imports; leave that alone.
        if (bindsNameAtTopLevel(module, name)) return null

        val candidates = try {
            resolving.set(true)
            definers.flatMap { it.multiResolveName(name) }
        } finally {
            resolving.set(false)
        }
        if (candidates.size < 2) return null

        val best = candidates.maxOf { it.rate }
        // A tie means the dispatch had no verdict for this module — leave the
        // platform's own semantics alone.
        if (candidates.all { it.rate == best }) return null
        return candidates.first { it.rate == best }.element
    }

    /**
     * `from` imports at module level plus those inside top-level `if`/`elif`/
     * `else` and `match` branches — where the HAL facades put their dispatch.
     * Imports inside classes or functions are not module members and stay out.
     */
    private fun collectImports(statements: List<PyStatement>): List<PyFromImportStatement> =
        statements.flatMap { statement ->
            when (statement) {
                is PyFromImportStatement -> listOf(statement)
                is PyIfStatement -> collectImports(
                    (listOf(statement.ifPart) + statement.elifParts)
                        .flatMap { it.statementList.statements.toList() } +
                        (statement.elsePart?.statementList?.statements?.toList() ?: emptyList())
                )
                is PyMatchStatement -> collectImports(
                    statement.caseClauses.flatMap { it.statementList.statements.toList() }
                )
                else -> emptyList()
            }
        }

    private fun bindsNameAtTopLevel(module: PyFile, name: String): Boolean =
        module.statements.any { statement ->
            when (statement) {
                is PyClass -> statement.name == name
                is PyFunction -> statement.name == name
                is PyAssignmentStatement -> statement.targets
                    .filterIsInstance<PyTargetExpression>().any { it.name == name }
                else -> false
            }
        }

    private companion object {
        /** Resolving a definer can itself consult this provider; defer to the outer pass. */
        val resolving: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }
    }
}
