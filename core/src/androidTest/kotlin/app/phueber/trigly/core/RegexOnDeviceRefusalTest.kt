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
 * for this exact pattern and text on the JVM. `RegexGuard`'s wall clock is the
 * same mechanism and the same five-second number on both platforms, and
 * `RegexBudget.kt`'s KDoc has the measurement that makes this device
 * assertion safe rather than hopeful: `.*.*.*b` over 1800 characters did not
 * finish in ten seconds on this exact emulator, well clear of the bound.
 *
 * A separate class from `RegexOnDeviceTest`, with nothing else in it, because
 * a search this file provokes keeps running on its own abandoned thread, in
 * the background, for an unmeasured time after this class's own assertions
 * are made, on this same device process. That thread no longer occupies
 * `RegexGuard` once it is abandoned, and this file's two patterns are the
 * only two `RegexOnDeviceTest` would ever have collided with anyway, but the
 * abandoned-thread count `RegexGuard` keeps is bookkeeping for the whole
 * process, the same as on the JVM: see the next paragraph.
 *
 * **What used to be here, and what actually broke the connected gate.** This
 * file first shipped with a comment warning that `AndroidJUnitRunner` might
 * run this class before `RegexOnDeviceTest`, alphabetically, and leave its
 * search "occupying" the guard. That framed the *ordering* as the hazard. It
 * was not: the real fault was that `RegexGuard` used to clear its one busy
 * flag only when an abandoned search finished on its own, which a search that
 * never finishes never does, so once poisoned it stayed refused for every
 * pattern, forever, regardless of order. The connected gate caught exactly
 * this: `RegexOnDeviceTest`'s `contains("abc123", "\d+", "regex")`, a search
 * over six characters that has never once been slow, failed with "took too
 * long against 6 characters of text", six times, on both API levels, because
 * this class's own patterns had already poisoned the one shared thread before
 * `RegexOnDeviceTest` ran. `RegexBudget.kt` documents the fix: a search that
 * times out is abandoned and replaced, not left poisoning the guard, and its
 * own identity is what gets refused from then on, not every identity.
 *
 * **What is left, now that the poisoning is fixed.** `RegexGuard` still
 * remembers every pattern that has ever timed out, and still caps how many
 * abandoned threads may exist at once, for the life of the process: see
 * `MAX_ABANDONED_THREADS` and `MAX_KNOWN_BAD_PATTERNS` in `RegexBudget.kt`.
 * Neither is per test class. So the residual risk this class still carries is
 * narrower than the old one, but not zero: if some other class in this
 * process happened to search the *exact same pattern and case sensitivity* as
 * one used here, it would inherit `RegexRefusal.KNOWN_BAD` instead of timing
 * out itself, and if four *different* classes each abandoned a distinct bad
 * pattern in the same process, a fifth would see `RegexRefusal.EXHAUSTED`
 * even for an honest search. **What either failure looks like:** an otherwise
 * ordinary test failing with a refusal reason that does not match what that
 * test itself provoked, rather than the six-times-over hang the old bug
 * produced. `RegexOnDeviceTest`'s own patterns (`\d+`, `^12`, `^34`,
 * `[0-9]+`, `alice`) do not collide with this file's `.*.*.*b`, so today
 * neither risk is live between these two specific classes; it stays possible
 * in general, the same way it does in the JVM suite, which is why
 * `core/build.gradle.kts`'s `forkEvery = 1` still isolates the JVM's own
 * refusal classes from each other rather than relying on their patterns never
 * matching. `connectedDebugAndroidTest` has no equivalent to `forkEvery`, so
 * nothing here can enforce the same isolation; keeping this file's patterns
 * distinct from every other class's is what keeps that difference from
 * mattering in practice.
 */
@RunWith(AndroidJUnit4::class)
class RegexOnDeviceRefusalTest {

    /**
     * Matches `TextFilterRegexRefusalTest`'s JVM assertion for this pattern
     * and text, verdict for verdict: [TextFilter.Outcome.REFUSED] with
     * [RegexRefusal.TIMED_OUT], not a hang and not a false "no match".
     */
    @Test(timeout = 20_000)
    fun a_pattern_that_runs_away_is_refused_on_this_device() {
        val filter = TextFilter.of(".*.*.*b", TextMatchMode.REGEX)
        val text = "a".repeat(1800)

        assertEquals(TextFilter.Outcome.REFUSED(RegexRefusal.TIMED_OUT), filter.outcome(text))
        assertFalse(filter.matches(text))
    }

    /**
     * Matches `ExpressionRegexRefusalTest`'s JVM assertion for this pattern
     * and text: the expression language's regex mode shares [RegexGuard] with
     * [TextFilter], and a refusal there is not `false`, it is a failed
     * expression. See `contains`'s KDoc in `Expression.kt` for why.
     *
     * Same literal pattern text as the test above, on purpose, and not a
     * repeat of it: `contains` compiles case-sensitively where `TextFilter`'s
     * `regex` mode does not, so this is a different [RegexIdentity], times out
     * on its own, and does not inherit the test above's [RegexRefusal.KNOWN_BAD].
     */
    @Test(timeout = 20_000)
    fun the_expression_language_fails_rather_than_hangs_on_the_same_pattern() {
        val text = "a".repeat(1800)
        val outcome = evaluateExpression("contains(\"$text\", \".*.*.*b\", \"regex\")")

        assertTrue(outcome is ExpressionOutcome.Failed)
    }
}
