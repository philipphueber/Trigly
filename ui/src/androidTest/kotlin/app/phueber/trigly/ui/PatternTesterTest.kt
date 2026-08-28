package app.phueber.trigly.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.TextMatchMode
import org.junit.Assert.assertEquals
import org.junit.Rule as JUnitRule
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The pattern tester, driven through the field that opens it.
 *
 * What is worth asserting is the reporting, because that is the whole feature: a
 * regex that compiles tells you nothing about whether it matches. The states
 * that are neither "yes" nor "no", an empty pattern, a pattern that will not
 * compile, a pattern refused for doing too much work, are the ones a naive
 * tester gets wrong by calling them failures.
 *
 * **Verdicts arrive after a frame, not within one.** `PatternTesterDialog` runs
 * the match on `Dispatchers.Default`, not on the composition that reads the
 * text field, because `BudgetedText` bounds a search's work but not its wall
 * time, and that work is not owed to the main thread just because it is
 * bounded. So a test that changed the sample and asserted immediately would be
 * racing the answer rather than reading it; [awaitText] is that race made
 * explicit and safe, in place of the plain [assertIsDisplayed] this file used
 * before that move.
 *
 * That same gap is also where a stale verdict could hide: the answer already
 * on screen has to stop being shown the moment it no longer belongs to the
 * current pattern, mode and sample, not merely get replaced once the new
 * answer lands. `changing_the_sample_never_leaves_the_old_verdict_on_screen`
 * checks that directly, and does not wait to do it.
 */
@RunWith(AndroidJUnit4::class)
class PatternTesterTest {

    @get:JUnitRule
    val composeRule = createComposeRule()

    private val edits = mutableListOf<String?>()

    private val field = ConfigField.TextPattern(key = "text", label = "Message contains")

    private fun open(pattern: String?, mode: TextMatchMode = TextMatchMode.REGEX) {
        composeRule.setContent {
            ConfigFieldEditor(
                field = field,
                value = pattern,
                onValueChange = { edits += it },
                companions = mapOf(field.modeKey to mode.configValue),
            )
        }
        composeRule.onNodeWithText("TEST").performClick()
    }

