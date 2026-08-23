package app.phueber.trigly.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.TextMatchMode
import org.junit.Assert.assertEquals
import org.junit.Rule as JUnitRule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The pattern tester, driven through the field that opens it.
 *
 * What is worth asserting is the reporting, because that is the whole feature: a
 * regex that compiles tells you nothing about whether it matches, and the states
 * that are neither "yes" nor "no" — an empty pattern, a pattern that will not
 * compile — are the ones a naive tester gets wrong by calling them failures.
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
                secondValue = mode.configValue,
            )
        }
        composeRule.onNodeWithText("TEST").performClick()
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

        composeRule.onNodeWithText("MATCHES · 2 HITS").assertIsDisplayed()
    }

    @Test
    fun one_hit_is_reported_in_the_singular() {
        open("[0-9]+")

        composeRule.onNodeWithText("SAMPLE TEXT").performTextReplacement("only 7")

        composeRule.onNodeWithText("MATCHES · 1 HIT").assertIsDisplayed()
    }

    @Test
    fun a_pattern_that_does_not_match_says_so() {
        open("[0-9]+")

        composeRule.onNodeWithText("SAMPLE TEXT").performTextReplacement("no digits here")

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

        composeRule.onNodeWithText("MATCHES · 1 HIT").assertIsDisplayed()
    }

    /** Anchors are the reason the hint mentions them: `contains` is the default. */
    @Test
    fun an_unanchored_pattern_matches_inside_a_longer_string() {
        open("wor")

        composeRule.onNodeWithText("SAMPLE TEXT").performTextReplacement("hello world")

        composeRule.onNodeWithText("MATCHES · 1 HIT").assertIsDisplayed()
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
