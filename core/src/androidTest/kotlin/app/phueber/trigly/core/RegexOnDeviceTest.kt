package app.phueber.trigly.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every regular expression this app runs, run on the platform it ships to.
 *
 * **This file exists because of a bug the JVM tests could not see.** Both regex
 * paths, `contains(a, b, "regex")` in [evaluateExpression] and
 * [TextFilter]'s `regex` mode, hand the engine a [BudgetedText] rather than the
 * text itself, to count the characters a search reads. Android's `Matcher`
 * converts its input to a `String` when it is handed anything else, and before
 * [BudgetedText] overrode `toString` that conversion produced
 * `Object.toString()`. So on a device every pattern searched
 * "app.phueber.trigly.core.BudgetedText@1a2b3c4d" instead of the text, and
 * matched or missed on the hex digits of a hash code. A `[0-9]+` search over
 * `12 and 34` reported a match at index 37.
 *
 * Not one of 1852 JVM tests could fail on that, because the JVM's `Matcher`
 * reads the `CharSequence` as given. These tests are the guard: they assert
 * only that a search on ART answers what the same search answers on the JVM,
 * which is the one property the JVM cannot check for itself.
 *
 * They deliberately do **not** assert the read bound. It does not work on
 * Android at all, for the same reason: `BudgetedText.get` is never called
 * there. See `docs/todo.md` T24.
 */
@RunWith(AndroidJUnit4::class)
class RegexOnDeviceTest {

    // --- The expression language's contains(a, b, "regex") -------------------------

    private fun expression(source: String): String =
        when (val outcome = evaluateExpression(source)) {
            is ExpressionOutcome.Ok -> outcome.value
            is ExpressionOutcome.Failed -> throw AssertionError(
                "'$source' did not evaluate on this device: ${outcome.reason}"
            )
        }

    @Test
    fun a_pattern_finds_a_match_in_the_text_it_was_given() {
        assertEquals("true", expression("contains(\"abc123\", \"\\d+\", \"regex\")"))
    }

    /**
     * The case that failed. A hash code is hex, so a pattern looking for digits
     * matched the object's own name when the text went missing.
     */
    @Test
    fun a_digit_pattern_does_not_match_text_that_holds_no_digit() {
        assertEquals("false", expression("contains(\"only letters\", \"\\d+\", \"regex\")"))
    }

    @Test
    fun an_anchor_still_anchors_to_the_real_text() {
        assertEquals("true", expression("contains(\"12 and 34\", \"^12\", \"regex\")"))
        assertEquals("false", expression("contains(\"12 and 34\", \"^34\", \"regex\")"))
    }

    // --- A trigger's text filter ---------------------------------------------------

    @Test
    fun a_filter_matches_the_candidate_and_not_its_wrapper() {
        val filter = TextFilter.of("[0-9]+", TextMatchMode.REGEX)

        assertTrue(filter.matches("12 and 34"))
        assertFalse(filter.matches("no digits here"))
    }

    @Test
    fun a_filter_is_case_insensitive_on_a_device_too() {
        val filter = TextFilter.of("alice", TextMatchMode.REGEX)

        assertTrue(filter.matches("ALICE called"))
    }

    /**
     * The exact assertion that caught it. Index 37 in a nine-character sample
     * was the hash code of the wrapper, so pinning the ranges pins the text.
     */
    @Test
    fun the_highlight_ranges_fall_inside_the_candidate() {
        val ranges = matchRangesIn("[0-9]+", TextMatchMode.REGEX, "12 and 34")

        assertEquals(listOf(0..1, 7..8), ranges)
    }
}
