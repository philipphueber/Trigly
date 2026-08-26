package app.phueber.trigly.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.ScopedVariable
import app.phueber.trigly.core.Substitution
import app.phueber.trigly.core.VariableScope
import app.phueber.trigly.core.VariableSpec
import org.junit.Assert.assertEquals
import org.junit.Rule as JUnitRule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The picker and the preview `docs/variables.md` section 12 adds to a
 * substitutable [ConfigField.Text].
 *
 * What is worth checking on a device rather than in a `:core` unit test is the
 * wiring: that the picker is offered only where the field declares it, that a
 * pick lands in the field's own key the same way typing does, and that the
 * preview reads what `Template.substitute` would actually produce rather than
 * something this screen computed itself.
 */
@RunWith(AndroidJUnit4::class)
class SubstitutableTextFieldEditorTest {

    @get:JUnitRule
    val composeRule = createComposeRule()

    private val substitutableField = ConfigField.Text(
        key = "text",
        label = "Message",
        substitution = Substitution.TEXT,
    )

    private val plainField = ConfigField.Text(
        key = "filter",
        label = "Contains",
    )

    /** The first sentence of [VariableRow]'s mark for `alwaysPresent = false`. */
    private val sometimesEmptyMark = "This value is sometimes empty."

    private val titleVariable = ScopedVariable(
        VariableScope.TRIGGER,
        VariableSpec(
            key = "title",
            label = "Title",
            sample = "New message",
            alwaysPresent = false,
        ),
    )

    private val triggerTypeVariable = ScopedVariable(
        VariableScope.EVENT,
        VariableSpec(
            key = VariableScope.EVENT_TYPE,
            label = "Trigger type",
            sample = "notification_posted",
        ),
    )

    /** Every value the field reported, in order. */
    private val edits = mutableListOf<String?>()

    private fun setField(
        field: ConfigField.Text,
        value: String?,
        variables: List<ScopedVariable> = listOf(titleVariable, triggerTypeVariable),
    ) {
        composeRule.setContent {
            TriglyTheme {
                var text by remember { mutableStateOf(value) }
                ConfigFieldEditor(
                    field = field,
                    value = text,
                    onValueChange = {
                        edits += it
                        text = it
                    },
                    availableVariables = variables,
                )
            }
        }
    }

    @Test
    fun a_field_that_accepts_variables_offers_the_picker() {
        setField(substitutableField, value = null)

        composeRule.onNodeWithText("Insert variable", ignoreCase = true).assertIsDisplayed()
    }

    @Test
    fun a_field_with_no_substitution_offers_no_picker() {
        setField(plainField, value = null)

        composeRule.onNodeWithText("Insert variable", ignoreCase = true).assertDoesNotExist()
    }

    @Test
    fun picking_an_entry_inserts_its_reference() {
        setField(substitutableField, value = null)

        composeRule.onNodeWithText("Insert variable", ignoreCase = true).performClick()
        composeRule.onNodeWithText(titleVariable.reference).performClick()

        assertEquals(titleVariable.reference, edits.last())
    }

    @Test
    fun an_entry_that_can_be_absent_is_marked() {
        setField(substitutableField, value = null)

        composeRule.onNodeWithText("Insert variable", ignoreCase = true).performClick()

        composeRule.onNodeWithText(sometimesEmptyMark, substring = true).assertIsDisplayed()
    }

    @Test
    fun an_entry_that_is_always_present_carries_no_mark() {
        // Only the always-present variable is offered here, so if the mark
        // shows up at all it can only be a false positive on this one row.
        setField(substitutableField, value = null, variables = listOf(triggerTypeVariable))

        composeRule.onNodeWithText("Insert variable", ignoreCase = true).performClick()

        composeRule.onNodeWithText(triggerTypeVariable.spec.label).assertIsDisplayed()
        composeRule.onNodeWithText(sometimesEmptyMark, substring = true).assertDoesNotExist()
    }

    @Test
    fun the_preview_shows_the_resolved_sample() {
        setField(substitutableField, value = "New: {{trigger.title}}")

        composeRule.onNodeWithText("Sample: New: New message").assertIsDisplayed()
    }

    @Test
    fun the_preview_explains_a_reference_this_rule_does_not_offer() {
        setField(substitutableField, value = "{{trigger.nope}}")

        val reason = "Trigly has no variable named {{trigger.nope}}."
        composeRule.onNodeWithText(reason, substring = true).assertIsDisplayed()
    }

    @Test
    fun a_value_with_no_reference_shows_no_preview() {
        setField(substitutableField, value = "just text")

        composeRule.onNodeWithText("Sample:", substring = true).assertDoesNotExist()
    }
}
