package app.phueber.trigly.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.runtime.CompositionLocalProvider
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.ScopedVariable
import app.phueber.trigly.core.Substitution
import app.phueber.trigly.core.TextSuggestions
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

    /** As `press_captured_button` declares it: a reference is still typeable. */
    private val keptButtonField = ConfigField.Text(
        key = "name",
        label = "Kept as",
        required = true,
        substitution = Substitution.TEXT,
        suggests = TextSuggestions.KEPT_BUTTON_NAMES,
    )

    /**
     * As `set_variable` declares its value field: plain text in the
     * declaration, and an expression only once the mode says "evaluate". What
     * makes it code is the configuration, which is what `previewEncoding`
     * carries. See [ConfigFieldEditor].
     */
    private val expressionField = ConfigField.Text(
        key = "value",
        label = "Value",
        required = true,
        substitution = Substitution.TEXT,
    )

    /** What the chooser's own button says. See [suggestionWording]. */
    private val chooserLabel = "Choose a kept button"

    /** Quotes and brackets, which is what a transformation is likeliest to break. */
    private val typed = "upper(\"a\") == \"A\""

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
        kept: List<KeptButton> = emptyList(),
        previewEncoding: Substitution = field.substitution,
    ) {
        composeRule.setContent {
            TriglyTheme {
                var text by remember { mutableStateOf(value) }
                CompositionLocalProvider(LocalKeptButtons provides { kept }) {
                    ConfigFieldEditor(
                        field = field,
                        value = text,
                        onValueChange = {
                            edits += it
                            text = it
                        },
                        availableVariables = variables,
                        previewEncoding = previewEncoding,
                        describeComponent = describeComponent,
                    )
                }
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

    // --- The kept-button chooser -------------------------------------------------------

    @Test
    fun a_field_that_declares_kept_buttons_offers_the_chooser() {
        setField(keptButtonField, value = null)

        composeRule.onNodeWithText(chooserLabel, ignoreCase = true).assertIsDisplayed()
    }

    @Test
    fun a_field_that_declares_no_source_offers_no_chooser() {
        setField(substitutableField, value = null)

        composeRule.onNodeWithText(chooserLabel, ignoreCase = true).assertDoesNotExist()
    }

    /**
     * Both affordances at once, and they do different jobs: the chooser fills in
     * a name that exists, the variable picker inserts a reference that works one
     * out at run time. One replacing the other would take away half the field.
     */
    @Test
    fun the_chooser_sits_beside_the_variable_picker() {
        setField(keptButtonField, value = null)

        composeRule.onNodeWithText(chooserLabel, ignoreCase = true).assertIsDisplayed()
        composeRule.onNodeWithText("Insert variable", ignoreCase = true).assertIsDisplayed()
    }

    @Test
    fun choosing_a_kept_button_replaces_the_value() {
        setField(
            keptButtonField,
            value = null,
            kept = listOf(KeptButton("bedtime_off", "Kept now")),
        )

        composeRule.onNodeWithText(chooserLabel, ignoreCase = true).performClick()
        composeRule.onNodeWithText("bedtime_off").performClick()

        assertEquals("bedtime_off", edits.last())
    }

    /**
     * The whole value, not an insertion at the cursor. A name is the entire
     * field, so appending to what is already there would produce
     * `bedtime_offwifi_off` from two picks.
     */
    @Test
    fun choosing_replaces_what_was_there_rather_than_adding_to_it() {
        setField(
            keptButtonField,
            value = "wifi_off",
            kept = listOf(KeptButton("bedtime_off", "Kept now")),
        )

        composeRule.onNodeWithText(chooserLabel, ignoreCase = true).performClick()
        composeRule.onNodeWithText("bedtime_off").performClick()

        assertEquals("bedtime_off", edits.last())
    }

    /**
     * The name keeps its own case, because a variable name is compared exactly.
     * The row's headline is uppercased by [PickerRow]; the name is not, which is
     * why it is the row's second line.
     */
    @Test
    fun an_offered_name_is_shown_exactly_as_it_is_stored() {
        setField(
            keptButtonField,
            value = null,
            kept = listOf(KeptButton("bedtime_off", "Kept now")),
        )

        composeRule.onNodeWithText(chooserLabel, ignoreCase = true).performClick()

        composeRule.onNodeWithText("bedtime_off").assertIsDisplayed()
    }

    @Test
    fun the_chooser_says_where_each_name_comes_from() {
        setField(
            keptButtonField,
            value = null,
            kept = listOf(
                KeptButton("bedtime_off", "Kept now"),
                KeptButton("wifi_off", "Kept by the rule Evening"),
            ),
        )

        composeRule.onNodeWithText(chooserLabel, ignoreCase = true).performClick()

        // Uppercased by PickerRow, which is why both of these ignore case.
        composeRule.onNodeWithText("Kept now", ignoreCase = true).assertIsDisplayed()
        composeRule.onNodeWithText("Kept by the rule Evening", ignoreCase = true)
            .assertIsDisplayed()
    }

    /**
     * Empty is ordinary, not a fault: nothing is kept until the keeping rule has
     * run, and nothing survives a restart. So the dialog has to say why and say
     * that typing the name is allowed.
     */
    @Test
    fun an_empty_chooser_explains_both_reasons() {
        setField(keptButtonField, value = null, kept = emptyList())

        composeRule.onNodeWithText(chooserLabel, ignoreCase = true).performClick()

        composeRule.onNodeWithText("No name to offer yet", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("type the name yourself", substring = true).assertIsDisplayed()
    }

    // --- The field whose value is about to be run --------------------------------------

    /**
     * The one thing colouring a field can break, and the reason this pair is on
     * a device rather than on the tokenizer: the transformation inserts and
     * removes nothing, so what is typed must reach the config exactly as typed.
     * An offset mapping that lied would show up here as dropped or reordered
     * characters.
     */
    @Test
    fun typing_an_expression_reaches_the_config_unchanged() {
        setField(expressionField, value = null, previewEncoding = Substitution.EXPRESSION)

        composeRule.onNodeWithText("VALUE", substring = true, ignoreCase = true)
            .performTextInput(typed)

        assertEquals(typed, edits.last())
    }

    /**
     * The same field, drawn as prose because the mode above it says so. Both
     * ways have to type through identically: the colour is the only difference
     * between them, and a difference in what is stored would be a bug that only
     * one mode shows.
     */
    @Test
    fun the_same_field_types_the_same_when_it_is_not_code() {
        setField(expressionField, value = null)

        composeRule.onNodeWithText("VALUE", substring = true, ignoreCase = true)
            .performTextInput(typed)

        assertEquals(typed, edits.last())
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
