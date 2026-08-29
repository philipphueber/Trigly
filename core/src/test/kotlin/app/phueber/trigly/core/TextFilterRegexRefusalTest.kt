package app.phueber.trigly.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The one shape [TextFilterTest] cannot test alongside everything else:
 * a pattern that genuinely runs away.
 *
 * `RegexGuard` abandons a search like this rather than waiting for it: see
 * `RegexBudget.kt`. The thread it was running on keeps running, in the
 * background, for as long as its own backtracking takes, which is not
 * bounded by anything a test could wait on. `RegexGuardTest` proves the
 * mechanism with a synthetic sleep of a known length; a real catastrophic
 * pattern has no such known length, it is not "a few seconds longer", it is
 * unmeasured. So this file exists on its own, with nothing else in it to
 * leave a lingering thread behind for.
 *
 * There is a second, independent reason this file stays isolated even though
 * `RegexGuard` no longer poisons itself for every other pattern the way it
 * once did: [MAX_ABANDONED_THREADS] and the set of patterns already known bad
 * are bookkeeping for the whole process, not per test class, and this file's
 * two patterns each spend one of the four abandoned-thread slots for the rest
 * of whatever JVM they run in. `RegexGuardTest`'s own test of
 * [MAX_ABANDONED_THREADS] does the same on purpose. Sharing a JVM with either
 * would leave this file, or that test, refused as
 * [RegexRefusal.EXHAUSTED] instead of the [RegexRefusal.TIMED_OUT] each
 * expects, for a reason that has nothing to do with what either file is
 * actually testing. This module's `build.gradle.kts` sets `forkEvery = 1` for
 * both reasons: a fresh JVM per test class means neither the lingering
 * thread nor the spent slot survives into the next class's tests.
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
     * [TextFilter.Outcome.REFUSED] with [RegexRefusal.TIMED_OUT], not a
     * thrown exception and not a false "no match" that looks the same as an
     * honest miss from the outside.
     */
    @Test(timeout = 60_000)
    fun `a pattern that does too much work is refused rather than matched`() {
        val filter = TextFilter.of(".*.*.*b", TextMatchMode.REGEX)
        val text = "a".repeat(1800)

        assertEquals(TextFilter.Outcome.REFUSED(RegexRefusal.TIMED_OUT), filter.outcome(text))
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
     *
     * A different pattern from the test above on purpose, not only for
     * variety: the same pattern twice in one file would prove
     * [RegexRefusal.KNOWN_BAD], the second time, not [RegexRefusal.TIMED_OUT]
     * again, which is `RegexGuardTest`'s test to own, not this file's.
     */
    @Test(timeout = 60_000)
    fun `short text does not escape the bound`() {
        val filter = TextFilter.of(".*.*.*.*b", TextMatchMode.REGEX)
        val text = "a".repeat(200)

        assertEquals(TextFilter.Outcome.REFUSED(RegexRefusal.TIMED_OUT), filter.outcome(text))
        assertFalse(filter.matches(text))
    }
}
