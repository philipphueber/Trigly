package app.phueber.trigly.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.phueber.trigly.actions.actionFactories
import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.ComponentSpec
import app.phueber.trigly.core.ComponentTool
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.NodePath
import app.phueber.trigly.core.NotificationController
import app.phueber.trigly.core.Registry
import app.phueber.trigly.core.TriggerNode
import app.phueber.trigly.triggers.AlarmManagerScheduler
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
        triggerFactories = triggerFactories(context, AlarmManagerScheduler(context)),
        actionFactories = actionFactories(context, NotificationController.Unavailable),
    )

    private val configChanges = mutableListOf<Triple<Slot, String, String?>>()
    private val tested = mutableListOf<Int>()
    private val pinned = mutableListOf<Map<String, String>>()
    private var saves = 0
    private var backs = 0
    private var chosenTrigger: String? = null
    private val changedTriggerTypes = mutableListOf<Pair<NodePath, String>>()
    private val addedTriggers = mutableListOf<Pair<NodePath, String>>()
    private val removedTriggers = mutableListOf<NodePath>()
    private val setOps = mutableListOf<Pair<NodePath, TriggerNode.Op>>()
    private val triggerConfigChanges = mutableListOf<Triple<NodePath, String, String?>>()
    private val folderChanges = mutableListOf<String>()

    @Composable
    private fun Editor(
        state: EditorState,
        isRequirementSatisfied: (ComponentRequirement) -> Boolean = { false },
        // Defaults to what the app really does, so a test that says nothing about
        // tools renders the same buttons a user sees.
        toolsFor: (String, Map<String, String>) -> List<ComponentTool> = { type, config ->
            registry.toolsFor(ComponentSpec(type, config))
        },
        // Defaults to recording nothing, like the other intents below — a test
        // that cares whether an action actually moves passes its own, backed by
        // state it holds, rather than this stub reordering anything itself.
        onMoveAction: (Int, Int) -> Unit = { _, _ -> },
        // What a real caller would draw from the saved rules — empty by default,
        // so a test that says nothing about folders still gets a working field.
        existingFolders: List<String> = emptyList(),
    ) {
        RuleEditorScreen(
            state = state,
            // Ignores the path: nothing here exercises `canStart`-style
            // filtering, which belongs to the ViewModel this stateless screen
            // does not have — every test that opens a picker just wants the
            // full, real list back.
            // The group rows come first, exactly as `RuleEditorViewModel`
            // assembles them: a group is one of the picker's options, and a
            // harness that left them out would let the screen's group handling
            // rot untested while every test still passed.
            triggerOptionsFor = { GROUP_OPTIONS + registry.triggerDescriptors },
            actionOptions = registry.actionDescriptors,
            descriptorFor = { slot, type ->
                when (slot) {
                    Slot.TRIGGER -> registry.triggerDescriptor(type)
                    Slot.ACTION -> registry.actionDescriptor(type)
                }
            },
            onNameChange = {},
            onEnabledChange = {},
            existingFolders = existingFolders,
            onFolderChange = { folderChanges += it },
            onChooseTrigger = { chosenTrigger = it },
            onChangeTriggerType = { path, type -> changedTriggerTypes += path to type },
            onAddTrigger = { path, type -> addedTriggers += path to type },
            onSetTriggerOp = { path, op -> setOps += path to op },
            onRemoveTrigger = { path -> removedTriggers += path },
            onSetTriggerConfigValue = { path, key, value -> triggerConfigChanges += Triple(path, key, value) },
            onAddAction = {},
            onChangeActionType = { _, _ -> },
            onRemoveAction = {},
            onMoveAction = onMoveAction,
            onConfigChange = { slot, _, key, value -> configChanges += Triple(slot, key, value) },
            onTestAction = { tested += it },
            onSave = { saves++ },
            onDelete = {},
            onBack = { backs++ },
            onResolveRequirement = {},
            isRequirementSatisfied = isRequirementSatisfied,
            toolsFor = toolsFor,
            onPinShortcut = { pinned += it },
        )
    }

    /**
     * A block's controls must not be squeezed into unreadability.
     *
     * The case from a real phone: a shortcut trigger contributes "Add to home
     * screen" beside "Add trigger" and "Remove", the row runs out of width, and
     * the last control renders as a vertical column of single letters. Still
     * tappable, so nothing failed and nothing said anything.
     *
     * Height is what this asserts, because height is what the bug produces.
     * `assertIsDisplayed` passes on a crushed button, and so does anything that
     * only looks for the text. A six-letter label stacked one letter per line is
     * six line heights tall; a control on one line is about one.
     */
    @Test
    fun a_blocks_controls_are_not_crushed_when_they_do_not_fit_one_line() {
        composeRule.setContent {
            // Narrow on purpose. 320dp is a small phone in portrait, and the
            // width the footer has to survive rather than one it happens to fit.
            Box(modifier = Modifier.width(320.dp)) {
                Editor(
                    EditorState(
                        RuleDraft(
                            id = "abc",
                            name = "Alarm",
                            trigger = TriggerDraft.One(
                                ComponentDraft(
                                    "shortcut",
                                    mapOf("shortcutId" to "id-1", "label" to "Alarmierung"),
                                )
                            ),
                        )
                    )
                )
            }
        }

        // All three are present: none was dropped to make room.
        composeRule.onNodeWithText("ADD TO HOME SCREEN").performScrollTo().assertExists()
        composeRule.onNodeWithText("ADD TRIGGER").assertExists()
        composeRule.onNodeWithText("REMOVE").assertExists()

        val oneLine = 64.dp
        listOf("ADD TO HOME SCREEN", "ADD TRIGGER", "REMOVE").forEach { label ->
            val height = composeRule.onNodeWithText(label)
                .getUnclippedBoundsInRoot()
                .height
            assertTrue(
                "$label is $height tall, so its text wrapped instead of staying on one line",
                height < oneLine,
            )
        }
    }

    /**
     * The same guarantee for an action block, which can hold more controls than a
     * trigger: its own tools, both reorder arrows, and Remove.
     */
    @Test
    fun an_action_blocks_controls_are_not_crushed_either() {
        composeRule.setContent {
            Box(modifier = Modifier.width(320.dp)) {
                Editor(
                    EditorState(
                        RuleDraft(
                            id = "abc",
                            name = "Noisy",
                            trigger = TriggerDraft.One(ComponentDraft("screen_state")),
                            actions = listOf(
                                ComponentDraft("toast", mapOf("text" to "one")),
                                ComponentDraft("post_notification", emptyMap()),
                                ComponentDraft("toast", mapOf("text" to "three")),
                            ),
                        )
                    )
                )
            }
        }

        composeRule.onAllNodesWithText("REMOVE").fetchSemanticsNodes().indices.forEach { index ->
            val height = composeRule.onAllNodesWithText("REMOVE")[index]
                .getUnclippedBoundsInRoot()
                .height
            assertTrue("a REMOVE control is $height tall", height < 64.dp)
        }
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
    fun declared_fields_are_rendered_with_display_names() {
        composeRule.setContent {
            Editor(
                EditorState(
                    RuleDraft(
                        id = null,
                        name = "Visual",
                        trigger = TriggerDraft.One(ComponentDraft("screen_content")),
                    )
                )
            )
        }

        // The display name, not the type string.
        composeRule.onNodeWithText("TEXT APPEARS ON SCREEN").assertIsDisplayed()
        // A declared field, rendered from the schema. Required, hence the marker.
        composeRule.onNodeWithText("SCREEN CONTAINS *").assertIsDisplayed()
    }

    @Test
    fun the_alert_duration_is_hidden_when_the_tone_plays_once() {
        composeRule.setContent {
            Editor(
                EditorState(
                    RuleDraft(
                        id = null,
                        name = "Chime",
                        trigger = TriggerDraft.One(ComponentDraft("power_connection", mapOf("state" to "connected"))),
                        actions = listOf(
                            ComponentDraft("play_alert", mapOf("playback" to "once"))
                        ),
                    )
                )
            )
        }

        // The tone still needs choosing; the length does not exist for one pass.
        // assertExists rather than assertIsDisplayed: the action block is below
        // the fold of a scrolling form, and being on screen is not the claim.
        // Required, hence the marker — the same convention the field tests above
        // rely on. Asserted so this is "only the duration is gone", not "the
        // block failed to render".
        composeRule.onNodeWithText("PLAY IT *").assertExists()
        composeRule.onNodeWithText("KEEP SOUNDING FOR").assertDoesNotExist()
    }

    @Test
    fun the_alert_duration_is_shown_when_repeating() {
        composeRule.setContent {
            Editor(
                EditorState(
                    RuleDraft(
                        id = null,
                        name = "Alarm",
                        trigger = TriggerDraft.One(ComponentDraft("power_connection", mapOf("state" to "connected"))),
                        actions = listOf(
                            ComponentDraft("play_alert", mapOf("playback" to "repeat"))
                        ),
                    )
                )
            )
        }

        composeRule.onNodeWithText("KEEP SOUNDING FOR").assertExists()
    }

    @Test
    fun the_alert_duration_is_shown_on_an_untouched_action() {
        // Nothing stored for `playback` yet, and the editor is showing its
        // default of "repeat" — so the duration must be there. Reading only the
        // stored value would hide it on every newly added alert.
        composeRule.setContent {
            Editor(
                EditorState(
                    RuleDraft(
                        id = null,
                        name = "Fresh",
                        trigger = TriggerDraft.One(ComponentDraft("power_connection", mapOf("state" to "connected"))),
                        actions = listOf(ComponentDraft("play_alert")),
                    )
                )
            )
        }

        composeRule.onNodeWithText("KEEP SOUNDING FOR").assertExists()
    }

    @Test
    fun a_requirement_that_is_met_is_not_shown_at_all() {
        // The requirement text exists so nobody saves a rule that cannot fire.
        // Once it is granted it has nothing left to say, and a "Grant" button
        // beside it invites pressing something already done.
        val requirement = registry.triggerDescriptors
            .first { it.requirements.any { r -> r.isResolvable } }

        composeRule.setContent {
            Editor(
                EditorState(
                    RuleDraft(
                        id = null,
                        name = "Granted",
                        trigger = TriggerDraft.One(ComponentDraft(requirement.type)),
                    )
                ),
                isRequirementSatisfied = { true },
            )
        }

        composeRule.onNodeWithText("GRANT").assertDoesNotExist()
        requirement.requirements.forEach { r ->
            composeRule.onNodeWithText(r.describe()).assertDoesNotExist()
        }
    }

    @Test
    fun a_requirement_that_is_not_met_still_offers_to_grant_it() {
        val requirement = registry.triggerDescriptors
            .first { it.requirements.any { r -> r.isResolvable } }

        composeRule.setContent {
            Editor(
                EditorState(
                    RuleDraft(
                        id = null,
                        name = "Ungranted",
                        trigger = TriggerDraft.One(ComponentDraft(requirement.type)),
                    )
                ),
                isRequirementSatisfied = { false },
            )
        }

        val resolvable = requirement.requirements.first { it.isResolvable }
        composeRule.onNodeWithText(resolvable.describe()).assertExists()
    }

    @Test
    fun tapping_the_caveat_badge_does_not_fold_the_block() {
        // The badge and the fold chevron sit next to each other in the header,
        // and both want a 48dp touch target around a 22dp glyph. While both got
        // it by overhanging, they claimed the same pixels and the chevron — drawn
        // later — won: tapping "!" folded the block instead of showing the
        // caveat, silently closing the only route to that prose.
        //
        // Asserting the block is still open is the half that catches it. A test
        // that only asserted the prose appeared would fail without saying why.
        composeRule.setContent {
            Editor(
                EditorState(
                    RuleDraft(
                        id = null,
                        name = "Visual",
                        trigger = TriggerDraft.One(ComponentDraft("screen_content")),
                    )
                )
            )
        }

        composeRule.onNodeWithText("SCREEN CONTAINS *").assertExists()
        composeRule.onNodeWithContentDescription(CAVEAT_DESCRIPTION).performClick()

        // Still open: the fields are there, and the fold control still reads open.
        composeRule.onNodeWithText("SCREEN CONTAINS *").assertExists()
        composeRule.onNodeWithContentDescription(EXPAND_DESCRIPTION).assertIsOn()
    }

    @Test
    fun a_caveat_is_hidden_until_its_badge_is_tapped() {
        composeRule.setContent {
            Editor(
                EditorState(
                    RuleDraft(
                        id = null,
                        name = "Visual",
                        // A trigger that carries a warning.
                        trigger = TriggerDraft.One(ComponentDraft("screen_content")),
                    )
                )
            )
        }

        // The block is open — its fields are showing — but the caveat prose is
        // not: hiding it is the whole change, and an open block must not leak it.
        composeRule.onNodeWithText("SCREEN CONTAINS *").assertIsDisplayed()
        assertTrue(
            "the caveat prose must not be shown before the badge is tapped",
            composeRule.onAllNodesWithText("This is the noisiest trigger in the app", substring = true)
                .fetchSemanticsNodes().isEmpty()
        )

        // The badge is the one way to it.
        composeRule.onNodeWithContentDescription(CAVEAT_DESCRIPTION).performClick()
        assertTrue(
            "tapping the badge reveals the caveat prose",
            composeRule.onAllNodesWithText("This is the noisiest trigger in the app", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        )

        // And tapping it again puts it away.
        composeRule.onNodeWithContentDescription(CAVEAT_DESCRIPTION).performClick()
        assertTrue(
            "tapping the badge again hides it",
            composeRule.onAllNodesWithText("This is the noisiest trigger in the app", substring = true)
                .fetchSemanticsNodes().isEmpty()
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
                        trigger = TriggerDraft.One(ComponentDraft("battery_level", mapOf("direction" to "below"))),
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
                        trigger = TriggerDraft.One(ComponentDraft("clipboard_changed")),
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
                        trigger = TriggerDraft.One(ComponentDraft("bluetooth_connected")),
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
                        trigger = TriggerDraft.One(ComponentDraft("notification_posted")),
                    )
                )
            )
        }

        // `assertExists`, not `assertIsDisplayed`: this row sits under the
        // trigger's fields, and the editor gained a folder field above it, so
        // whether it is on screen is now a question about the emulator's height
        // rather than about the requirement being stated. It was displayed until
        // one field was added — which is exactly the trap this file's other tests
        // avoid by asserting existence.
        composeRule
            .onNodeWithText("Needs notification access, granted in system settings")
            .assertExists()
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
                        trigger = TriggerDraft.One(ComponentDraft("quantum_entanglement")),
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

    // --- Folder: the rule's own property, shown near its name. ---

    @Test
    fun an_unfoldered_rule_shows_no_folder() {
        composeRule.setContent { Editor(EditorState(RuleDraft(id = null, name = "Untitled"))) }

        composeRule.onNodeWithText("NO FOLDER").assertIsDisplayed()
    }

    @Test
    fun a_rule_already_in_a_folder_shows_it() {
        composeRule.setContent {
            Editor(EditorState(RuleDraft(id = "abc", name = "Existing", folder = "Car")))
        }

        composeRule.onNodeWithText("CAR").assertIsDisplayed()
    }

    @Test
    fun typing_a_new_folder_name_reports_it() {
        composeRule.setContent { Editor(EditorState(RuleDraft(id = null, name = "Untitled"))) }

        composeRule.onNodeWithText("NO FOLDER").performClick()
        composeRule.onNodeWithText("PICK A FOLDER, OR TYPE A NEW NAME").performTextReplacement("Weekend")
        composeRule.onNodeWithText("+  NEW FOLDER \"WEEKEND\"").performClick()

        assertEquals(listOf("Weekend"), folderChanges)
    }

    /**
     * Picking one of the folders other rules already use, rather than typing
     * it again — the whole point of offering the list, so a repeat name is a
     * tap instead of a second, possibly mistyped, spelling of the same one.
     */
    @Test
    fun picking_an_existing_folder_reports_it() {
        composeRule.setContent {
            Editor(
                EditorState(RuleDraft(id = null, name = "Untitled")),
                existingFolders = listOf("Car", "Home"),
            )
        }

        composeRule.onNodeWithText("NO FOLDER").performClick()
        composeRule.onNodeWithText("CAR").performClick()

        assertEquals(listOf("Car"), folderChanges)
    }

    /**
     * Clearing has to be reachable from a rule that already has a folder, and
     * has to actually report it — [RuleDraft.toRuleOrNull] is where "" then
     * becomes a real `null`, not here, but the screen has to get "" out in the
     * first place or that conversion never gets a chance to run.
     */
    @Test
    fun clearing_an_existing_folder_reports_it_as_blank() {
        composeRule.setContent {
            Editor(EditorState(RuleDraft(id = "abc", name = "Existing", folder = "Car")))
        }

        composeRule.onNodeWithText("CAR").performClick()
        composeRule.onNodeWithText("NO FOLDER").performClick()

        assertEquals(listOf(""), folderChanges)
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
        // shows the sentence only when its badge is tapped.
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
    fun the_pickers_caveat_badge_opens_the_prose_without_picking() {
        val caveated = registry.triggerDescriptors.first { it.warning != null }
        var picked: String? = null

        composeRule.setContent {
            ComponentPickerDialog(
                title = "Choose a trigger",
                options = listOf(caveated),
                onPick = { picked = it },
                onDismiss = {},
            )
        }

        // Tapping the badge reveals the sentence in place — and does not fall
        // through to the row's own click, which would pick the component out from
        // under someone who only wanted to read the catch.
        composeRule.onNodeWithContentDescription(CAVEAT_DESCRIPTION).performClick()
        composeRule.onNodeWithText(caveated.warning!!).assertIsDisplayed()
        assertEquals("reading the caveat must not pick the component", null, picked)
    }

    @Test
    fun the_caveat_badge_is_tappable_well_outside_its_glyph() {
        // The badge draws at 22dp and is the only route to a component's caveat
        // prose, so its *touch* target is grown to Android's 48dp minimum
        // without the glyph or the row around it changing size — the target
        // overhangs the space the row reserves for it. That is easy to write and
        // easy to have silently not work, because `performClick` hits a node's
        // centre and would pass either way. So this presses near the corner of
        // the enlarged target, which is outside the glyph entirely.
        val caveated = registry.triggerDescriptors.first { it.warning != null }

        composeRule.setContent {
            ComponentPickerDialog(
                title = "Choose a trigger",
                options = listOf(caveated),
                onPick = {},
                onDismiss = {},
            )
        }

        val badge = composeRule.onNodeWithContentDescription(CAVEAT_DESCRIPTION)
        badge.assertWidthIsAtLeast(48.dp)
        badge.assertHeightIsAtLeast(48.dp)

        // 3dp in from the target's own corner: ~10dp clear of the 22dp glyph
        // centred inside it, and still comfortably within the target.
        badge.performTouchInput { click(Offset(3.dp.toPx(), 3.dp.toPx())) }
        composeRule.onNodeWithText(caveated.warning!!).assertIsDisplayed()
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
            ConfigField.NotificationButton("btn", "A button"),
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
        composeRule.onNodeWithText("CAPTURE A BUTTON").assertExists()
    }

    /**
     * Folding is what makes a six-action rule navigable, so what it hides and
     * what it keeps is the behaviour worth pinning down — not merely that a
     * button exists.
     */
    @Test
    fun a_block_folds_its_settings_away_and_back() {
        composeRule.setContent {
            Editor(
                EditorState(
                    RuleDraft(
                        id = null,
                        name = "Battery",
                        trigger = TriggerDraft.One(ComponentDraft("battery_level", mapOf("direction" to "below"))),
                    )
                )
            )
        }

        composeRule.onNodeWithText("THRESHOLD (%) *").assertIsDisplayed()

        // One fixed control, not two different lookups for open versus closed —
        // `toggleable` carries the open/closed state on this same node, so
        // `assertIsOn`/`assertIsOff` is what used to be "find HIDE" / "find SHOW".
        val fold = composeRule.onNodeWithContentDescription(EXPAND_DESCRIPTION)
        fold.assertIsOn()

        fold.performClick()
        composeRule.onNodeWithText("THRESHOLD (%) *").assertDoesNotExist()
        // The heading has to survive, or a folded block cannot be identified or
        // reopened.
        composeRule.onNodeWithText("BATTERY LEVEL").assertIsDisplayed()
        fold.assertIsOff()

        fold.performClick()
        composeRule.onNodeWithText("THRESHOLD (%) *").assertIsDisplayed()
        fold.assertIsOn()
    }

    /**
     * A folded action keeps the controls that act on it. Reordering a long rule is
     * the main thing folding is *for*, so hiding Up, Down and Remove along with
     * the settings would take away the reason to fold in the first place.
     */
    @Test
    fun a_folded_action_keeps_its_controls() {
        composeRule.setContent {
            Editor(
                EditorState(
                    RuleDraft(
                        id = null,
                        name = "Alert",
                        trigger = TriggerDraft.One(ComponentDraft("battery_level", mapOf("direction" to "below"))),
                        actions = listOf(ComponentDraft("speak", mapOf("text" to "low"))),
                    )
                )
            )
        }

        // Two folding blocks on screen; the action's is the second.
        composeRule.onAllNodesWithContentDescription(EXPAND_DESCRIPTION)[1].performClick()

        // `assertExists`, for the reason given in the fourteen-field test below:
        // the trigger block above is still open, so whether the action's footer
        // has scrolled off is a question about the emulator's screen height. What
        // this test is about is that folding did not *remove* the controls.
        composeRule.onNodeWithText("TEST").assertExists()
        // Two Removes now, not one: a lone trigger carries its own, since
        // clearing the trigger slot is a thing you can do. So this counts them
        // instead of expecting a single match — the action's is the second, in
        // the same order as the fold controls above.
        composeRule.onAllNodesWithText("REMOVE").assertCountEquals(2)
        composeRule.onNodeWithText("SPEAK OUT LOUD").assertExists()
    }

    /**
     * No fold where there is nothing behind it. An unchosen trigger has no
     * settings, no requirements and no caveat, and a button that visibly does
     * nothing is worse than no button.
     */
    @Test
    fun a_block_with_nothing_to_fold_offers_no_fold() {
        composeRule.setContent { Editor(EditorState(RuleDraft(id = null))) }

        composeRule.onNodeWithText("CHOOSE A TRIGGER").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(EXPAND_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun a_component_only_shows_the_tools_it_declares() {
        // The seam, from the screen's side: it renders what it is handed and
        // nothing else. `power_connection` declares no tools, `play_alert`
        // declares Test by being an action, and neither is named anywhere in the
        // editor's own source.
        composeRule.setContent {
            Editor(
                EditorState(
                    RuleDraft(
                        id = null,
                        name = "Tools",
                        trigger = TriggerDraft.One(ComponentDraft("power_connection", mapOf("state" to "connected"))),
                        actions = listOf(ComponentDraft("play_alert")),
                    )
                )
            )
        }

        composeRule.onNodeWithText("TEST").assertExists()
        // A trigger cannot be run, so nothing offers to.
        composeRule.onNodeWithText("ADD TO HOME SCREEN").assertDoesNotExist()
        composeRule.onNodeWithText("INSPECT").assertDoesNotExist()
    }

    @Test
    fun a_notification_action_offers_the_inspector_beside_its_test() {
        composeRule.setContent {
            Editor(
                EditorState(
                    RuleDraft(
                        id = null,
                        name = "Inspect",
                        trigger = TriggerDraft.One(ComponentDraft("power_connection", mapOf("state" to "connected"))),
                        actions = listOf(ComponentDraft("dismiss_notification")),
                    )
                )
            )
        }

        // Both, and from the factory's declaration rather than from a name this
        // screen recognises.
        composeRule.onNodeWithText("TEST").assertExists()
        composeRule.onNodeWithText("INSPECT").assertExists()
    }

    @Test
    fun the_inspector_opens_over_the_editor_without_leaving_it() {
        composeRule.setContent {
            Editor(
                EditorState(
                    RuleDraft(
                        id = "abc",
                        name = "Kept",
                        trigger = TriggerDraft.One(ComponentDraft("power_connection", mapOf("state" to "connected"))),
                        actions = listOf(ComponentDraft("dismiss_notification")),
                    )
                )
            )
        }

        // Scrolled to first: the editor composes every block whether or not it is
        // in view, so a node this far down the page exists but is off-screen, and
        // a tap dispatched at its coordinates would land outside the window. The
        // file's other tests use `assertExists` for the same reason.
        composeRule.onNodeWithText("INSPECT").performScrollTo().performClick()
        composeRule.onNodeWithText("WHAT TRIGLY SEES").assertIsDisplayed()

        composeRule.onNodeWithText("BACK").performClick()
        // Still the same editor, with the draft it had. This is the whole reason
        // the inspector is a dialog here and not a destination: navigating away
        // would reset the draft on the way back in. `assertExists`, not
        // `assertIsDisplayed` — the editor is still scrolled to where the action
        // block is, so its header is off-screen but very much present.
        composeRule.onNodeWithText("EDIT RULE").assertExists()
        composeRule.onNodeWithText("Kept").assertExists()
        composeRule.onNodeWithText("WHAT TRIGLY SEES").assertDoesNotExist()
    }

    @Test
    fun a_declared_setup_tool_reports_the_whole_config() {
        // Pinning takes the config rather than an id, because the label and the
        // icon live there too. Driven through a stub rather than the shortcut
        // trigger, so this holds even if that trigger changes its keys.
        val config = mapOf("shortcutId" to "s1", "label" to "Go")
        composeRule.setContent {
            Editor(
                state = EditorState(
                    RuleDraft(
                        id = null,
                        name = "Pin",
                        trigger = TriggerDraft.One(ComponentDraft("power_connection", config)),
                    )
                ),
                toolsFor = { _, _ -> listOf(ComponentTool.PinShortcut) },
            )
        }

        composeRule.onNodeWithText("ADD TO HOME SCREEN").performScrollTo().performClick()
        assertEquals(listOf(config), pinned)
    }

    /*
     * The "When" section is a single slot now: nothing chosen, one component,
     * or a group of them. There used to be a second region here, captioned
     * "Must also be true.", with its own "Add trigger"/"Add a group" pair — a
     * gate is a trigger now, and it lives in this one slot. The tests below
     * cover that directly, rather than trusting the region's absence to fall
     * out of the ones above.
     */

    @Test
    fun a_single_trigger_shows_no_group_chrome_and_offers_add_trigger() {
        composeRule.setContent {
            Editor(
                EditorState(
                    RuleDraft(
                        id = null,
                        name = "Solo",
                        trigger = TriggerDraft.One(
                            ComponentDraft("power_connection", mapOf("state" to "connected"))
                        ),
                    )
                )
            )
        }

        composeRule.onNodeWithText("CHARGER").assertIsDisplayed()
        // No AND/OR, no fold summary — a lone trigger looks exactly like one.
        composeRule.onNodeWithText("ALL OF", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("ANY OF", substring = true).assertDoesNotExist()
        // The one way a group comes into existence: adding a sibling here.
        composeRule.onNodeWithText("ADD TRIGGER").assertExists()
        composeRule.onNodeWithText("ADD GATE").assertDoesNotExist()
    }

    @Test
    fun a_group_folds_to_a_summary_and_opens_to_its_operator_and_children() {
        composeRule.setContent {
            Editor(
                EditorState(
                    RuleDraft(
                        id = null,
                        name = "Both",
                        trigger = TriggerDraft.Group(
                            TriggerNode.Op.ALL,
                            listOf(
                                TriggerDraft.One(
                                    ComponentDraft("power_connection", mapOf("state" to "connected"))
                                ),
                                TriggerDraft.One(
                                    ComponentDraft("battery_level", mapOf("direction" to "below"))
                                ),
                            ),
                        ),
                    )
                )
            )
        }

        // A group starts folded when its rule is opened — one line, not the
        // tree, until asked.
        composeRule.onNodeWithText("ALL OF · 2 TRIGGERS").assertIsDisplayed()
        composeRule.onNodeWithText("CHARGER").assertDoesNotExist()
        composeRule.onNodeWithText("BATTERY LEVEL").assertDoesNotExist()

        composeRule.onNodeWithContentDescription(EXPAND_DESCRIPTION).performClick()

        // Open: the operator on its own, both children, and the AND/OR choice.
        // `assertExists` from here down — expanding pushed content further
        // down the scrolling form, and whether it has scrolled off is a
        // question about the emulator's screen height, not about this change.
        composeRule.onNodeWithText("ALL OF").assertExists()
        composeRule.onNodeWithText("AND").assertExists()
        composeRule.onNodeWithText("OR").assertExists()
        composeRule.onNodeWithText("CHARGER").assertExists()
        composeRule.onNodeWithText("BATTERY LEVEL").assertExists()
    }

    @Test
    fun a_folded_group_marks_a_hidden_caveat_and_the_mark_opens_it() {
        composeRule.setContent {
            Editor(
                EditorState(
                    RuleDraft(
                        id = null,
                        name = "Noisy",
                        trigger = TriggerDraft.Group(
                            TriggerNode.Op.ALL,
                            listOf(
                                // Carries a caveat — see the caveat-badge tests above.
                                TriggerDraft.One(ComponentDraft("screen_content")),
                                TriggerDraft.One(
                                    ComponentDraft("power_connection", mapOf("state" to "connected"))
                                ),
                            ),
                        ),
                    )
                )
            )
        }

        // The mark says a hidden child has something to say — not that the
        // group itself does; there is no prose to print here.
        composeRule.onNodeWithContentDescription(GROUP_CAVEAT_DESCRIPTION).assertExists()
        assertTrue(
            "the group must not appear to carry the caveat's own prose",
            composeRule.onAllNodesWithText("This is the noisiest trigger in the app", substring = true)
                .fetchSemanticsNodes().isEmpty()
        )

        // Tapping it opens the group rather than revealing any prose in place.
        composeRule.onNodeWithContentDescription(GROUP_CAVEAT_DESCRIPTION).performClick()
        composeRule.onNodeWithText("CHARGER").assertExists()
        composeRule.onNodeWithContentDescription(GROUP_CAVEAT_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun add_trigger_on_a_lone_trigger_reports_its_path_and_the_picked_type() {
        composeRule.setContent {
            Editor(
                EditorState(
                    RuleDraft(
                        id = null,
                        name = "Solo",
                        trigger = TriggerDraft.One(
                            ComponentDraft("power_connection", mapOf("state" to "connected"))
                        ),
                    )
                )
            )
        }

        composeRule.onNodeWithText("ADD TRIGGER").performScrollTo().performClick()
        composeRule.onNodeWithText("SEARCH").performTextReplacement("battery_level")
        composeRule.onNodeWithText("BATTERY LEVEL").performClick()

        assertEquals(listOf(emptyList<Int>() to "battery_level"), addedTriggers)
    }

    @Test
    fun a_group_is_picked_from_the_trigger_picker_like_any_other_trigger() {
        composeRule.setContent {
            Editor(
                EditorState(
                    RuleDraft(
                        id = null,
                        name = "Nest",
                        trigger = TriggerDraft.Group(
                            TriggerNode.Op.ALL,
                            listOf(
                                TriggerDraft.One(
                                    ComponentDraft("power_connection", mapOf("state" to "connected"))
                                ),
                                TriggerDraft.One(
                                    ComponentDraft("battery_level", mapOf("direction" to "below"))
                                ),
                            ),
                        ),
                    )
                )
            )
        }

        // There is no "Add gate" button, and that absence is the point: a group
        // is a row in the same picker every trigger comes from, so nesting one is
        // the same gesture as adding a trigger. It reaches the screen as an
        // ordinary picked type — see [GROUP_ALL_TYPE].
        composeRule.onNodeWithText("ADD GATE").assertDoesNotExist()

        // A group opens folded, so its footer is not composed yet.
        composeRule.onNodeWithContentDescription(EXPAND_DESCRIPTION).performClick()
        composeRule.onNodeWithText("ADD TRIGGER").performScrollTo().performClick()
        composeRule.onNodeWithText("ALL OF THESE").performClick()

        assertEquals(listOf(emptyList<Int>() to GROUP_ALL_TYPE), addedTriggers)
    }

    @Test
    fun the_screen_no_longer_shows_the_old_two_region_prose() {
        // The whole point of this change: no separately-captioned tail
        // beneath the trigger blocks, whatever the tree looks like.
        composeRule.setContent {
            Editor(
                EditorState(
                    RuleDraft(
                        id = null,
                        name = "No second region",
                        trigger = TriggerDraft.Group(
                            TriggerNode.Op.ALL,
                            listOf(
                                TriggerDraft.One(
                                    ComponentDraft("power_connection", mapOf("state" to "connected"))
                                ),
                                TriggerDraft.One(
                                    ComponentDraft("battery_level", mapOf("direction" to "below"))
                                ),
                            ),
                        ),
                    )
                )
            )
        }

        assertTrue(
            "the old gate caption must be gone",
            composeRule.onAllNodesWithText("must also be true", substring = true, ignoreCase = true)
                .fetchSemanticsNodes().isEmpty()
        )
        assertTrue(
            "the old multi-edge caption must be gone",
            composeRule.onAllNodesWithText("fire the rule", substring = true, ignoreCase = true)
                .fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun a_nested_notification_trigger_still_offers_inspect() {
        composeRule.setContent {
            Editor(
                EditorState(
                    RuleDraft(
                        id = null,
                        name = "Nested",
                        trigger = TriggerDraft.Group(
                            TriggerNode.Op.ALL,
                            listOf(
                                TriggerDraft.One(
                                    ComponentDraft("power_connection", mapOf("state" to "connected"))
                                ),
                                TriggerDraft.One(ComponentDraft("notification_posted")),
                            ),
                        ),
                    )
                )
            )
        }

        composeRule.onNodeWithContentDescription(EXPAND_DESCRIPTION).performClick()
        composeRule.onNodeWithText("INSPECT").performScrollTo().assertExists()
    }

    /**
     * Up/Down replace the "Up"/"Down" text buttons with a chevron pair, but the
     * behaviour they drive — actions run in the order they are listed, and
     * these are the only controls that change it — must survive unchanged. The
     * icon is not the thing worth asserting; the reorder is.
     */
    @Test
    fun moving_an_action_down_swaps_its_running_order() {
        composeRule.setContent {
            var actions by remember {
                mutableStateOf(
                    listOf(
                        ComponentDraft("toast", mapOf("text" to "first")),
                        ComponentDraft("speak", mapOf("text" to "second")),
                    )
                )
            }
            Editor(
                state = EditorState(RuleDraft(id = null, name = "Reorder", actions = actions)),
                // A real move, not a recorded intent: this is the one test in the
                // file that needs to see the *result* of reordering, not merely
                // that `onMoveAction` was called with the right indices.
                onMoveAction = { from, to ->
                    actions = actions.toMutableList().also { it.add(to, it.removeAt(from)) }
                },
            )
        }

        fun topOf(text: String) =
            composeRule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

        // Toast is first in the draft, so it must render above speak before
        // anything is pressed — the baseline the rest of this test moves from.
        assertTrue(
            "toast starts above speak",
            topOf("SHOW A BRIEF MESSAGE") < topOf("SPEAK OUT LOUD"),
        )

        // The first action's one reordering control is Move down.
        composeRule.onNodeWithContentDescription("Move down").performScrollTo().performClick()

        assertTrue(
            "moving the first action down puts speak first",
            topOf("SPEAK OUT LOUD") < topOf("SHOW A BRIEF MESSAGE"),
        )
    }

    /**
     * Order matters to what a rule does, so the ends of the list are where a
     * mistake would be worst: an Up that moved the first action, or a Down that
     * moved the last, would silently run something before or after the whole
     * rule's action list. Neither control exists at the end it would act past.
     */
    @Test
    fun the_first_action_has_no_up_and_the_last_has_no_down() {
        composeRule.setContent {
            Editor(
                EditorState(
                    RuleDraft(
                        id = null,
                        name = "Three",
                        actions = listOf(
                            ComponentDraft("toast", mapOf("text" to "a")),
                            ComponentDraft("speak", mapOf("text" to "b")),
                            ComponentDraft("toast", mapOf("text" to "c")),
                        ),
                    )
                )
            )
        }

        // Three actions: the middle one offers both, the first offers only
        // Down, the last only Up — two of each, never three.
        composeRule.onAllNodesWithContentDescription("Move up").assertCountEquals(2)
        composeRule.onAllNodesWithContentDescription("Move down").assertCountEquals(2)
    }
}
