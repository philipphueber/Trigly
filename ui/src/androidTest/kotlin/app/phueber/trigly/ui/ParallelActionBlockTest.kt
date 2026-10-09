package app.phueber.trigly.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.phueber.trigly.actions.actionFactories
import app.phueber.trigly.core.NotificationController
import app.phueber.trigly.core.Registry
import app.phueber.trigly.core.RuleRunnerHandle
import app.phueber.trigly.triggers.AlarmManagerScheduler
import app.phueber.trigly.triggers.triggerFactories
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule as JUnitRule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * The parallel block on screen, edited the way a person edits it: from the
 * "Add action" picker, with its own branch controls, and with actions added
 * inside a branch through the same picker.
 *
 * The screen is stateless, so the test holds the draft and applies the same
 * pure operations the ViewModel applies (`addToBranch`, `addBranch`,
 * `removeActionAt`). What is under test is that the screen draws the block
 * and sends each control's path to the right callback.
 */
@RunWith(AndroidJUnit4::class)
class ParallelActionBlockTest {

    @get:JUnitRule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val registry = Registry(
        triggerFactories = triggerFactories(context, AlarmManagerScheduler(context)),
        actionFactories = actionFactories(
            context,
            AlarmManagerScheduler(context),
            RuleRunnerHandle(),
            NotificationController.Unavailable,
        ),
    )

    private val added = mutableListOf<String>()

    private fun showEditor(initial: List<ActionDraft>): () -> List<ActionDraft> {
        var actions by mutableStateOf(initial)
        composeRule.setContent {
            RuleEditorScreen(
                state = EditorState(RuleDraft(id = null, name = "Parallel", actions = actions)),
                triggerOptionsFor = { registry.triggerDescriptors },
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
                onAddAction = { added += it },
                onChangeActionType = { _, _ -> },
                onRemoveAction = { path -> actions = actions.removeActionAt(path) },
                onMoveAction = { from, to -> actions = actions.moveActionAt(from, to) },
                onConfigChange = { _, _, _ -> },
                onTestAction = {},
                onAddActionToBranch = { path, type ->
                    actions = actions.addToBranch(path, ComponentDraft(type))
                },
                onAddBranch = { index -> actions = actions.addBranch(index) },
                onSave = {},
                onDelete = {},
                onBack = {},
                onResolveRequirement = {},
            )
        }
        return { actions }
    }

    @Test
    fun the_top_level_picker_offers_the_block_and_hands_on_its_type() {
        showEditor(emptyList())

        composeRule.onNodeWithText("ADD ACTION").performScrollTo().performClick()
        composeRule.onNodeWithText(PARALLEL_HEADING.uppercase()).performClick()

        assertEquals(listOf(PARALLEL_TYPE), added)
    }

    @Test
    fun a_block_shows_each_branch_and_adds_an_action_to_the_branch_it_was_asked_from() {
        val actions = showEditor(listOf(ActionDraft.Parallel(listOf(emptyList(), emptyList()))))

        composeRule.onNodeWithText("BRANCH 1").assertExists()
        composeRule.onNodeWithText("BRANCH 2").assertExists()

        // Three "Add action" buttons: one per branch, then the list's own.
        // The second is branch 2's.
        composeRule.onAllNodesWithText("ADD ACTION")[1].performScrollTo().performClick()
        // A branch holds only actions, so its picker does not offer a block.
        composeRule.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput("same time")
        // The search did run: the action that runs two whole rules matches.
        composeRule.onNodeWithText("RUN TWO RULES AT THE SAME TIME").assertExists()
        composeRule.onNode(hasText(PARALLEL_HEADING.uppercase()) and hasAnyAncestor(isDialog())).assertDoesNotExist()
        composeRule.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextClearance()
        composeRule.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput("Vibrate")
        composeRule.onNodeWithText("VIBRATE").performClick()

        val block = actions().single() as ActionDraft.Parallel
        assertEquals(listOf(emptyList(), listOf(ComponentDraft("vibrate"))), block.branches)
        composeRule.onNodeWithText("VIBRATE").assertExists()
    }

    @Test
    fun add_branch_and_remove_branch_change_the_block() {
        val actions = showEditor(
            listOf(ActionDraft.Parallel(listOf(listOf(ComponentDraft("vibrate")), emptyList()))),
        )

        composeRule.onNodeWithText("ADD BRANCH").performScrollTo().performClick()
        composeRule.onNodeWithText("BRANCH 3").assertExists()
        assertEquals(3, (actions().single() as ActionDraft.Parallel).branches.size)

        // Two "Remove branch" taps from three branches leave one branch,
        // which is no block at all: its action takes the block's place.
        composeRule.onAllNodesWithText("REMOVE BRANCH")[2].performScrollTo().performClick()
        composeRule.onAllNodesWithText("REMOVE BRANCH")[1].performScrollTo().performClick()

        assertEquals(listOf<ActionDraft>(ComponentDraft("vibrate")), actions())
        assertTrue(composeRule.onAllNodesWithText("BRANCH 1").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun a_folded_block_counts_its_branches_and_actions() {
        showEditor(
            listOf(
                ActionDraft.Parallel(
                    listOf(listOf(ComponentDraft("vibrate"), ComponentDraft("vibrate")), listOf(ComponentDraft("vibrate"))),
                ),
            ),
        )

        val heading = composeRule.onNodeWithText(PARALLEL_HEADING.uppercase()).fetchSemanticsNode()
        // The block's own fold control is the expand button on the heading's
        // line. Every action block has one too, so it is found by position.
        val buttons = composeRule.onAllNodesWithContentDescription(EXPAND_DESCRIPTION)
        val onHeadingLine = buttons.fetchSemanticsNodes().indices.minBy { i ->
            abs(buttons[i].fetchSemanticsNode().boundsInRoot.center.y - heading.boundsInRoot.center.y)
        }
        buttons[onHeadingLine].performClick()

        composeRule.onNodeWithText("$PARALLEL_HEADING · 2 branches · 3 actions".uppercase()).assertExists()
        composeRule.onNodeWithText("BRANCH 1").assertDoesNotExist()
    }
}
