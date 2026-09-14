package dev.begeistert.pymcu.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.jetbrains.python.psi.PyElementVisitor
import com.jetbrains.python.psi.PyIfStatement
import com.jetbrains.python.psi.PyMatchStatement
import com.jetbrains.python.psi.PyStatementList
import dev.begeistert.pymcu.resolver.ChipIdentity
import dev.begeistert.pymcu.resolver.PyMcuBranch
import dev.begeistert.pymcu.resolver.PyMcuChipCondition
import dev.begeistert.pymcu.resolver.PyMcuChipInfoService

/**
 * Greys out the `__CHIP__` branches the compiler will not build.
 *
 * Reading a HAL facade means reading six implementations of one function and
 * working out by hand which one this project gets. The conditions are right there
 * and the project's target is known, so the editor can answer it: the branches
 * for other silicon are dimmed the way dead code is, and what is left on screen
 * is what will end up in the firmware.
 *
 * This is the same evaluation that decides where Go To Declaration lands — see
 * [dev.begeistert.pymcu.resolver.PyMcuHalIndexService] — shown rather than acted
 * on, so the two can never disagree about which branch is live.
 *
 * It says nothing at all unless the file asks about `__CHIP__` or `__FREQ__`, the
 * project is a PyMCU project, and the target is known. An undecidable condition
 * dims nothing, here as everywhere else.
 */
class PyMcuDeadBranchInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        // Cheapest possible rejection: this is registered for every Python file
        // in every project, and almost none of them belong to a PyMCU build.
        // `identity()` reads the project config first, so it answers null for
        // anything that is not one without a second lookup here.
        val identity = PyMcuChipInfoService.getInstance(holder.project).identity()
            ?: return PsiElementVisitor.EMPTY_VISITOR

        return object : PyElementVisitor() {

            override fun visitPyIfStatement(statement: PyIfStatement) {
                val parts = listOf(statement.ifPart) + statement.elifParts
                val conditions = parts.map { it.condition }
                if (conditions.none { PyMcuChipCondition.mentionsTarget(it) }) return

                val elsePart = statement.elsePart
                val verdicts = PyMcuChipCondition.chainVerdicts(conditions, elsePart != null, identity)
                for ((index, part) in parts.withIndex()) {
                    dim(verdicts[index], part.statementList, identity)
                }
                if (elsePart != null) dim(verdicts.last(), elsePart.statementList, identity)
            }

            override fun visitPyMatchStatement(statement: PyMatchStatement) {
                if (!PyMcuChipCondition.mentionsTarget(statement.subject)) return
                val clauses = statement.caseClauses
                val verdicts = PyMcuChipCondition.matchVerdicts(statement.subject, clauses, identity)
                for ((index, clause) in clauses.withIndex()) {
                    dim(verdicts[index], clause.statementList, identity)
                }
            }

            private fun dim(verdict: PyMcuBranch, body: PyStatementList, target: ChipIdentity) {
                if (verdict != PyMcuBranch.DEAD) return
                holder.registerProblem(
                    body,
                    "Not compiled for ${target.chip}",
                    ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                )
            }
        }
    }
}
