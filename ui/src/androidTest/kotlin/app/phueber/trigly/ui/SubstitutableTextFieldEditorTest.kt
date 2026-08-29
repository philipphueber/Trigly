package app.phueber.trigly.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.runtime.CompositionLocalProvider
import app.phueber.trigly.core.ConditionalHelp
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.FieldCondition
import app.phueber.trigly.core.ScopedVariable
import app.phueber.trigly.core.Substitution
import app.phueber.trigly.core.TextSuggestions
import app.phueber.trigly.core.VariableScope
import app.phueber.trigly.core.VariableSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule as JUnitRule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How far [SubstitutableTextFieldEditorTest.growthTriggeringWordCount]
 * searches before concluding an expression box never grows at all, rather
 * than that it just needed a few more words.
 */
private const val MAX_GROWTH_SEARCH_WORDS = 4_096

/**
 * Enough words to pass the eight-line bound on any device this runs on.
 * [SubstitutableTextFieldEditorTest.an_expression_field_stops_growing_once_it_reaches_its_bound]
 * proves that by doubling it and finding the height unchanged, rather than by
 * trusting this number alone.
 */
private const val BOUND_SEARCH_WORDS = 200

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

    // --- The box that must not clip an expression --------------------------------------

    /**
     * [count] short, distinct words. Distinct rather than one word repeated, so
     * nothing about a single glyph shape or a font's ligature table can make the
     * growth below an accident of one specific character.
     */
    private fun wordsOf(count: Int): String = (1..count).joinToString(" ") { "word$it" }

    /**
     * Doubled each step rather than searched linearly, so finding the point past
     * which the box actually grows costs a handful of measurements regardless of
     * how wide the device is. [MAX_GROWTH_SEARCH_WORDS] is the point past which a
     * search that has found nothing means the box does not grow at all, which is
     * a real failure rather than a search that gave up too soon.
     */
    private fun growthTriggeringWordCount(restHeight: Dp, heightFor: (String) -> Dp): Int {
        var wordCount = 4
        while (wordCount <= MAX_GROWTH_SEARCH_WORDS) {
            if (heightFor(wordsOf(wordCount)) > restHeight) return wordCount
            wordCount *= 2
        }
        return wordCount
    }

    @Test
    fun an_expression_field_starts_taller_than_a_plain_field() {
        composeRule.setContent {
            TriglyTheme {
                Column {
                    ConfigFieldEditor(
                        field = expressionField,
                        value = null,
                        onValueChange = {},
                        previewEncoding = Substitution.EXPRESSION,
                    )
                    ConfigFieldEditor(field = plainField, value = null, onValueChange = {})
                }
            }
        }

        val expressionHeight = composeRule.onNodeWithText("VALUE", substring = true, ignoreCase = true)
            .getUnclippedBoundsInRoot().height
        val plainHeight = composeRule.onNodeWithText("CONTAINS", substring = true, ignoreCase = true)
            .getUnclippedBoundsInRoot().height

        assertTrue(
            "an expression box starts as several lines, not the one a plain field gets",
            expressionHeight > plainHeight,
        )
    }

    /**
     * How content past the starting height is found, rather than assumed: a
     * fixed string that wraps to four lines on one screen can wrap to two on
     * another, and the connected gate runs this on more than one device. So
     * this keeps typing more words, a doubling amount at a time, until the box
     * has actually grown past its own rest height, and only then checks the
     * claim the wrapping fixed — that a plain field fed the identical value
     * still does not.
     */
    @Test
    fun an_expression_field_grows_once_its_content_passes_the_starting_height() {
        composeRule.setContent {
            TriglyTheme {
                Column {
                    ConfigFieldEditor(
                        field = expressionField,
                        value = null,
                        onValueChange = {},
                        previewEncoding = Substitution.EXPRESSION,
                    )
                    ConfigFieldEditor(field = plainField, value = null, onValueChange = {})
                }
            }
        }

        fun expressionHeightFor(value: String): Dp {
            composeRule.onNodeWithText("VALUE", substring = true, ignoreCase = true)
                .performTextReplacement(value)
            return composeRule.onNodeWithText("VALUE", substring = true, ignoreCase = true)
                .getUnclippedBoundsInRoot().height
        }

        val expressionRest = expressionHeightFor("")
        val wordCount = growthTriggeringWordCount(expressionRest) { expressionHeightFor(it) }
        val expressionGrown = expressionHeightFor(wordsOf(wordCount))

        assertTrue(
            "typing $wordCount words never grew the box past its $expressionRest rest height",
            expressionGrown > expressionRest,
        )

        val plainRest = composeRule.onNodeWithText("CONTAINS", substring = true, ignoreCase = true)
            .getUnclippedBoundsInRoot().height
        composeRule.onNodeWithText("CONTAINS", substring = true, ignoreCase = true)
            .performTextReplacement(wordsOf(wordCount))
        val plainAfter = composeRule.onNodeWithText("CONTAINS", substring = true, ignoreCase = true)
            .getUnclippedBoundsInRoot().height

        assertEquals(
            "the same value in a plain field scrolls sideways rather than growing",
            plainRest,
            plainAfter,
        )
    }

    /**
     * [BOUND_SEARCH_WORDS] is chosen to be enough to pass the eight-line bound on
     * any device this runs on, not a device-specific guess: it is measured
     * against the box's own height, by asserting growth happened at all, rather
     * than assumed to wrap to a specific line count. Doubling that same word
     * count and finding the height unchanged is what proves the bound holds
     * while the content keeps growing, rather than merely being long enough
     * that this build happens not to have grown further yet.
     */
    @Test
    fun an_expression_field_stops_growing_once_it_reaches_its_bound() {
        composeRule.setContent {
            TriglyTheme {
                ConfigFieldEditor(
                    field = expressionField,
                    value = null,
                    onValueChange = {},
                    previewEncoding = Substitution.EXPRESSION,
                )
            }
        }

        fun expressionHeightFor(value: String): Dp {
            composeRule.onNodeWithText("VALUE", substring = true, ignoreCase = true)
                .performTextReplacement(value)
            return composeRule.onNodeWithText("VALUE", substring = true, ignoreCase = true)
                .getUnclippedBoundsInRoot().height
        }

        val restHeight = expressionHeightFor("")
        val boundedHeight = expressionHeightFor(wordsOf(BOUND_SEARCH_WORDS))
        val pastBoundHeight = expressionHeightFor(wordsOf(BOUND_SEARCH_WORDS * 2))

        assertTrue(
            "$BOUND_SEARCH_WORDS words never grew the box past its $restHeight rest height",
            boundedHeight > restHeight,
        )
        assertEquals(
            "height must stop increasing once the bound is reached, even though the " +
                "content typed kept growing",
            boundedHeight,
            pastBoundHeight,
        )
    }

    // --- Help that follows the mode a sibling field chose ---------------------------------

    private val modeAwareField = ConfigField.Text(
        key = "value",
        label = "Value",
        help = "This can include another variable.",
        helpWhen = listOf(
            ConditionalHelp(
                condition = FieldCondition(key = "mode", value = "add"),
                help = "Adding needs a plain number.",
            ),
            ConditionalHelp(
                condition = FieldCondition(key = "mode", value = "evaluate"),
                help = "Evaluating runs this as an expression.",
            ),
        ),
    )

    private fun setModeAwareField(mode: String?) {
        composeRule.setContent {
            TriglyTheme {
                ConfigFieldEditor(
                    field = modeAwareField,
                    value = null,
                    onValueChange = {},
                    companions = mode?.let { mapOf("mode" to it) } ?: emptyMap(),
                )
            }
        }
    }

    @Test
    fun help_names_only_the_add_specific_sentence_in_add_mode() {
        setModeAwareField("add")

        composeRule.onNodeWithText("Adding needs a plain number.", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Evaluating runs this", substring = true).assertDoesNotExist()
    }

    @Test
    fun help_names_only_the_evaluate_specific_sentence_in_evaluate_mode() {
        setModeAwareField("evaluate")

        composeRule.onNodeWithText("Evaluating runs this", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Adding needs a plain number.", substring = true).assertDoesNotExist()
    }

    @Test
    fun help_names_neither_mode_specific_sentence_when_no_mode_matches() {
        setModeAwareField("set")

        composeRule.onNodeWithText("This can include another variable.").assertIsDisplayed()
        composeRule.onNodeWithText("Adding needs a plain number.", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Evaluating runs this", substring = true).assertDoesNotExist()
    }

    // --- A long help collapses; a short one does not ---------------------------------------

    private val shortHelp = "Leave blank to match any device."

    private val longHelp = "Some apps draw their own notification buttons. Android then offers " +
        "no way to press them directly. This setting opens the notification shade instead, and " +
        "taps the button by name. It needs accessibility access. It briefly shows the shade on " +
        "screen. Turn it on only when you need it."

    private val longHelpFirstSentence = "Some apps draw their own notification buttons."

    private val shortHelpField = ConfigField.Text(key = "short", label = "Short", help = shortHelp)
    private val longHelpField = ConfigField.Text(key = "long", label = "Long", help = longHelp)

    @Test
    fun a_short_help_shows_in_full_with_no_toggle() {
        composeRule.setContent {
            TriglyTheme { ConfigFieldEditor(field = shortHelpField, value = null, onValueChange = {}) }
        }

        composeRule.onNodeWithText(shortHelp).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(HINT_EXPAND_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun a_long_help_collapses_to_its_first_sentence_until_expanded() {
        composeRule.setContent {
            TriglyTheme { ConfigFieldEditor(field = longHelpField, value = null, onValueChange = {}) }
        }

        composeRule.onNodeWithText(longHelpFirstSentence).assertIsDisplayed()
        composeRule.onNodeWithText(longHelp).assertDoesNotExist()

        composeRule.onNodeWithContentDescription(HINT_EXPAND_DESCRIPTION).performClick()

        composeRule.onNodeWithText(longHelp).assertIsDisplayed()
    }
}
