package app.phueber.trigly.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.regexErrorOrNull
import org.junit.Assert.assertEquals
import org.junit.Rule as JUnitRule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one field kind that renders two config keys from one control.
 *
 * What is worth checking on a device rather than in a unit test is the wiring:
 * that the mode reaches the rule under its *own* key while the pattern keeps
 * going under the field's, and that switching the mode changes what the box below
 * does — a broken pattern is an error in regex mode and ordinary text in the
 * other. `TextFilterTest` already covers what the modes mean; this covers that
 * the editor is actually driving them.
 */
@RunWith(AndroidJUnit4::class)
class TextPatternFieldEditorTest {

    @get:JUnitRule
    val composeRule = createComposeRule()

    private val field = ConfigField.TextPattern(
        key = "textContains",
        label = "Title or text contains",
        blankMeaning = "Leave blank to match every notification",
    )

    /** Every edit the field reported, as the (key, value) pairs a rule stores. */
    private val edits = mutableListOf<Pair<String, String?>>()

    /**
     * Holds both keys in state, the way the rule draft does.
     *
     * The field renders what it is handed, so a test that only captured the
     * callbacks would never see the mode switch take effect — and `setContent`
     * may only be called once per test, which is why this takes the starting
     * values rather than being called twice.
     */
    private fun setField(value: String?, mode: String?) {
        composeRule.setContent {
            TriglyTheme {
                var pattern by remember { mutableStateOf(value) }
                var modeValue by remember { mutableStateOf(mode) }
                ConfigFieldEditor(
                    field = field,
                    value = pattern,
                    onValueChange = {
                        edits += field.key to it
                        pattern = it
                    },
                    companions = mapOf(field.modeKey to modeValue),
                    onCompanionChange = { _, value ->
                        edits += field.modeKey to value.orEmpty()
                        modeValue = value.orEmpty()
                    },
                )
            }
        }
    }

    @Test
    fun both_modes_are_offered_next_to_the_label() {
        setField(value = null, mode = null)

        composeRule.onNodeWithText("TITLE OR TEXT CONTAINS").assertIsDisplayed()
        composeRule.onNodeWithText("CONTAINS").assertIsDisplayed()
        composeRule.onNodeWithText("REGEX").assertIsDisplayed()
    }

    @Test
    fun a_rule_with_no_stored_mode_shows_as_contains() {
        // The compatibility case: every rule saved before the mode key existed.
        setField(value = "alice", mode = null)

        composeRule.onNodeWithText("CONTAINS").assertIsSelected()
        composeRule.onNodeWithText("REGEX").assertIsNotSelected()
    }

    @Test
    fun a_stored_mode_is_the_selected_one() {
        setField(value = "^alice", mode = "regex")

        composeRule.onNodeWithText("REGEX").assertIsSelected()
        composeRule.onNodeWithText("CONTAINS").assertIsNotSelected()
    }

    @Test
    fun switching_the_mode_writes_the_mode_key_and_leaves_the_pattern_alone() {
        setField(value = "alice", mode = null)

        composeRule.onNodeWithText("REGEX").performClick()

        assertEquals(listOf(field.modeKey to "regex"), edits.toList())
        composeRule.onNodeWithText("REGEX").assertIsSelected()
    }

    @Test
    fun the_pattern_is_reported_under_the_fields_own_key() {
        setField(value = null, mode = "regex")

        composeRule.onNode(hasSetTextAction()).performTextReplacement("^alice")

        assertEquals(field.key to "^alice", edits.last())
    }

    @Test
    fun clearing_the_pattern_reports_absence_rather_than_an_empty_string() {
        // Blank means "match anything" here, and the config map says that by not
        // holding the key at all.
        setField(value = "alice", mode = null)

        composeRule.onNode(hasSetTextAction()).performTextReplacement("")

        assertEquals(field.key to null, edits.last())
    }

    @Test
    fun a_broken_pattern_is_explained_in_regex_mode() {
        setField(value = "a(b", mode = "regex")

        // The engine's own wording, computed here rather than hardcoded: the
        // point is that what the editor shows is what the factory would throw.
        val expected = requireNotNull(regexErrorOrNull("a(b"))
        composeRule.onNodeWithText(expected).assertIsDisplayed()
    }

    @Test
    fun the_same_pattern_is_unremarkable_as_a_substring() {
        setField(value = "a(b", mode = "contains")

        val message = requireNotNull(regexErrorOrNull("a(b"))
        composeRule.onNodeWithText(message).assertDoesNotExist()
    }

    @Test
    fun switching_to_regex_surfaces_a_problem_that_was_not_one_before() {
        setField(value = "a(b", mode = "contains")
        val message = requireNotNull(regexErrorOrNull("a(b"))

        composeRule.onNodeWithText(message).assertDoesNotExist()
        composeRule.onNodeWithText("REGEX").performClick()
        composeRule.onNodeWithText(message).assertIsDisplayed()
    }

    @Test
    fun the_blank_meaning_is_shown_while_the_field_is_empty() {
        setField(value = null, mode = null)

        composeRule.onNodeWithText("Leave blank to match every notification")
            .assertIsDisplayed()
    }
}
