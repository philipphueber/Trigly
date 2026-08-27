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

    /** A type-qualified entry, as offered when a tree has more than one leaf. */
    private val qualifiedNameVariable = ScopedVariable(
        "bluetooth_connected",
        VariableSpec(
            key = "name",
            label = "Name",
            sample = "Car speakers",
            alwaysPresent = false,
        ),
    )

    /** An action output, as offered to an action below the one that makes it. */
    private val actionOutputVariable = ScopedVariable(
        VariableScope.ACTION,
        VariableSpec(
            key = "value",
            label = "Value stored",
            sample = "4",
            alwaysPresent = false,
        ),
    )

    /** Every value the field reported, in order. */
    private val edits = mutableListOf<String?>()

    private fun setField(
        field: ConfigField.Text,
        value: String?,
        variables: List<ScopedVariable> = listOf(titleVariable, triggerTypeVariable),
        describeComponent: (String) -> String = { it },
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
                    describeComponent = describeComponent,
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

    /**
     * A type-qualified group is headed by the trigger's own name, the one the
     * trigger picker and the rules list already use. It is not a reformatting
     * of its type string. `bluetooth_connected` reads as "Bluetooth device"
     * everywhere else in the app; a heading that called it "Bluetooth connected"
     * here would be the picker describing the same trigger under a name nobody
     * else in the app uses for it.
     */
    @Test
    fun a_type_qualified_heading_uses_the_trigger_s_own_name() {
        setField(
            substitutableField,
            value = null,
            variables = listOf(qualifiedNameVariable),
            describeComponent = { type ->
                if (type == "bluetooth_connected") "Bluetooth device" else type
            },
        )

        composeRule.onNodeWithText("Insert variable", ignoreCase = true).performClick()

        composeRule.onNodeWithText("BLUETOOTH DEVICE").assertIsDisplayed()
        composeRule.onNodeWithText("BLUETOOTH CONNECTED").assertDoesNotExist()
    }

    /**
     * Action scope gets a sentence of its own rather than the bare word
     * "action". What a person has to know about it is when the value exists,
     * which is only after an action earlier in the same rule has run and
     * produced it, and the raw scope word says none of that.
     */
    @Test
    fun the_action_scope_heading_says_where_the_value_comes_from() {
        setField(substitutableField, value = null, variables = listOf(actionOutputVariable))

        composeRule.onNodeWithText("Insert variable", ignoreCase = true).performClick()

        composeRule.onNodeWithText("PRODUCED BY AN EARLIER ACTION IN THIS RULE").assertIsDisplayed()
    }
}
