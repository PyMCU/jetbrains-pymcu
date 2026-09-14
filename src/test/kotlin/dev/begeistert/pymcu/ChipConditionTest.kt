package dev.begeistert.pymcu

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.python.psi.PyExpression
import com.jetbrains.python.psi.PyIfStatement
import com.jetbrains.python.psi.PyMatchStatement
import dev.begeistert.pymcu.resolver.ChipIdentity
import dev.begeistert.pymcu.resolver.PyMcuBranch
import dev.begeistert.pymcu.resolver.PyMcuChipCondition

/**
 * Reading the stdlib's compile-time dispatch the way the compiler reads it.
 *
 * Every HAL facade picks its implementation from `__CHIP__`, and until the plugin
 * could evaluate those conditions it had only the architecture directory to go on
 * — which decides nothing inside `hal/avr/`, where six chip modules sit side by
 * side. These pin what the evaluator answers, and just as carefully where it
 * refuses to answer: the third value is not a formality, because a chain whose
 * second condition cannot be read has no dead branches after it either.
 */
class ChipConditionTest : BasePlatformTestCase() {

    private val uno = ChipIdentity("atmega328p", "avr", board = "arduino_uno", frequency = 16_000_000)
    private val tiny = ChipIdentity("attiny2313", "avr")
    private val pico = ChipIdentity("rp2040", "arm", board = "pico")

    /** The condition of the single `if` in [source]. */
    private fun condition(source: String): PyExpression? {
        val file = myFixture.configureByText("c${counter++}.py", "$source\n    pass\n")
        return PsiTreeUtil.findChildOfType(file, PyIfStatement::class.java)?.ifPart?.condition
    }

    private fun evaluate(source: String, target: ChipIdentity): Boolean? =
        PyMcuChipCondition.evaluate(condition("if $source:"), target)

    private var counter = 0

    // ── the two fields every facade branches on ──────────────────────────────

