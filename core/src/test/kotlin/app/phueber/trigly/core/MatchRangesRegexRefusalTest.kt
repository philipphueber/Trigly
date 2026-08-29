package app.phueber.trigly.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one shape [MatchRangesTest] cannot test alongside everything else:
 * a pattern that genuinely runs away. See `TextFilterRegexRefusalTest`'s KDoc
 * for the full reasoning; it applies here unchanged, because `matchRangesIn`
 * runs its search through the identical shared `RegexGuard`, and shares the
 * identical [RegexIdentity] for the same pattern: `TextFilter`'s `regex`
 * mode and `matchRangesIn` both compile with `RegexOption.IGNORE_CASE`, so a
 * pattern one of them has already learned is bad is refused on sight by the
 * other too, at [RegexRefusal.KNOWN_BAD] rather than a second
 * [RegexRefusal.TIMED_OUT] wait. Each test below asks [TextFilter.outcome]
 * first, for the honest [RegexRefusal.TIMED_OUT] answer, and asks
 * [matchRangesIn] afterward for exactly that reason: asking the other way
 * round would make [matchRangesIn] pay the first five-second wait and leave
 * [TextFilter.outcome] to inherit [RegexRefusal.KNOWN_BAD] instead, which
 * would test the same mechanism but describe it backwards.
 *
 * Do not add another test to this file for the same reason
 * `TextFilterRegexRefusalTest` gives.
 */
class MatchRangesRegexRefusalTest {

    /**
     * Three of `.*` over 1800 characters does not finish in fifteen seconds
     * on the JVM these tests run on (measured in `TextFilterRegexRefusalTest`),
     * so it is refused rather than run. The highlight comes back empty, the
     * same answer a pattern that simply does not match would give, and the
     * filter's own [TextFilter.Outcome] is what tells the two apart.
     */
    @Test(timeout = 60_000)
    fun `a pattern that does too much work highlights nothing, rather than hanging`() {
        val text = "a".repeat(1800)

        val outcome = TextFilter.of(".*.*.*b", TextMatchMode.REGEX).outcome(text)
        assertEquals(TextFilter.Outcome.REFUSED(RegexRefusal.TIMED_OUT), outcome)

        // Same identity, already known bad from the call above: refused on
        // sight rather than a second long wait, and still nothing to
        // highlight either way.
        assertTrue(matchRangesIn(".*.*.*b", TextMatchMode.REGEX, text).isEmpty())
    }

    /** Same shape, same reason as `TextFilterRegexRefusalTest`'s test of the same name. */
    @Test(timeout = 60_000)
    fun `short text does not escape the bound`() {
        val text = "a".repeat(200)

        val outcome = TextFilter.of(".*.*.*.*b", TextMatchMode.REGEX).outcome(text)
        assertEquals(TextFilter.Outcome.REFUSED(RegexRefusal.TIMED_OUT), outcome)

        assertTrue(matchRangesIn(".*.*.*.*b", TextMatchMode.REGEX, text).isEmpty())
    }
}
