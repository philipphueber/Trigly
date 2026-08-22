package app.phueber.trigly.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
    private var saves = 0

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
            onSave = { saves++ },
            onDelete = {},
            onResolveRequirement = {},
        )
    }

    @Test
    fun a_new_rule_shows_prompts_and_no_delete() {
        composeRule.setContent { Editor(EditorState(RuleDraft(id = null))) }

        composeRule.onNodeWithText("New rule").assertIsDisplayed()
        composeRule.onNodeWithText("Choose a trigger").assertIsDisplayed()
        composeRule.onNodeWithText("Delete rule").assertDoesNotExist()
    }

    @Test
    fun an_existing_rule_can_be_deleted() {
        composeRule.setContent {
            Editor(EditorState(RuleDraft(id = "abc", name = "Existing")))
        }

        composeRule.onNodeWithText("Edit rule").assertIsDisplayed()
        composeRule.onNodeWithText("Delete rule").assertIsDisplayed()
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
        composeRule.onNodeWithText("Text appears on screen").assertIsDisplayed()
        // A declared field, rendered from the schema. Required, hence the marker.
        composeRule.onNodeWithText("Screen contains *").assertIsDisplayed()
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

        composeRule.onNodeWithText("Threshold (%) *").assertIsDisplayed()
        composeRule.onNodeWithText("below the threshold").assertIsDisplayed()
    }

    @Test
    fun a_blank_optional_field_explains_what_blank_means() {
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

        composeRule.onNodeWithText("Leave blank for any device").assertIsDisplayed()
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

        composeRule.onNodeWithText("Message *").performTextReplacement("hello")

        assertEquals(Triple(Slot.ACTION, "text", "hello"), configChanges.last())
    }

    @Test
    fun save_is_reported() {
        composeRule.setContent { Editor(EditorState(RuleDraft(id = null))) }

        composeRule.onNodeWithText("Save").performClick()

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

        composeRule.onNodeWithText("Search").performTextReplacement("battery_level")

        composeRule.onNodeWithText("Battery level").assertIsDisplayed()
        composeRule.onNodeWithText("Screen on or off").assertDoesNotExist()
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

        composeRule.onNodeWithText("Search").performTextReplacement("zzzz")

        composeRule.onNodeWithText("Nothing matches \"zzzz\".").assertIsDisplayed()
    }

    @Test
    fun every_field_kind_renders_without_crashing() {
        // Cheap guard: the six kinds are the whole editor surface, so a missing
        // branch would break an arbitrary subset of the 46 components.
        val fields = listOf<ConfigField>(
            ConfigField.Text("t", "Some text"),
            ConfigField.AppPackage("p", "An app"),
            ConfigField.Choice("c", "A choice", listOf(ConfigField.Option("a", "Option A"))),
            ConfigField.Number("n", "A number", unit = "ms"),
            ConfigField.Decimal("d", "A decimal"),
            ConfigField.Flag("f", "A flag"),
        )

        composeRule.setContent {
            androidx.compose.foundation.layout.Column {
                fields.forEach { field ->
                    ConfigFieldEditor(field = field, value = null, onValueChange = {})
                }
            }
        }

        composeRule.onNodeWithText("Some text").assertIsDisplayed()
        composeRule.onNodeWithText("An app").assertIsDisplayed()
        composeRule.onNodeWithText("A number (ms)").assertIsDisplayed()
        composeRule.onNodeWithText("A decimal").assertIsDisplayed()
        composeRule.onNodeWithText("A flag").assertIsDisplayed()
        // A choice with no value and no default prompts rather than guessing;
        // its option labels live inside the dropdown, not on the form.
        composeRule.onNodeWithText("A choice *").assertIsDisplayed()
        composeRule.onNodeWithText("Choose…").assertIsDisplayed()
    }
}
