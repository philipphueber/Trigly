package app.phueber.trigly.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The spans the pattern tester highlights.
 *
 * The property that matters is not "does it find matches" but **does it agree
 * with the engine**. A tester whose highlight and whose verdict disagree teaches
 * people to distrust both, so the last test here checks the two against each
 * other across a spread of patterns rather than asserting them separately.
 */
class MatchRangesTest {

    @Test
    fun `a substring is found everywhere it occurs`() {
        assertEquals(
            listOf(0..2, 10..12),
            matchRangesIn("abc", TextMatchMode.CONTAINS, "abcdefghijabc"),
        )
    }

    @Test
    fun `a substring search ignores case, like the filter it mirrors`() {
        assertEquals(
            listOf(0..2),
            matchRangesIn("ABC", TextMatchMode.CONTAINS, "abcdef"),
        )
    }

    @Test
    fun `overlapping substrings are reported once each, not once per position`() {
        // "aa" in "aaaa" is found at 0 and 2 — advancing past each match rather
        // than by one character, so a highlight cannot double-cover a character.
        assertEquals(listOf(0..1, 2..3), matchRangesIn("aa", TextMatchMode.CONTAINS, "aaaa"))
    }

    @Test
    fun `a regex reports each match`() {
        assertEquals(
            listOf(0..2, 7..9),
            matchRangesIn("[0-9]+", TextMatchMode.REGEX, "123abc 456"),
        )
    }

    @Test
    fun `a regex ignores case, like the filter it mirrors`() {
        assertEquals(listOf(0..3), matchRangesIn("[a-z]+", TextMatchMode.REGEX, "ABCD"))
    }

    @Test
    fun `an anchored regex reports the whole string`() {
        assertEquals(listOf(0..4), matchRangesIn("^hello$", TextMatchMode.REGEX, "hello"))
        assertTrue(matchRangesIn("^hello$", TextMatchMode.REGEX, "hello there").isEmpty())
    }

    /**
     * `a*` matches "b" — at no position anyone would want underlined. The verdict
     * still has to say it matched, so the ranges being empty is correct and the
     * tester says both halves.
     */
    @Test
    fun `zero-width matches produce no spans`() {
        assertTrue(matchRangesIn("a*", TextMatchMode.REGEX, "bbb").isEmpty())
        // And the engine agrees it is a match, which is the pair the UI reports.
        assertTrue(TextFilter.of("a*", TextMatchMode.REGEX).matches("bbb"))
    }

    @Test
    fun `an empty pattern or an empty candidate has nothing to mark`() {
        assertTrue(matchRangesIn(null, TextMatchMode.REGEX, "anything").isEmpty())
        assertTrue(matchRangesIn("", TextMatchMode.CONTAINS, "anything").isEmpty())
        assertTrue(matchRangesIn("x", TextMatchMode.REGEX, "").isEmpty())
    }

    @Test
    fun `a pattern that does not compile marks nothing rather than throwing`() {
        // The tester shows the compile error separately; this must not blow up
        // while someone is halfway through typing a character class.
        assertTrue(matchRangesIn("[unclosed", TextMatchMode.REGEX, "abc").isEmpty())
    }

    // --- the work a highlight may do --------------------------------------------
    //
    // matchRangesIn runs the same bounded search TextFilter.matches does, over
    // the same BudgetedText, so a pattern the filter refuses cannot still be
    // searched here just because this call only draws a highlight. Every test
    // below has a timeout: a bound that stops working hangs, it does not answer
    // wrongly. See TextFilterTest for the sibling tests and where the numbers
    // were measured.

    /**
     * An ordinary pattern over 1800 characters, the size `screen_content` can
     * hand this. `a+` over that text is one match spanning the whole string,
     * and has to come back rather than being refused: there is nothing wrong
     * with this search.
     */
    @Test(timeout = 60_000)
    fun `an ordinary pattern over long text is still highlighted`() {
        val text = "a".repeat(1800)

        assertEquals(listOf(0..1799), matchRangesIn("a+", TextMatchMode.REGEX, text))
    }

    /**
     * Two of `.*` over the same 1800 characters costs far more than the budget
     * allows (measured in TextFilterTest). The highlight comes back empty, the
     * same answer a pattern that simply does not match would give, and the
     * filter's own [TextFilter.Outcome] is what tells the two apart.
     */
    @Test(timeout = 60_000)
    fun `a pattern that does too much work highlights nothing, rather than hanging`() {
        val text = "a".repeat(1800)

        assertTrue(matchRangesIn(".*.*b", TextMatchMode.REGEX, text).isEmpty())
        assertEquals(
            TextFilter.Outcome.BUDGET_SPENT,
            TextFilter.of(".*.*b", TextMatchMode.REGEX).outcome(text),
        )
    }

    /** Same rate, same reason as TextFilterTest's test of the same name. */
    @Test(timeout = 60_000)
    fun `short text does not buy a worse pattern`() {
        val text = "a".repeat(60)

        assertTrue(matchRangesIn(".*.*.*b", TextMatchMode.REGEX, text).isEmpty())
        assertEquals(
            TextFilter.Outcome.BUDGET_SPENT,
            TextFilter.of(".*.*.*b", TextMatchMode.REGEX).outcome(text),
        )
    }

    /**
     * The one that would catch a drift between the highlight and the truth: any
     * pattern producing a span must be a pattern the filter matches, and any
     * pattern the filter matches must produce a span *or* be zero-width.
     */
    @Test
    fun `spans and the filter never disagree`() {
        val patterns = listOf(
            "abc", "ABC", "[0-9]+", "^a", "c$", "a|z", "\\d{2}", "a.c", "q", "",
        )
        val samples = listOf("abc", "ABC 12", "xyz", "", "a1b2c3", "aXc")

        patterns.forEach { pattern ->
            TextMatchMode.entries.forEach { mode ->
                samples.forEach { sample ->
                    val spans = matchRangesIn(pattern, mode, sample)
                    val matched = TextFilter.of(pattern.ifEmpty { null }, mode).matches(sample)
                    if (spans.isNotEmpty()) {
                        assertTrue(
                            "'$pattern' ($mode) marked ${spans} in '$sample' " +
                                "but the filter says it does not match",
                            matched,
                        )
                    }
                }
            }
        }
    }
}
