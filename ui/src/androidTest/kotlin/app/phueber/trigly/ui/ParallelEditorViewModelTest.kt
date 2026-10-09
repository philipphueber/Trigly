package app.phueber.trigly.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.phueber.trigly.actions.actionFactories
import app.phueber.trigly.core.ActionNode
import app.phueber.trigly.core.ComponentSpec
import app.phueber.trigly.core.InMemoryRuleRepository
import app.phueber.trigly.core.InMemoryRuleVariableStore
import app.phueber.trigly.core.InMemoryVariableStore
import app.phueber.trigly.core.NotificationController
import app.phueber.trigly.core.Registry
import app.phueber.trigly.core.RequirementChecker
import app.phueber.trigly.core.RuleRunnerHandle
import app.phueber.trigly.core.VariableScope
import app.phueber.trigly.triggers.AlarmManagerScheduler
import app.phueber.trigly.triggers.triggerFactories
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The editor's parallel block, driven through [RuleEditorViewModel] against
 * the real registry: building a block, filling its branches, saving it, and
 * the two places a branch is easy to forget, validation and the variable
 * picker.
 *
 * Set up the way `RuleEditorViewModelTest` is, for the reasons it gives: the
 * factories need a real `Context`, and Main is swapped for an unconfined test
 * dispatcher so a save has finished when the next line runs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ParallelEditorViewModelTest {

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

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(repository: InMemoryRuleRepository = InMemoryRuleRepository(), ruleId: String? = null) =
        RuleEditorViewModel(
            repository,
            registry,
            RequirementChecker(context),
            InMemoryVariableStore(),
            InMemoryRuleVariableStore(),
            ruleId,
        )

    private val RuleEditorViewModel.block: ActionDraft.Parallel
        get() = state.value.draft.actions.filterIsInstance<ActionDraft.Parallel>().single()

    @Test
    fun picking_the_parallel_row_adds_a_block_with_two_empty_branches() = runTest {
        val editor = viewModel()

        editor.addAction(PARALLEL_TYPE)

        assertEquals(listOf(emptyList<ComponentDraft>(), emptyList()), editor.block.branches)
    }

    @Test
    fun a_rule_with_a_block_saves_as_one_tree_and_opens_again_the_same() = runTest {
        val repository = InMemoryRuleRepository()
        val editor = viewModel(repository)
        editor.setName("Both at once")
        editor.chooseTrigger("screen_state")
        editor.setTriggerConfigValue(emptyList(), "state", "on")
        editor.addAction("toast")
        editor.setActionConfigValue(listOf(0), "text", "before")
        editor.addAction(PARALLEL_TYPE)
        editor.addActionToBranch(listOf(1, 0), "toast")
        editor.setActionConfigValue(listOf(1, 0, 0), "text", "left")
        editor.addActionToBranch(listOf(1, 1), "toast")
        editor.setActionConfigValue(listOf(1, 1, 0), "text", "right")
        // A branch cannot hold a block: the row is ignored inside one.
        editor.addActionToBranch(listOf(1, 1), PARALLEL_TYPE)

        editor.save()

        assertEquals(null, editor.state.value.error)
        val saved = repository.rules().first().single()
        assertEquals(
            listOf(
                ComponentSpec("toast", mapOf("text" to "before")),
                ActionNode.Parallel(
                    listOf(
                        listOf(ComponentSpec("toast", mapOf("text" to "left"))),
                        listOf(ComponentSpec("toast", mapOf("text" to "right"))),
                    )
                ),
            ),
            saved.actions.map { node ->
                // Compared without config the factory fills in on its own.
                when (node) {
                    is ComponentSpec -> ComponentSpec(node.type, mapOf("text" to node.config.getValue("text")))
                    is ActionNode.Parallel -> ActionNode.Parallel(
                        node.branches.map { branch ->
                            branch.map { ComponentSpec(it.type, mapOf("text" to it.config.getValue("text"))) }
                        }
                    )
                }
            },
        )

        val reopened = viewModel(repository, saved.id)
        assertEquals(saved.actions, reopened.state.value.draft.toRuleOrNull()!!.actions)
    }

    @Test
    fun a_broken_action_inside_a_branch_refuses_the_save_and_is_numbered() = runTest {
        val editor = viewModel()
        editor.setName("Broken in a branch")
        editor.chooseTrigger("screen_state")
        editor.setTriggerConfigValue(emptyList(), "state", "on")
        editor.addAction(PARALLEL_TYPE)
        editor.addActionToBranch(listOf(0, 0), "toast")
        editor.setActionConfigValue(listOf(0, 0, 0), "text", "fine")
        editor.addActionToBranch(listOf(0, 1), "set_alarm")
        editor.setActionConfigValue(listOf(0, 1, 0), "hour", "25")

        editor.save()

        val error = editor.state.value.error
        assertNotNull("a broken action in a branch must not save", error)
        assertTrue(error!!, error.contains("action 2"))
    }

    @Test
    fun a_branch_is_not_offered_what_the_branch_beside_it_produces() = runTest {
        val editor = viewModel()
        editor.chooseTrigger("bluetooth_connected")
        editor.addAction(PARALLEL_TYPE)
        editor.addActionToBranch(listOf(0, 0), "set_rule_enabled")
        editor.addActionToBranch(listOf(0, 1), "toast")
        editor.addAction("toast")

        val beside = editor.availableVariablesForAction(listOf(0, 1, 0))
        val after = editor.availableVariablesForAction(listOf(1))

        assertTrue(
            "was: ${beside.map { it.reference }}",
            beside.none { it.scope == VariableScope.ACTION },
        )
        assertTrue(
            "was: ${after.map { it.reference }}",
            after.any { it.scope == VariableScope.ACTION },
        )
    }

    @Test
    fun removing_one_of_two_branches_leaves_the_other_branch_in_the_list() = runTest {
        val editor = viewModel()
        editor.addAction(PARALLEL_TYPE)
        editor.addActionToBranch(listOf(0, 0), "toast")
        editor.addActionToBranch(listOf(0, 1), "vibrate")

        editor.removeAction(listOf(0, 0))

        assertEquals(listOf("vibrate"), editor.state.value.draft.actions.map { (it as ComponentDraft).type })
    }

    @Test
    fun the_test_button_reaches_an_action_inside_a_branch() = runTest {
        val editor = viewModel()
        editor.addAction(PARALLEL_TYPE)
        editor.addActionToBranch(listOf(0, 1), "open_url")
        editor.setActionConfigValue(listOf(0, 1, 0), "url", "not-a-url")

        editor.testAction(listOf(0, 1, 0))

        val result = editor.state.value.testResult
        assertNotNull(result)
        assertTrue("expected the component named, got: $result", result!!.contains("Open a website"))
    }
}
