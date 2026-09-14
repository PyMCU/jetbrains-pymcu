package dev.begeistert.pymcu.resolver

import com.intellij.psi.tree.IElementType
import com.jetbrains.python.PyTokenTypes
import com.jetbrains.python.psi.PyBinaryExpression
import com.jetbrains.python.psi.PyBoolLiteralExpression
import com.jetbrains.python.psi.PyCaseClause
import com.jetbrains.python.psi.PyCapturePattern
import com.jetbrains.python.psi.PyExpression
import com.jetbrains.python.psi.PyGroupPattern
import com.jetbrains.python.psi.PyLiteralPattern
import com.jetbrains.python.psi.PyNumericLiteralExpression
import com.jetbrains.python.psi.PyOrPattern
import com.jetbrains.python.psi.PyParenthesizedExpression
import com.jetbrains.python.psi.PyPattern
import com.jetbrains.python.psi.PyPrefixExpression
import com.jetbrains.python.psi.PyReferenceExpression
import com.jetbrains.python.psi.PySequenceExpression
import com.jetbrains.python.psi.PyStringLiteralExpression
import com.jetbrains.python.psi.PyWildcardPattern

/** Whether a branch is the one the compiler keeps. */
enum class PyMcuBranch {
    /** The compiler compiles this branch for the project's target. */
    LIVE,

    /** The compiler drops this branch: an earlier one won, or the condition is false. */
    DEAD,

    /** Nothing here can be decided from the target alone. */
    UNKNOWN,
    ;

    /** A branch nested inside another is live only when both are. */
    fun and(inner: PyMcuBranch): PyMcuBranch = when {
        this == DEAD || inner == DEAD -> DEAD
        this == UNKNOWN || inner == UNKNOWN -> UNKNOWN
        else -> LIVE
    }
}

/**
 * Evaluates the compile-time conditions the stdlib dispatches on.
 *
 * Every HAL facade picks its implementation by asking the target about itself:
 *
 * ```python
 * if __CHIP__.name == "attiny2313" or __CHIP__.name == "attiny4313":
 *     from pymcu.hal.avr.uart.attiny2313 import uart_write
 * elif __CHIP__.arch == "avr":
 *     from pymcu.hal.avr.uart.avr import uart_write
 * ```
 *
 * The compiler substitutes the target and keeps one branch. `pymcu/chips/__init__.py`
 * says outright that the `.name` / `.arch` / `.board` fields exist so that IDEs can
 * do the same, and this is the plugin doing it.
 *
 * Three-valued on purpose. `null` means "not decidable here", which has to stay
 * distinct from `false`: in a chain whose second condition cannot be read, the
 * branches after it are not dead either, and demoting them anyway would hide the
 * very code the user asked to see.
 *
 * Comparisons are case-insensitive. Python's are not, but a chip id can arrive
 * from a board catalog spelling it `ATmega328P` while the stdlib writes
 * `atmega328p`, and being strict there costs a user the one branch that was right.
 */
object PyMcuChipCondition {

    private const val CHIP = "__CHIP__"
    private const val FREQ = "__FREQ__"

    /** `__CHIP__` fields this can answer. Anything else evaluates to null. */
    private val STRING_FIELDS = setOf("name", "arch", "board")

    private val ORDERING = setOf(
        PyTokenTypes.EQEQ, PyTokenTypes.NE,
        PyTokenTypes.LT, PyTokenTypes.LE, PyTokenTypes.GT, PyTokenTypes.GE,
    )

    /**
     * True / false / null for [expression] against [target].
     *
     * Handles what the stdlib actually writes — `==`, `!=`, `and`, `or`, `not`,
     * parentheses, `in` over a literal sequence, and the ordering operators for
     * `__FREQ__` — and answers null for everything else.
     */
    fun evaluate(expression: PyExpression?, target: ChipIdentity): Boolean? = when (expression) {
        null -> null
        is PyParenthesizedExpression -> evaluate(expression.containedExpression, target)
        is PyPrefixExpression -> evaluatePrefix(expression, target)
        is PyBinaryExpression -> evaluateBinary(expression, target)
        is PyBoolLiteralExpression -> expression.value
        else -> null
    }

    /**
     * True when [expression] asks about the target at all.
     *
     * A conditional that never mentions `__CHIP__` or `__FREQ__` is ordinary
     * Python and none of the plugin's business however it evaluates. This is what
     * keeps [PyMcuHalIndexService] to the compile-time dispatch and out of the
     * rest of the stdlib.
     */
    fun mentionsTarget(expression: PyExpression?): Boolean {
        val text = expression?.text ?: return false
        return text.contains(CHIP) || text.contains(FREQ)
    }

