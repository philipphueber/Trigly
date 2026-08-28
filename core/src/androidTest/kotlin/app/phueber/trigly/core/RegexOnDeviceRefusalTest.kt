package app.phueber.trigly.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one shape [RegexOnDeviceTest] cannot assert alongside everything else:
 * that a pattern which never finishes is actually refused, here, on the
 * device this app ships to.
 *
 * `RegexOnDeviceTest`'s own rule is "assert that a search on ART answers what
 * the same search answers on the JVM". This file follows that rule for the
 * one answer the JVM tests could never prove meant anything on a device:
 * "refused". `TextFilterRegexRefusalTest`'s `a pattern that does too much
 * work is refused rather than matched` asserts `TextFilter.Outcome.REFUSED`
 * for this exact pattern and text on the JVM. The counting bound this file's
 * sibling used to guard against would have let the same search run forever on
 * a device, silently disagreeing with that JVM answer. `RegexGuard`'s wall
 * clock does not: it is the same mechanism and the same five-second number on
 * both platforms, and `RegexBudget.kt`'s KDoc has the measurement that makes
 * this device assertion safe rather than hopeful: `.*.*.*b` over 1800
 * characters did not finish in ten seconds on this exact emulator, well clear
 * of the bound.
 *
 * A separate class from `RegexOnDeviceTest`, with nothing else in it, because
 * `RegexGuard` is one thread shared by the whole process: the search each
 * test below provokes keeps running in the background for an unmeasured time
 * after that test's own assertion is made, on this same device process, and
 * `RegexOnDeviceTest`'s other tests must not be the ones that pay for it. Do
 * not add another test to this file for the same reason.
 *
 * **A gap this file cannot close by itself.** `core/build.gradle.kts` gives
 * the JVM suite a fresh process per test class for exactly this hazard, but
 * `connectedDebugAndroidTest` has no such option: one instrumentation run is
 * one device process for every class in it, for the whole module, and
 * `RegexGuard` is a singleton for as long as that process lives. If
 * `AndroidJUnitRunner` happens to run this module's classes in the
 * alphabetical order their names suggest, this class (`RegexOnDeviceRefusal`)
 * sorts before `RegexOnDeviceTest` and would run first, leaving its
 * still-running search occupying `RegexGuard` exactly when `RegexOnDeviceTest`
 * asks it to run an ordinary, honest pattern. **What that failure looks
 * like:** `RegexOnDeviceTest`'s tests would fail claiming a search that should
 * match plainly does not, or that `evaluateExpression` failed where it should
 * have succeeded, in the same run, right after this class's tests, on a
 * pattern that has never once failed on its own. That is not flakiness and
 * not a device difference; it is this same ordering hazard on the one
 * platform that has no `forkEvery` to answer it with. This was measured, not
 * guessed: removing `core/build.gradle.kts`'s `forkEvery = 1` reproduced
 * exactly this shape of failure on the JVM, in `ExpressionTest` and
 * `MatchRangesTest`, once the equivalent ordering put a refusal class first.
 * Nothing here works around it; a real fix would have to live in `RegexGuard`
 * itself, and is left as an open question rather than something this test
 * file should paper over by guessing at an order Gradle does not promise.
 */
@RunWith(AndroidJUnit4::class)
class RegexOnDeviceRefusalTest {

    /**
     * Matches `TextFilterRegexRefusalTest`'s JVM assertion for this pattern
     * and text, verdict for verdict: [TextFilter.Outcome.REFUSED], not a hang
     * and not a false "no match".
     */
    @Test(timeout = 20_000)
    fun a_pattern_that_runs_away_is_refused_on_this_device() {
        val filter = TextFilter.of(".*.*.*b", TextMatchMode.REGEX)
        val text = "a".repeat(1800)

        assertEquals(TextFilter.Outcome.REFUSED, filter.outcome(text))
        assertFalse(filter.matches(text))
    }

    /**
     * Matches `ExpressionRegexRefusalTest`'s JVM assertion for this pattern
     * and text: the expression language's regex mode shares [RegexGuard] with
     * [TextFilter], and a refusal there is not `false`, it is a failed
     * expression. See `contains`'s KDoc in `Expression.kt` for why.
     */
    @Test(timeout = 20_000)
    fun the_expression_language_fails_rather_than_hangs_on_the_same_pattern() {
        val text = "a".repeat(1800)
        val outcome = evaluateExpression("contains(\"$text\", \".*.*.*b\", \"regex\")")

        assertTrue(outcome is ExpressionOutcome.Failed)
    }
}