    fun testTheChipNameIsCompared() {
        assertEquals(true, evaluate("""__CHIP__.name == "atmega328p"""", uno))
        assertEquals(false, evaluate("""__CHIP__.name == "attiny85"""", uno))
        assertEquals(false, evaluate("""__CHIP__.name != "atmega328p"""", uno))
    }

    fun testTheArchitectureIsCompared() {
        assertEquals(true, evaluate("""__CHIP__.arch == "avr"""", uno))
        assertEquals(false, evaluate("""__CHIP__.arch == "pic14"""", uno))
        assertEquals(true, evaluate("""__CHIP__.arch == "arm"""", pico))
    }

    /** `hal/wifi.py` branches on the board, and on it being unset. */
    fun testTheBoardIsCompared() {
        assertEquals(true, evaluate("""__CHIP__.board == "arduino_uno"""", uno))
        assertEquals(true, evaluate("__CHIP__.board == \"\"", tiny))
        assertEquals(false, evaluate("""__CHIP__.board == "pico_w"""", pico))
    }

    fun testALiteralOnTheLeftReadsTheSame() {
        assertEquals(true, evaluate(""""avr" == __CHIP__.arch""", uno))
    }

    /** A board catalog may spell the chip `ATmega328P`; the stdlib never does. */
    fun testCaseDoesNotDecideTheBranch() {
        assertEquals(
            true,
            PyMcuChipCondition.evaluate(
                condition("""if __CHIP__.name == "atmega328p":"""),
                ChipIdentity("ATmega328P", "AVR"),
            )
        )
    }

    // ── the operators the facades are written with ───────────────────────────

    fun testOrAndAndCombineTheWayPythonDoes() {
        assertEquals(true, evaluate("""__CHIP__.name == "attiny2313" or __CHIP__.name == "attiny4313"""", tiny))
        assertEquals(false, evaluate("""__CHIP__.name == "attiny85" or __CHIP__.name == "attiny45"""", tiny))
        assertEquals(true, evaluate("""__CHIP__.arch == "avr" and __CHIP__.name == "attiny2313"""", tiny))
        assertEquals(false, evaluate("""__CHIP__.arch == "avr" and __CHIP__.name == "atmega328p"""", tiny))
    }

    fun testParenthesesAndNotAreRead() {
        assertEquals(true, evaluate("""(__CHIP__.arch == "avr")""", uno))
        assertEquals(false, evaluate("""not __CHIP__.arch == "avr"""", uno))
        assertEquals(true, evaluate("""not (__CHIP__.name == "attiny85")""", uno))
    }

    fun testMembershipInALiteralSequenceIsRead() {
        assertEquals(true, evaluate("""__CHIP__.name in ("atmega328p", "atmega168p")""", uno))
        assertEquals(false, evaluate("""__CHIP__.name in ["attiny85", "attiny45"]""", uno))
        assertEquals(true, evaluate("""__CHIP__.name not in ("attiny85",)""", uno))
    }

    /** The clock is public and comparable, even though no facade gates on it yet. */
    fun testTheClockIsComparedWhenTheProjectStatesOne() {
        assertEquals(true, evaluate("__FREQ__ == 16000000", uno))
        assertEquals(true, evaluate("__FREQ__ >= 8000000", uno))
        assertEquals(false, evaluate("__FREQ__ < 8000000", uno))
        assertEquals(true, evaluate("8000000 < __FREQ__", uno))
        assertNull("a project with no clock cannot answer", evaluate("__FREQ__ == 16000000", tiny))
    }

    // ── refusals ─────────────────────────────────────────────────────────────

    fun testAnythingElseIsUndecidable() {
        assertNull(evaluate("__CHIP__.ram_size == 2048", uno))
        assertNull(evaluate("some_flag", uno))
        assertNull(evaluate("""os.environ["X"] == "y"""", uno))
        assertNull(evaluate("""__CHIP__.name == other""", uno))
    }

    /** An `or` still answers when one side is readable and decides it. */
    fun testHalfAnAnswerIsStillAnAnswerWhenItDecides() {
        assertEquals(true, evaluate("""__CHIP__.arch == "avr" or unknown_flag""", uno))
        assertEquals(false, evaluate("""__CHIP__.arch == "avr" and unknown_flag""", pico))
        assertNull(evaluate("""__CHIP__.arch == "avr" or unknown_flag""", pico))
    }

    fun testOnlyChipQuestionsCount() {
        assertTrue(PyMcuChipCondition.mentionsTarget(condition("""if __CHIP__.arch == "avr":""")))
        assertTrue(PyMcuChipCondition.mentionsTarget(condition("if __FREQ__ > 1:")))
        assertFalse(PyMcuChipCondition.mentionsTarget(condition("if DEBUG:")))
        assertFalse(PyMcuChipCondition.mentionsTarget(null))
    }

    // ── whole chains ─────────────────────────────────────────────────────────

    private fun chain(source: String, target: ChipIdentity): List<PyMcuBranch> {
        val file = myFixture.configureByText("chain${counter++}.py", source)
        val statement = PsiTreeUtil.findChildOfType(file, PyIfStatement::class.java)!!
        val parts = listOf(statement.ifPart) + statement.elifParts
        return PyMcuChipCondition.chainVerdicts(
            parts.map { it.condition },
            statement.elsePart != null,
            target,
        )
    }

    /** `uart_text.py`, which is where an ATtiny2313 project used to land wrongly. */
    fun testOneBranchOfAChainIsLiveAndTheRestAreNot() {
        val verdicts = chain(
            """
            if __CHIP__.name == "attiny2313" or __CHIP__.name == "attiny4313":
                pass
            elif __CHIP__.name == "atmega32u4":
                pass
            elif __CHIP__.arch == "avr":
                pass
            else:
                pass
            """.trimIndent(),
            tiny,
        )
        assertEquals(
            listOf(PyMcuBranch.LIVE, PyMcuBranch.DEAD, PyMcuBranch.DEAD, PyMcuBranch.DEAD),
            verdicts
        )
    }

    fun testTheSameChainPicksTheFallbackForAnUno() {
        val verdicts = chain(
            """
            if __CHIP__.name == "attiny2313":
                pass
            elif __CHIP__.arch == "avr":
                pass
            else:
                pass
            """.trimIndent(),
            uno,
        )
        assertEquals(listOf(PyMcuBranch.DEAD, PyMcuBranch.LIVE, PyMcuBranch.DEAD), verdicts)
    }

    fun testTheElseIsLiveWhenEveryConditionIsFalse() {
        val verdicts = chain(
            """
            if __CHIP__.name == "attiny85":
                pass
            elif __CHIP__.name == "atmega32u4":
                pass
            else:
                pass
            """.trimIndent(),
            uno,
        )
        assertEquals(listOf(PyMcuBranch.DEAD, PyMcuBranch.DEAD, PyMcuBranch.LIVE), verdicts)
    }

    /**
     * The reason the third value exists. One unreadable condition cannot rule out
     * the branches after it, so nothing later is demoted and the user still sees
     * every candidate rather than a confidently wrong one.
     */
    fun testAnUnreadableConditionMakesEverythingAfterItUnknown() {
        val verdicts = chain(
            """
            if __CHIP__.name == "attiny85":
                pass
            elif SOME_BUILD_FLAG:
                pass
            elif __CHIP__.arch == "avr":
                pass
            """.trimIndent(),
            uno,
        )
        assertEquals(
            listOf(PyMcuBranch.DEAD, PyMcuBranch.UNKNOWN, PyMcuBranch.UNKNOWN),
            verdicts
        )
    }

    /**
     * The `else` is the one part an unreadable condition cannot save: a later
     * condition that is definitely true means the chain is taken whatever the
     * unreadable one answers, so the `else` never runs.
     */
    fun testAnElseIsDeadOnceSomeConditionIsDefinitelyTrue() {
        val verdicts = chain(
            """
            if SOME_BUILD_FLAG:
                pass
            elif __CHIP__.arch == "avr":
                pass
            else:
                pass
            """.trimIndent(),
            uno,
        )
        assertEquals(
            listOf(PyMcuBranch.UNKNOWN, PyMcuBranch.UNKNOWN, PyMcuBranch.DEAD),
            verdicts
        )
    }

    // ── match, the stdlib's other dispatch shape ─────────────────────────────

    private fun match(source: String, target: ChipIdentity): List<PyMcuBranch> {
        val file = myFixture.configureByText("match${counter++}.py", source)
        val statement = PsiTreeUtil.findChildOfType(file, PyMatchStatement::class.java)!!
        return PyMcuChipCondition.matchVerdicts(statement.subject, statement.caseClauses, target)
    }

    /** `hal/pic14/pwm.py` picks its register map exactly like this. */
    fun testACaseIsLiveAndTheWildcardIsNot() {
        val verdicts = match(
            """
            match __CHIP__.name:
                case "attiny2313":
                    pass
                case _:
                    pass
            """.trimIndent(),
            tiny,
        )
        assertEquals(listOf(PyMcuBranch.LIVE, PyMcuBranch.DEAD), verdicts)
    }

    fun testTheWildcardIsTheLiveOneWhenNoCaseMatches() {
        val verdicts = match(
            """
            match __CHIP__.name:
                case "pic16f18877":
                    pass
                case _:
                    pass
            """.trimIndent(),
            uno,
        )
        assertEquals(listOf(PyMcuBranch.DEAD, PyMcuBranch.LIVE), verdicts)
    }

    /** `case "pic14" | "pic14e":` — `irq.py` writes several of these. */
    fun testAnAlternationMatchesAnyOfItsLiterals() {
        val verdicts = match(
            """
            match __CHIP__.name:
                case "attiny2313" | "attiny4313":
                    pass
                case "atmega328p":
                    pass
            """.trimIndent(),
            tiny,
        )
        assertEquals(listOf(PyMcuBranch.LIVE, PyMcuBranch.DEAD), verdicts)
    }

    fun testAMatchOnSomethingElseDecidesNothing() {
        val verdicts = match(
            """
            match mode:
                case "fast":
                    pass
                case _:
                    pass
            """.trimIndent(),
            uno,
        )
        assertEquals(listOf(PyMcuBranch.UNKNOWN, PyMcuBranch.UNKNOWN), verdicts)
    }

    fun testAGuardedCaseIsNotDecidedHere() {
        val verdicts = match(
            """
            match __CHIP__.arch:
                case "avr" if SOMETHING:
                    pass
                case _:
                    pass
            """.trimIndent(),
            uno,
        )
        assertEquals(listOf(PyMcuBranch.UNKNOWN, PyMcuBranch.UNKNOWN), verdicts)
    }

    // ── nesting ──────────────────────────────────────────────────────────────

    fun testABranchInsideADeadBranchIsDead() {
        assertEquals(PyMcuBranch.DEAD, PyMcuBranch.DEAD.and(PyMcuBranch.LIVE))
        assertEquals(PyMcuBranch.DEAD, PyMcuBranch.LIVE.and(PyMcuBranch.DEAD))
        assertEquals(PyMcuBranch.UNKNOWN, PyMcuBranch.LIVE.and(PyMcuBranch.UNKNOWN))
        assertEquals(PyMcuBranch.DEAD, PyMcuBranch.DEAD.and(PyMcuBranch.UNKNOWN))
        assertEquals(PyMcuBranch.LIVE, PyMcuBranch.LIVE.and(PyMcuBranch.LIVE))
    }
}
