package dev.begeistert.pymcu

import dev.begeistert.pymcu.lint.PyMcuLint
import dev.begeistert.pymcu.lint.LintScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the plugin is allowed to say when the porting assistant finds nothing.
 *
 * It used to say "No findings — this should port cleanly", in the balloon and again in the
 * tool window, and it said it about programs that do not compile. `pymcu lint` walks
 * CPython's `ast` over a fixed list of porting idioms; it resolves no name, knows no type and
 * follows no import. Measured on four programs the compiler refuses, an undefined call, a
 * literal too wide for its parameter, an unknown type and a write to a field that does not
 * exist, it reports nothing for all four.
 *
 * These tests refuse the OLD sentence as well as checking the new one, because the way this
 * comes back is somebody restoring a friendlier line.
 */
class LintScopeTest {

    private val reportWithScope = """
        {"flavor": "micropython",
         "files": [],
         "scope": {"checked": ["portability-idioms"],
                   "not_checked": ["names", "types", "imports", "target"],
                   "summary": "porting idioms only; no names, types or imports were resolved",
                   "for_correctness_run": "pymcu build"},
         "summary": {"errors": 0, "warnings": 0, "info": 0, "file_count": 1}}
    """.trimIndent()

    /** A driver older than the `scope` field. It checked exactly as little; it just did not say so. */
    private val reportWithoutScope = """
        {"flavor": null, "files": [],
         "summary": {"errors": 0, "warnings": 0, "info": 0, "file_count": 1}}
    """.trimIndent()

    @Test
    fun `the scope travels in the report`() {
        val report = PyMcuLint.parse(reportWithScope)
        assertNotNull(report)
        assertEquals("pymcu build", report!!.scope.forCorrectnessRun)
        assertTrue(report.scope.summary.contains("no names, types or imports"))
    }

    @Test
    fun `an older driver does not get the cheerful sentence back`() {
        // The regression that matters most. A missing field is not permission to promise
        // something; it is a report that failed to say what it had not looked at.
        val report = PyMcuLint.parse(reportWithoutScope)
        assertNotNull(report)
        assertEquals(LintScope.UNSTATED, report!!.scope)
        assertFalse(report.nothingFoundMessage.contains("port cleanly"))
    }

    @Test
    fun `the empty message claims nothing about compiling`() {
        for (json in listOf(reportWithScope, reportWithoutScope)) {
            val message = PyMcuLint.parse(json)!!.nothingFoundMessage
            // The exact sentence that shipped, and the claim under it however worded.
            assertFalse(message, message.contains("port cleanly"))
            assertFalse(message, message.contains("should port"))
            // What it says instead is about the findings, which is what was measured, and it
            // names the way to get the real answer.
            assertTrue(message, message.contains("No porting blockers found"))
            assertTrue(message, message.contains("pymcu build"))
        }
    }
}
