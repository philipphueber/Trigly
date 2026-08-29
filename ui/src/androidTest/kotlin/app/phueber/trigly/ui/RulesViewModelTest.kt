package app.phueber.trigly.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.phueber.trigly.actions.actionFactories
import app.phueber.trigly.core.ComponentSpec
import app.phueber.trigly.core.InMemoryRuleRepository
import app.phueber.trigly.core.NO_TRIGGER
import app.phueber.trigly.core.NotificationController
import app.phueber.trigly.core.Registry
import app.phueber.trigly.core.RequirementChecker
import app.phueber.trigly.core.Rule
import app.phueber.trigly.core.RuleRunnerHandle
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The rules list's own switch: the other of the two places a rule can be
 * turned on, beside the editor's. See `RuleEditorViewModelTest` for that one;
 * both have to refuse the same way and for the same reason.
 *
 * An instrumented test rather than a JVM one for the same reason
 * `RuleEditorViewModelTest` is: the factories need a real `Context`, and
 * `viewModelScope` dispatches on `Dispatchers.Main`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class RulesViewModelTest {

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

    private fun viewModel(repository: InMemoryRuleRepository) =
        RulesViewModel(repository, registry, RequirementChecker(context))

    private val unfinished = Rule(
        id = "half-built",
        name = "Half built",
        trigger = NO_TRIGGER,
        actions = emptyList(),
        enabled = false,
    )

    private val whole = Rule(
        id = "whole",
        name = "Whole",
        trigger = ComponentSpec("screen_state", mapOf("state" to "on")),
        actions = listOf(ComponentSpec("toast", mapOf("text" to "hi"))),
        enabled = false,
    )

    @Test
    fun the_list_switch_refuses_to_turn_on_an_unfinished_rule() = runTest {
        val repository = InMemoryRuleRepository(listOf(unfinished))
        val viewModel = viewModel(repository)

        viewModel.setEnabled(unfinished, true)

        assertEquals(
            "Add a trigger and an action before switching this on.",
            viewModel.message.value,
        )
        assertFalse(
            "the repository must not have been touched",
            repository.rules().first().single().enabled,
        )
    }

    @Test
    fun the_list_switch_turns_on_a_rule_that_can_actually_start() = runTest {
        val repository = InMemoryRuleRepository(listOf(whole))
        val viewModel = viewModel(repository)

        viewModel.setEnabled(whole, true)

        assertNull("a rule that can start must not be refused", viewModel.message.value)
        assertTrue(repository.rules().first().single().enabled)
    }

    @Test
    fun turning_the_list_switch_off_is_never_refused() = runTest {
        val repository = InMemoryRuleRepository(listOf(whole.copy(enabled = true)))
        val viewModel = viewModel(repository)

        viewModel.setEnabled(whole, false)

        assertNull(viewModel.message.value)
        assertFalse(repository.rules().first().single().enabled)
    }

    /**
     * `RuleStatus.enableRefusal` is what lets the rules list show why a rule
     * is unfinished without anyone tapping its switch. See `UnfinishedRuleCell`
     * in `RulesScreen`.
     */
    @Test
    fun statuses_carries_the_same_refusal_for_a_disabled_unfinished_rule() = runTest {
        val repository = InMemoryRuleRepository(listOf(unfinished))
        val viewModel = viewModel(repository)

        val status = viewModel.statuses.first().single()

        assertEquals(
            "Add a trigger and an action before switching this on.",
            status.enableRefusal,
        )
    }

    @Test
    fun statuses_carries_no_refusal_once_the_rule_can_start() = runTest {
        val repository = InMemoryRuleRepository(listOf(whole))
        val viewModel = viewModel(repository)

        assertNull(viewModel.statuses.first().single().enableRefusal)
    }
}
