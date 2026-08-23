package app.phueber.trigly.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.phueber.trigly.actions.actionFactories
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.NotificationController
import app.phueber.trigly.core.Registry
import app.phueber.trigly.triggers.triggerFactories
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule as JUnitRule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The editor's rendering, driven from a plain [EditorState] with no ViewModel —
 * the same stateless-screen approach as [RulesScreenTest]. Uses the real
 * registry, so what is on screen is what the factories actually declare.
 */
@RunWith(AndroidJUnit4::class)
class RuleEditorScreenTest {

    @get:JUnitRule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val registry = Registry(
        triggerFactories = triggerFactories(context),
        actionFactories = actionFactories(context, NotificationController.Unavailable),
    )

    private val configChanges = mutableListOf<Triple<Slot, String, String?>>()
    private val tested = mutableListOf<Int>()
    private var saves = 0
    private var backs = 0

    @Composable
    private fun Editor(state: EditorState) {
        RuleEditorScreen(
            state = state,
            triggerOptions = registry.triggerDescriptors,
            actionOptions = registry.actionDescriptors,
            descriptorFor = { slot, type ->
                when (slot) {
                    Slot.TRIGGER -> registry.triggerDescriptor(type)
                    Slot.ACTION -> registry.actionDescriptor(type)
                }
            },
            onNameChange = {},
            onEnabledChange = {},
            onChooseTrigger = {},
            onAddAction = {},
            onChangeActionType = { _, _ -> },
            onRemoveAction = {},
            onMoveAction = { _, _ -> },
            onConfigChange = { slot, _, key, value -> configChanges += Triple(slot, key, value) },
            onTestAction = { tested += it },
            onSave = { saves++ },
            onDelete = {},
            onBack = { backs++ },
            onResolveRequirement = {},
        )
    }

    @Test
    fun a_new_rule_shows_prompts_and_no_delete() {
        composeRule.setContent { Editor(EditorState(RuleDraft(id = null))) }

        composeRule.onNodeWithText("NEW RULE").assertIsDisplayed()
        composeRule.onNodeWithText("CHOOSE A TRIGGER").assertIsDisplayed()
        composeRule.onNodeWithText("DELETE RULE").assertDoesNotExist()
    }

    @Test
    fun an_existing_rule_can_be_deleted() {
        composeRule.setContent {
            Editor(EditorState(RuleDraft(id = "abc", name = "Existing")))
        }

        composeRule.onNodeWithText("EDIT RULE").assertIsDisplayed()
        composeRule.onNodeWithText("DELETE RULE").assertIsDisplayed()
    }