    /**
     * The verdict for each part of one `if` / `elif` / `else` chain, in order,
     * with a final entry for the `else` when [hasElse].
     *
     * A part is live only when its own condition is true *and* every condition
     * before it is definitely false — which is what `elif` means, and why one
     * unreadable condition makes every later part unknown rather than dead.
     */
    fun chainVerdicts(
        conditions: List<PyExpression?>,
        hasElse: Boolean,
        target: ChipIdentity,
    ): List<PyMcuBranch> = verdicts(conditions.map { evaluate(it, target) }, hasElse)

    /**
     * The verdict for each `case` of a `match __CHIP__.<field>:`, in order.
     *
     * The stdlib's second dispatch shape, and the one that decides which of
     * `pic16f877a_pwm` and `pic16f18877_pwm` a PIC project's `pwm_init` means:
     *
     * ```python
     * match __CHIP__.name:
     *     case "pic16f18877":
     *         from pymcu.hal.pic14.pic16f18877_pwm import pwm_init
     *     case _:
     *         from pymcu.hal.pic14.pic16f877a_pwm import pwm_init
     * ```
     *
     * A `case _` is treated as a pattern that always matches, so it is live
     * exactly when no earlier case is — which is also how `case x:` behaves, and
     * why neither needs a separate rule. A case carrying a guard, or a pattern
     * richer than a literal or an alternation of them, reads as undecidable and
     * makes every case after it undecidable too.
     */
    fun matchVerdicts(
        subject: PyExpression?,
        clauses: List<PyCaseClause>,
        target: ChipIdentity,
    ): List<PyMcuBranch> {
        val value = targetValueOf(subject, target)
        return verdicts(
            clauses.map { clause ->
                when {
                    value == null -> null
                    clause.guardCondition != null -> null
                    else -> matches(clause.pattern, value)
                }
            },
            hasElse = false,
        )
    }

    /**
     * The target's own value for `__CHIP__.name` / `.arch` / `.board`, or null
     * when [expression] asks about something else.
     */
    fun targetValueOf(expression: PyExpression?, target: ChipIdentity): String? =
        stringValueOf(expression, target)

    /**
     * One pass over a chain of conditions already reduced to true / false / null.
     *
     * A part is live only when its own condition is true *and* every condition
     * before it is definitely false — which is what `elif` means, and why one
     * unreadable condition makes every later part unknown rather than dead.
     */
    private fun verdicts(values: List<Boolean?>, hasElse: Boolean): List<PyMcuBranch> {
        val verdicts = ArrayList<PyMcuBranch>(values.size + 1)
        var decided = false      // an earlier condition is definitely true
        var allFalse = true      // every earlier condition is definitely false

        for (value in values) {
            verdicts += when {
                decided -> PyMcuBranch.DEAD
                !allFalse -> PyMcuBranch.UNKNOWN
                value == true -> PyMcuBranch.LIVE
                value == false -> PyMcuBranch.DEAD
                else -> PyMcuBranch.UNKNOWN
            }
            if (value == true) decided = true
            if (value != false) allFalse = false
        }

        if (hasElse) {
            verdicts += when {
                decided -> PyMcuBranch.DEAD
                allFalse -> PyMcuBranch.LIVE
                else -> PyMcuBranch.UNKNOWN
            }
        }
        return verdicts
    }

    /** Whether [pattern] matches the string [value]: true, false, or undecidable. */
    private fun matches(pattern: PyPattern?, value: String): Boolean? = when (pattern) {
        is PyWildcardPattern, is PyCapturePattern -> true
        is PyGroupPattern -> matches(pattern.pattern, value)
        is PyOrPattern -> {
            val alternatives = pattern.alternatives.map { matches(it, value) }
            when {
                alternatives.any { it == true } -> true
                alternatives.all { it == false } -> false
                else -> null
            }
        }
        is PyLiteralPattern ->
            (pattern.expression as? PyStringLiteralExpression)
                ?.stringValue
                ?.equals(value, ignoreCase = true)
        else -> null
    }

    // ── expression shapes ────────────────────────────────────────────────────

    private fun evaluatePrefix(expression: PyPrefixExpression, target: ChipIdentity): Boolean? {
        if (expression.operator != PyTokenTypes.NOT_KEYWORD) return null
        return evaluate(expression.operand, target)?.not()
    }

