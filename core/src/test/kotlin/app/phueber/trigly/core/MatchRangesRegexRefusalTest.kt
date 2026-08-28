package app.phueber.trigly.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one shape [MatchRangesTest] cannot test alongside everything else:
 * a pattern that genuinely runs away. See `TextFilterRegexRefusalTest`'s KDoc
 * for the full reasoning; it applies here unchanged, because `matchRangesIn`
 * runs its search through the identical shared `RegexGuard`.
 *
 * Do not add another test to this file for the same reason that file gives.
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

        assertTrue(matchRangesIn(".*.*.*b", TextMatchMode.REGEX, text).isEmpty())
        assertEquals(
            TextFilter.Outcome.REFUSED,
            TextFilter.of(".*.*.*b", TextMatchMode.REGEX).outcome(text),
        )
    }

    /** Same shape, same reason as `TextFilterRegexRefusalTest`'s test of the same name. */
    @Test(timeout = 60_000)
    fun `short text does not escape the bound`() {
        val text = "a".repeat(200)

        assertTrue(matchRangesIn(".*.*.*.*b", TextMatchMode.REGEX, text).isEmpty())
        assertEquals(
            TextFilter.Outcome.REFUSED,
            TextFilter.of(".*.*.*.*b", TextMatchMode.REGEX).outcome(text),
        )
    }
}