    @Test
    fun declared_fields_are_rendered_with_display_names_and_warnings() {
        composeRule.setContent {
            Editor(
                EditorState(
                    RuleDraft(
                        id = null,
                        name = "Visual",
                        trigger = ComponentDraft("screen_content"),
                    )
                )
            )
        }

        // The display name, not the type string.
        composeRule.onNodeWithText("TEXT APPEARS ON SCREEN").assertIsDisplayed()
        // A declared field, rendered from the schema. Required, hence the marker.
        composeRule.onNodeWithText("SCREEN CONTAINS *").assertIsDisplayed()
        // The warning that used to live only in KDoc.
        assertTrue(
            composeRule.onAllNodesWithText("The noisiest trigger available", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        )
    }

    @Test
    fun a_numeric_field_shows_its_unit_and_a_choice_shows_its_words() {
        composeRule.setContent {
            Editor(
                EditorState(
                    RuleDraft(
                        id = null,
                        name = "Battery",
                        trigger = ComponentDraft("battery_level", mapOf("direction" to "below")),
                    )
                )
            )
        }

        composeRule.onNodeWithText("THRESHOLD (%) *").assertIsDisplayed()
        composeRule.onNodeWithText("BELOW THE THRESHOLD").assertIsDisplayed()
    }

    @Test
    fun a_blank_optional_field_explains_what_blank_means() {
        composeRule.setContent {
            Editor(
                EditorState(
                    RuleDraft(
                        id = null,
                        name = "Any text",
                        trigger = ComponentDraft("clipboard_changed"),
                    )
                )
            )
        }

        composeRule.onNodeWithText("Leave blank for any copied text").assertIsDisplayed()
    }

    /**
     * The picker kinds say the same thing differently, and on purpose: blankness
     * is shown as the field's current *value* rather than as a hint underneath,
     * because a picker has no empty box for an instruction to sit below. Asserted
     * separately so the two phrasings cannot quietly converge — a picker reading
     * "Leave blank for any device" would be telling the user to do something the
     * control does not offer.
     */
    @Test
    fun a_picker_shows_what_blank_means_as_its_value() {
        composeRule.setContent {
            Editor(
                EditorState(
                    RuleDraft(
                        id = null,
                        name = "Any device",
                        trigger = ComponentDraft("bluetooth_connected"),
                    )
                )
            )
        }

        composeRule.onNodeWithText("ANY DEVICE").assertIsDisplayed()
    }

    @Test
    fun requirements_are_stated_while_the_rule_is_being_built() {
        composeRule.setContent {
            Editor(
                EditorState(
                    RuleDraft(
                        id = null,
                        name = "Notifications",
                        trigger = ComponentDraft("notification_posted"),
                    )
                )
            )
        }

        composeRule
            .onNodeWithText("Needs notification access, granted in system settings")
            .assertIsDisplayed()
    }

    @Test
    fun a_component_this_build_lacks_is_flagged_rather_than_shown_blank() {
        // What an import from a newer version, or a downgrade, produces.
        composeRule.setContent {
            Editor(
                EditorState(
                    RuleDraft(
                        id = "x",
                        name = "From the future",
                        trigger = ComponentDraft("quantum_entanglement"),
                    )
                )
            )
        }

        assertTrue(
            composeRule.onAllNodesWithText("is not available in this version", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        )
    }

    @Test
    fun a_validation_error_is_shown_on_the_form() {
        composeRule.setContent {
            Editor(
                EditorState(
                    draft = RuleDraft(id = null, name = "Bad"),
                    error = "Battery level: battery_level needs 'threshold'",
                )
            )
        }

        composeRule
            .onNodeWithText("Battery level: battery_level needs 'threshold'")
            .assertIsDisplayed()
    }

    @Test
    fun editing_a_field_reports_the_new_value() {
        composeRule.setContent {
            Editor(
                EditorState(
                    RuleDraft(id = null, name = "Toast", actions = listOf(ComponentDraft("toast")))
                )
            )
        }

        composeRule.onNodeWithText("MESSAGE *").performTextReplacement("hello")

        assertEquals(Triple(Slot.ACTION, "text", "hello"), configChanges.last())
    }

    @Test
    fun save_is_reported() {
        composeRule.setContent { Editor(EditorState(RuleDraft(id = null))) }

        composeRule.onNodeWithText("SAVE").performClick()

        assertEquals(1, saves)
    }

    @Test
    fun the_picker_groups_by_category_and_can_be_searched_by_type_string() {
        composeRule.setContent {
            ComponentPickerDialog(
                title = "Choose a trigger",
                options = registry.triggerDescriptors,
                onPick = {},
                onDismiss = {},
            )
        }

        composeRule.onNodeWithText("SEARCH").performTextReplacement("battery_level")

        composeRule.onNodeWithText("BATTERY LEVEL").assertIsDisplayed()
        composeRule.onNodeWithText("SCREEN ON OR OFF").assertDoesNotExist()
    }

    @Test
    fun the_picker_says_so_when_nothing_matches() {
        composeRule.setContent {
            ComponentPickerDialog(
                title = "Choose a trigger",
                options = registry.triggerDescriptors,
                onPick = {},
                onDismiss = {},
            )
        }

        composeRule.onNodeWithText("SEARCH").performTextReplacement("zzzz")

        composeRule.onNodeWithText("Nothing matches \"zzzz\".").assertIsDisplayed()
    }

    @Test
    fun back_is_reported() {
        // The editor is a full screen with no visible way out other than this.
        composeRule.setContent { Editor(EditorState(RuleDraft(id = null))) }

        composeRule.onNodeWithContentDescription("Back").performClick()

        assertEquals(1, backs)
    }

    @Test
    fun the_picker_marks_a_caveat_instead_of_printing_it() {
        // Two thirds of the triggers carry a warning. Printing each one in the
        // list made it unreadable, so the list marks that a caveat exists and
        // the editor states it once the component is chosen.
        val caveated = registry.triggerDescriptors.first { it.warning != null }

        composeRule.setContent {
            ComponentPickerDialog(
                title = "Choose a trigger",
                options = listOf(caveated),
                onPick = {},
                onDismiss = {},
            )
        }

        composeRule.onNodeWithText(caveated.displayName.uppercase()).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(CAVEAT_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText(caveated.warning!!).assertDoesNotExist()
    }

    @Test
    fun the_picker_leaves_an_uncomplicated_component_unmarked() {
        val plain = registry.triggerDescriptors.first { it.warning == null }

        composeRule.setContent {
            ComponentPickerDialog(
                title = "Choose a trigger",
                options = listOf(plain),
                onPick = {},
                onDismiss = {},
            )
        }

        composeRule.onNodeWithContentDescription(CAVEAT_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun every_field_kind_renders_without_crashing() {
        // The field kinds are the whole editor surface, so a missing branch would
        // break an arbitrary subset of the 47 components. This list is
        // hand-maintained and therefore the weak point — it silently missed four
        // kinds once already — so a new kind belongs here as well as in the
        // editor's `when`, which at least the compiler enforces.
        val fields = listOf<ConfigField>(
            ConfigField.Text("t", "Some text"),
            ConfigField.AppPackage("p", "An app"),
            ConfigField.Choice("c", "A choice", listOf(ConfigField.Option("a", "Option A"))),
            ConfigField.Number("n", "A number", unit = "ms"),
            ConfigField.Decimal("d", "A decimal"),
            ConfigField.Flag("f", "A flag"),
            ConfigField.SoundUri("s", "A sound"),
            ConfigField.BluetoothAddress("b", "A device"),
            ConfigField.Slider("sl", "A slider", min = 0, max = 10, default = 5),
            ConfigField.TextPattern("tp", "A pattern"),
            ConfigField.Duration("du", "A duration"),
            ConfigField.Timestamp("ts", "A moment"),
            ConfigField.TimeOfDay("tod", "A time"),
            ConfigField.Coordinates("lat", "A latitude"),
        )

        composeRule.setContent {
            androidx.compose.foundation.layout.Column {
                fields.forEach { field ->
                    ConfigFieldEditor(field = field, value = null, onValueChange = {})
                }
            }
        }

        composeRule.onNodeWithText("SOME TEXT").assertIsDisplayed()
        composeRule.onNodeWithText("AN APP").assertIsDisplayed()
        composeRule.onNodeWithText("A NUMBER (MS)").assertIsDisplayed()
        composeRule.onNodeWithText("A DECIMAL").assertIsDisplayed()
        composeRule.onNodeWithText("A FLAG").assertIsDisplayed()
        // A choice with no value and no default prompts rather than guessing;
        // its option labels live inside the dropdown, not on the form.
        composeRule.onNodeWithText("A CHOICE *").assertIsDisplayed()
        composeRule.onNodeWithText("CHOOSE…").assertIsDisplayed()

        // The four newest kinds, each of which prompts rather than inventing a
        // value: a defaulted timestamp would mean "the moment I opened this".
        //
        // `assertExists` rather than `assertIsDisplayed` from here down. Fourteen
        // fields are taller than the test surface, so displayed-ness would be an
        // assertion about screen height — which passes on one emulator and fails
        // on the next, for no reason anybody wants to hear about.
        composeRule.onNodeWithText("A DURATION").assertExists()
        composeRule.onNodeWithText("A MOMENT").assertExists()
        composeRule.onNodeWithText("PICK A TIME").assertExists()
        composeRule.onNodeWithText("A LATITUDE").assertExists()
        composeRule.onNodeWithText("USE WHERE I AM NOW").assertExists()
    }
}