    private fun evaluateBinary(expression: PyBinaryExpression, target: ChipIdentity): Boolean? {
        val left = expression.leftExpression
        val right = expression.rightExpression

        if (expression.isOperator("or")) {
            val a = evaluate(left, target)
            val b = evaluate(right, target)
            return when {
                a == true || b == true -> true
                a == false && b == false -> false
                else -> null
            }
        }
        if (expression.isOperator("and")) {
            val a = evaluate(left, target)
            val b = evaluate(right, target)
            return when {
                a == false || b == false -> false
                a == true && b == true -> true
                else -> null
            }
        }
        if (expression.isOperator("in")) return member(left, right, target)
        if (expression.isOperator("notin")) return member(left, right, target)?.not()

        val operator = expression.operator?.takeIf { it in ORDERING } ?: return null
        stringComparison(left, right, target, operator)?.let { return it }
        return frequencyComparison(left, right, target, operator)
    }

    /** `__CHIP__.name in ("a", "b")`, and the list and set spellings of it. */
    private fun member(left: PyExpression?, right: PyExpression?, target: ChipIdentity): Boolean? {
        val value = stringValueOf(left, target) ?: return null
        // A parenthesised tuple is a PyParenthesizedExpression wrapping the tuple,
        // and that is the spelling this appears in.
        val unwrapped = (right as? PyParenthesizedExpression)?.containedExpression ?: right
        val sequence = unwrapped as? PySequenceExpression ?: return null
        val literals = sequence.elements.map { (it as? PyStringLiteralExpression)?.stringValue }
        if (literals.any { it == null }) return null
        return literals.any { it!!.equals(value, ignoreCase = true) }
    }

    /** `__CHIP__.<field> == "literal"`, either way round. */
    private fun stringComparison(
        left: PyExpression?,
        right: PyExpression?,
        target: ChipIdentity,
        operator: IElementType,
    ): Boolean? {
        if (operator != PyTokenTypes.EQEQ && operator != PyTokenTypes.NE) return null
        val value = stringValueOf(left, target) ?: stringValueOf(right, target) ?: return null
        val literal = (right as? PyStringLiteralExpression)?.stringValue
            ?: (left as? PyStringLiteralExpression)?.stringValue
            ?: return null
        val equal = value.equals(literal, ignoreCase = true)
        return if (operator == PyTokenTypes.EQEQ) equal else !equal
    }

    /**
     * `__FREQ__` against an integer literal.
     *
     * No facade gates an import on the clock today — `__FREQ__` appears only
     * inside baud and prescaler arithmetic. It is read anyway because the field
     * is public and a chip needing two register maps for two clocks would reach
     * for exactly this shape, and because leaving it out would have the plugin
     * answer "false" to a question it had not read.
     */
    private fun frequencyComparison(
        left: PyExpression?,
        right: PyExpression?,
        target: ChipIdentity,
        operator: IElementType,
    ): Boolean? {
        val frequency = target.frequency ?: return null
        val literal: Long
        val frequencyOnTheLeft: Boolean
        when {
            isFrequency(left) -> {
                literal = (right as? PyNumericLiteralExpression)?.longValue ?: return null
                frequencyOnTheLeft = true
            }
            isFrequency(right) -> {
                literal = (left as? PyNumericLiteralExpression)?.longValue ?: return null
                frequencyOnTheLeft = false
            }
            else -> return null
        }
        val a = if (frequencyOnTheLeft) frequency else literal
        val b = if (frequencyOnTheLeft) literal else frequency
        return when (operator) {
            PyTokenTypes.EQEQ -> a == b
            PyTokenTypes.NE -> a != b
            PyTokenTypes.LT -> a < b
            PyTokenTypes.LE -> a <= b
            PyTokenTypes.GT -> a > b
            PyTokenTypes.GE -> a >= b
            else -> null
        }
    }

    // ── reading the target out of an expression ──────────────────────────────

    /** The target's value for `__CHIP__.name` / `.arch` / `.board`, else null. */
    private fun stringValueOf(expression: PyExpression?, target: ChipIdentity): String? {
        val reference = expression as? PyReferenceExpression ?: return null
        val qualifier = reference.qualifier as? PyReferenceExpression ?: return null
        if (qualifier.referencedName != CHIP || qualifier.qualifier != null) return null
        return when (reference.referencedName?.takeIf { it in STRING_FIELDS }) {
            "name" -> target.chip
            "arch" -> target.arch
            "board" -> target.board
            else -> null
        }
    }

    private fun isFrequency(expression: PyExpression?): Boolean {
        val reference = expression as? PyReferenceExpression ?: return false
        return reference.qualifier == null && reference.referencedName == FREQ
    }
}
