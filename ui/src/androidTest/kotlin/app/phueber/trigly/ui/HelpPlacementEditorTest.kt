package app.phueber.trigly.ui

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.phueber.trigly.actions.actionFactories
import app.phueber.trigly.core.ComponentFactory
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.HelpPlacement
import app.phueber.trigly.core.NotificationController
import app.phueber.trigly.core.RuleRunnerHandle
import app.phueber.trigly.triggers.AlarmManagerScheduler
import app.phueber.trigly.triggers.triggerFactories
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule as JUnitRule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Where a field's help text lands, which is the whole of what
 * [ConfigField.helpPlacement] decides.
 *
 * Checked by comparing the two texts' positions on screen, not by reading the
 * declaration back: the declaration is one enum constant, and asserting that
 * it says what it says proves nothing. The fault this guards against is a
 * layout one. `day_of_week` draws seven flags in a row and the sentence that
 * says what checking them does belongs to all seven, so hung below the first
 * one it reads as a caption for Monday and puts a paragraph between Monday and
 * the six days it describes.
 *
 * The four runs are checked through their **real** factories rather than
 * through stand-in fields, because a hand-written field can only show that the
 * mechanism works. Only the shipped declaration can show that the screen
 * someone complained about was actually changed.
 */
@RunWith(AndroidJUnit4::class)
class HelpPlacementEditorTest {

    @get:JUnitRule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // Built the same way `ConfigSchemaContractTest` builds them, for the same
    // reason: `:ui` is the only module that can see both `:triggers` and
    // `:actions`, and a run looked up by type cannot go stale the way a copy
    // of the field list would.
    private val factories: List<ComponentFactory> =
        triggerFactories(context, AlarmManagerScheduler(context)) +
            actionFactories(
                context,
                AlarmManagerScheduler(context),
                RuleRunnerHandle(),
                NotificationController.Unavailable,
            )

    private fun fieldOf(type: String, label: String): ConfigField {
        val factory = requireNotNull(factories.firstOrNull { it.type == type }) {
            "no factory of type '$type'"
        }
        return requireNotNull(factory.configFields.firstOrNull { it.label == label }) {
            "'$type' declares no field labelled '$label'"
        }
    }

    private fun setField(field: ConfigField) {
        composeRule.setContent {
            TriglyTheme {
                ConfigFieldEditor(field = field, value = null, onValueChange = {})
            }
        }
    }

    /** The top edge of the first node whose text starts with [text]. */
    private fun topOf(text: String) =
        composeRule.onNodeWithText(text, substring = true).getUnclippedBoundsInRoot().top

    /**
     * Renders one run's first field and reports whether its sentence heads it.
     *
     * [help] is matched as a prefix, because a sentence past
     * `HINT_COLLAPSE_THRESHOLD` is drawn as its first sentence with a control
     * to unfold the rest. What is being checked here is where the text sits,
     * not how much of it is shown.
     */
    private fun assertHelpHeadsTheRun(field: ConfigField, help: String, label: String) {
        setField(field)
        assertTrue(
            "'$label' should follow its description, not be captioned by it",
            topOf(help) < topOf(label),
        )
    }

    @Test
    fun help_that_explains_its_own_field_stays_under_it() {
        setField(
            ConfigField.Flag(
                key = "vibrate",
                label = "Vibrate",
                help = "Silent phones still vibrate.",
            ),
        )

        assertTrue(
            "the default placement should leave help below its control",
            topOf("Silent phones") > topOf("VIBRATE"),
        )
    }

    @Test
    fun the_day_of_week_condition_heads_its_seven_flags() {
        assertHelpHeadsTheRun(
            field = fieldOf("day_of_week", "Monday"),
            help = "Which days this holds on.",
            label = "MONDAY",
        )
    }

    @Test
    fun the_month_condition_heads_its_twelve_flags() {
        assertHelpHeadsTheRun(
            field = fieldOf("month", "January"),
            help = "Which months this holds in.",
            label = "JANUARY",
        )
    }

    @Test
    fun the_daily_time_trigger_heads_its_seven_flags() {
        assertHelpHeadsTheRun(
            field = fieldOf("time_of_day", "Monday"),
            help = "Which days this fires on.",
            label = "MONDAY",
        )
    }

    @Test
    fun the_intent_extras_head_their_name_and_value_boxes() {
        // Not a flag run: the same fault, in a run of text boxes, which is why
        // `ConfigField.Text` takes the placement too.
        assertHelpHeadsTheRun(
            field = fieldOf("fire_intent", "Extra 1 name"),
            help = "A named value the other app reads",
            label = "EXTRA 1 NAME",
        )
    }

    @Test
    fun a_heading_is_only_declared_where_there_is_something_to_head() {
        // A run builds its fields in a loop and gives the sentence to the first
        // of them. Declaring the placement on all of them instead would be a
        // setting with no effect on every field but one, and the next person to
        // read it would have to work out that it does nothing.
        val empty = factories.flatMap { factory ->
            factory.configFields
                .filter { it.helpPlacement == HelpPlacement.ABOVE && it.help == null }
                .map { "${factory.type}.${it.key}" }
        }
        assertEquals("these declare a heading with no help to draw: $empty", emptyList<String>(), empty)
    }
}