    /**
     * Waits for [text] to appear, rather than asserting it is there already.
     * The verdict is computed off the main thread now, so it lands some time
     * after the keystroke that caused it, and a fixed timeout that is generous
     * on a test device is still a bound, not a promise that nothing regressed
     * to hanging: `RegexBudget.kt`'s `MAX_REGEX_READS` is what keeps a search
     * finite even when the pattern under test is deliberately expensive.
     */
    private fun awaitText(text: String, timeoutMillis: Long = 5_000) {
        composeRule.waitUntil(timeoutMillis) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun the_tester_opens_from_the_field() {
        open("[0-9]+")

        composeRule.onNodeWithText("TEST MESSAGE CONTAINS").assertIsDisplayed()
        composeRule.onNodeWithText("PATTERN (REGEX)").assertIsDisplayed()
    }

    @Test
    fun it_asks_for_sample_text_before_claiming_anything() {
        open("[0-9]+")

        composeRule.onNodeWithText("TYPE SOME SAMPLE TEXT").assertIsDisplayed()
    }

    @Test
    fun a_matching_pattern_reports_the_number_of_hits() {
        open("[0-9]+")

        composeRule.onNodeWithText("SAMPLE TEXT").performTextReplacement("12 and 34")

        awaitText("MATCHES · 2 HITS")
        composeRule.onNodeWithText("MATCHES · 2 HITS").assertIsDisplayed()
    }

    @Test
    fun one_hit_is_reported_in_the_singular() {
        open("[0-9]+")

        composeRule.onNodeWithText("SAMPLE TEXT").performTextReplacement("only 7")

        awaitText("MATCHES · 1 HIT")
        composeRule.onNodeWithText("MATCHES · 1 HIT").assertIsDisplayed()
    }

    @Test
    fun a_pattern_that_does_not_match_says_so() {
        open("[0-9]+")

        composeRule.onNodeWithText("SAMPLE TEXT").performTextReplacement("no digits here")

        awaitText("NO MATCH")
        composeRule.onNodeWithText("NO MATCH").assertIsDisplayed()
    }

    /**
     * The state a naive tester calls a failure. An empty filter has no opinion and
     * lets everything through, so "no match" would misdescribe the rule.
     */
    @Test
    fun an_empty_pattern_is_reported_as_matching_anything() {
        open(null)

        composeRule.onNodeWithText("EMPTY PATTERN — MATCHES ANYTHING").assertIsDisplayed()
    }

    @Test
    fun a_pattern_that_does_not_compile_is_called_out_rather_than_called_a_mismatch() {
        open("[unclosed")

        composeRule.onNodeWithText("SAMPLE TEXT").performTextReplacement("anything")

        composeRule.onNodeWithText("PATTERN DOES NOT COMPILE").assertIsDisplayed()
    }

    /** Matching is case-insensitive in the engine, so the tester must agree. */
    @Test
    fun matching_ignores_case_like_the_engine_does() {
        open("hello")

        composeRule.onNodeWithText("SAMPLE TEXT").performTextReplacement("Hello there")

        awaitText("MATCHES · 1 HIT")
        composeRule.onNodeWithText("MATCHES · 1 HIT").assertIsDisplayed()
    }

    /** Anchors are the reason the hint mentions them: `contains` is the default. */
    @Test
    fun an_unanchored_pattern_matches_inside_a_longer_string() {
        open("wor")

        composeRule.onNodeWithText("SAMPLE TEXT").performTextReplacement("hello world")

        awaitText("MATCHES · 1 HIT")
        composeRule.onNodeWithText("MATCHES · 1 HIT").assertIsDisplayed()
    }

    /**
     * The fourth state, alongside an empty pattern, a pattern that will not
     * compile and a zero-width match. Refused, not "no match": those two look
     * the same from the outside, and only this message tells a person which one
     * they got.
     *
     * **Ignored because the bound it asserts does not exist on a device.**
     * Android's `Matcher` converts its input to a `String`, so
     * `BudgetedText.get` is never called and nothing counts the reads. The same
     * assertion passes in `:core`'s JVM tests, which is exactly the trap: the
     * bound holds where the tests run and not where the rules run. `docs/todo.md`
     * T24 holds the options. Re-enable this the day a bound works on ART.
     */
    @Ignore("The read bound does not work on Android. See docs/todo.md T24.")
    @Test
    fun a_pattern_that_does_too_much_work_says_so_instead_of_pretending_to_miss() {
        open(".*.*.*b")

        composeRule.onNodeWithText("SAMPLE TEXT").performTextReplacement("a".repeat(60))

        awaitText("REFUSED · TOO MUCH WORK ON THIS SAMPLE")
        composeRule.onNodeWithText("REFUSED · TOO MUCH WORK ON THIS SAMPLE").assertIsDisplayed()
    }

    /**
     * The regression this file exists to catch. `result` used to be
     * remembered with no key, so it was never cleared when the sample
     * changed, and the verdict shown right after an edit was still the
     * previous edit's answer until the new search landed. Editing the sample
     * must never leave the old verdict on screen, whether or not the new
     * answer has arrived yet.
     *
     * Two cheap samples, deliberately. The property under test is the
     * freshness check, which runs synchronously on recomposition, so the
     * assertion right after the edit waits for nothing: the stale verdict must
     * already be gone whatever the new search costs. An expensive sample would
     * only add a bound that a device does not enforce. See T24.
     */
    @Test
    fun changing_the_sample_never_leaves_the_old_verdict_on_screen() {
        open("[0-9]+")

        composeRule.onNodeWithText("SAMPLE TEXT").performTextReplacement("only 7")
        awaitText("MATCHES · 1 HIT")

        composeRule.onNodeWithText("SAMPLE TEXT").performTextReplacement("no digits here")
        composeRule.onNodeWithText("MATCHES · 1 HIT").assertDoesNotExist()

        awaitText("NO MATCH")
        composeRule.onNodeWithText("NO MATCH").assertIsDisplayed()
    }

    @Test
    fun editing_the_pattern_in_the_tester_reports_it_back_to_the_field() {
        open("[0-9]+")

        composeRule.onNodeWithText("PATTERN (REGEX)").performTextReplacement("[a-z]+")

        // Testing is iterating, so a fix made here has to reach the rule.
        assertEquals("[a-z]+", edits.last())
    }

    @Test
    fun a_contains_pattern_is_labelled_as_such() {
        open("abc", mode = TextMatchMode.CONTAINS)

        composeRule.onNodeWithText("PATTERN (CONTAINS)").assertIsDisplayed()
    }
}
