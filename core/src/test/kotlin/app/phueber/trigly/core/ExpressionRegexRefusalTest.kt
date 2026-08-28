package app.phueber.trigly.core

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one shape [ExpressionTest] cannot test alongside everything else:
 * a pattern that genuinely runs away. See `TextFilterRegexRefusalTest`'s KDoc
 * for the full reasoning; it applies here unchanged, because `contains(a, b,
 * "regex")` shares the identical `RegexGuard` with `TextFilter`'s regex mode.
 *
 * Do not add another test to this file for the same reason that file gives.
 */
class ExpressionRegexRefusalTest {

    private fun failed(source: String): String =
        (evaluateExpression(source) as ExpressionOutcome.Failed).reason

    /**
     * Two of `.*` over 1800 characters measured at 5.9 seconds on the JVM
     * these tests run on: too close to a five-second bound to assert
     * reliably. Three of `.*` over the same text does not finish in fifteen
     * seconds on that JVM, so it is refused with room to spare instead of run.
     */
    @Test(timeout = 60_000)
    fun `a pattern that does too much work is refused`() {
        val reason = failed("contains(\"${"a".repeat(1800)}\", \".*.*.*b\", \"regex\")")

        assertTrue(reason, reason.contains("took too long"))
    }

    /**
     * Why a short adversarial pattern is refused too: four of `.*` over two
     * hundred characters does not finish in eight seconds on the JVM either.
     * A wall-clock bound does not grow with the length of the text the old
     * counted bound did, so a short input is not a way around it.
     */
    @Test(timeout = 60_000)
    fun `short text does not escape the bound`() {
        val reason = failed("contains(\"${"a".repeat(200)}\", \".*.*.*.*b\", \"regex\")")

        assertTrue(reason, reason.contains("took too long"))
    }
}
