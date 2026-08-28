package app.phueber.trigly.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The one shape [TextFilterTest] cannot test alongside everything else:
 * a pattern that genuinely runs away.
 *
 * `RegexGuard` is one thread shared by the whole process, on purpose: see
 * `RegexBudget.kt`. A pattern built to run away keeps running on that thread,
 * in the background, for as long as its own backtracking takes, which is not
 * bounded by anything a test could wait on. `RegexGuardTest` proves the
 * mechanism with a synthetic sleep of a known length, which this file's
 * `@After` hook could safely wait out. A real catastrophic pattern has no such
 * known length; it is not "a few seconds longer", it is unmeasured. So this
 * file exists on its own, with nothing else in it to contaminate, and this
 * module's `build.gradle.kts` sets `forkEvery = 1` for exactly this reason: a
 * fresh JVM per test class means the process this file's lingering search
 * runs in is gone, search and all, before the next class's tests begin.
 *
 * Do not add another test to this file. Anything that does not deliberately
 * run away belongs in `TextFilterTest` instead, where it is safe to keep
 * ordinary company.
 */
class TextFilterRegexRefusalTest {

    /**
     * Three of `.*` over 1800 characters. Two of `.*` over the same text was
     * the shape this test used before the bound became a wall clock, but it
     * measured at 5.9 seconds on the desktop JVM these tests run on, too close
     * to the five-second bound for a reliable assertion. This shape does not
     * finish in fifteen seconds on that same JVM, so it must be
     * [TextFilter.Outcome.REFUSED], not a thrown exception and not a false
     * "no match" that looks the same as an honest miss from the outside.
     */
    @Test(timeout = 60_000)
    fun `a pattern that does too much work is refused rather than matched`() {
        val filter = TextFilter.of(".*.*.*b", TextMatchMode.REGEX)
        val text = "a".repeat(1800)

        assertEquals(TextFilter.Outcome.REFUSED, filter.outcome(text))
        // matches() never throws and never claims a match it could not verify.
        assertFalse(filter.matches(text))
    }

    /**
     * Why a short adversarial pattern is refused too: `.*.*.*.*b` over only
     * two hundred characters does not finish in eight seconds on the JVM
     * either. A wall-clock bound does not scale with the length of the text
     * the way the old counted bound did, so this is not proving the same
     * point the old test proved; it is proving that refusal does not depend
     * on the input being long.
     */
    @Test(timeout = 60_000)
    fun `short text does not escape the bound`() {
        val filter = TextFilter.of(".*.*.*.*b", TextMatchMode.REGEX)
        val text = "a".repeat(200)

        assertEquals(TextFilter.Outcome.REFUSED, filter.outcome(text))
        assertFalse(filter.matches(text))
    }
}
