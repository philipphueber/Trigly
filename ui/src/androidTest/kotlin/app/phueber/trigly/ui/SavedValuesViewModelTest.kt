package app.phueber.trigly.ui

import app.phueber.trigly.core.InMemoryRuleVariableStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.phueber.trigly.actions.actionFactories
import app.phueber.trigly.core.ComponentSpec
import app.phueber.trigly.core.InMemoryRuleRepository
import app.phueber.trigly.core.InMemoryVariableStore
import app.phueber.trigly.core.NotificationController
import app.phueber.trigly.core.Registry
import app.phueber.trigly.core.Rule
import app.phueber.trigly.core.RuleRunnerHandle
import app.phueber.trigly.core.variableNameProblem
import app.phueber.trigly.triggers.AlarmManagerScheduler
import app.phueber.trigly.triggers.triggerFactories
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [SavedValuesViewModel], driven against the real registry so `toast`'s own
 * declared substitution is what decides whether a rule counts as a reader,
 * the same reasoning [RuleEditorViewModelTest] gives for itself.
 *
 * An instrumented test rather than a JVM one because the factories need a real
 * `Context`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SavedValuesViewModelTest {

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

    /**
     * Waits for Main to be free before giving it back, rather than assuming it
     * is. See [RuleEditorViewModelTest.tearDown], which this copies: a leaked
     * dispatcher reads as flakiness in whichever test happens to run last,
     * when the race is really in this teardown.
     */
    @After
    fun tearDown() {
        val deadline = System.currentTimeMillis() + RESET_TIMEOUT_MILLIS
        while (true) {
            try {
                Dispatchers.resetMain()
                return
            } catch (busy: IllegalStateException) {
                if (System.currentTimeMillis() > deadline) throw busy
                Thread.sleep(RESET_POLL_MILLIS)
            }
        }
    }

    private fun viewModel(
        variableStore: InMemoryVariableStore = InMemoryVariableStore(),
        ruleRepository: InMemoryRuleRepository = InMemoryRuleRepository(),
    ) = SavedValuesViewModel(variableStore, InMemoryRuleVariableStore(), ruleRepository, registry::substitutionsFor)

    /** A rule with one action whose field reads `{{app.[name]}}`. */
    private fun ruleReading(name: String, ruleName: String, id: String) = Rule(
        id = id,
        name = ruleName,
        trigger = ComponentSpec("screen_state", mapOf("state" to "on")),
        actions = listOf(ComponentSpec("toast", mapOf("text" to "{{app.$name}}"))),
    )

    private companion object {
        const val RESET_TIMEOUT_MILLIS = 5_000L
        const val RESET_POLL_MILLIS = 20L
    }

    @Test
    fun the_list_reflects_the_store_and_is_sorted_by_name() = runTest {
        val store = InMemoryVariableStore(initial = mapOf("zebra" to "1", "apple" to "2"))

        val viewModel = viewModel(variableStore = store)

        assertEquals(listOf("apple", "zebra"), viewModel.state.value.values.map { it.name })
    }

    @Test
    fun adding_a_value_stores_it() = runTest {
        val store = InMemoryVariableStore()
        val viewModel = viewModel(variableStore = store)

        viewModel.setValue("trip_count", "3")

        assertEquals("3", store.get("trip_count"))
        assertEquals(listOf("trip_count"), viewModel.state.value.values.map { it.name })
        assertEquals("3", viewModel.state.value.values.single().value)
    }

    /**
     * Editing is the same call as adding: a value rules already read needs no
     * ceremony beyond an ordinary write. See [SavedValuesViewModel.setValue].
     */
    @Test
    fun setting_a_value_a_second_time_replaces_it() = runTest {
        val store = InMemoryVariableStore(initial = mapOf("trip_count" to "3"))
        val viewModel = viewModel(variableStore = store)

        viewModel.setValue("trip_count", "4")

        assertEquals("4", store.get("trip_count"))
        assertEquals(listOf("trip_count"), viewModel.state.value.values.map { it.name })
    }

    @Test
    fun a_name_variable_name_problem_refuses_is_reported_and_nothing_is_stored() = runTest {
        val store = InMemoryVariableStore()
        val viewModel = viewModel(variableStore = store)
        val badName = "bad name"

        viewModel.setValue(badName, "1")

        assertEquals(variableNameProblem(badName), viewModel.state.value.error)
        assertNull("nothing should be stored", store.get(badName))
        assertTrue(viewModel.state.value.values.isEmpty())
    }

    @Test
    fun deleting_removes_it() = runTest {
        val store = InMemoryVariableStore(initial = mapOf("trip_count" to "3"))
        val viewModel = viewModel(variableStore = store)

        viewModel.delete("trip_count")

        assertNull(store.get("trip_count"))
        assertTrue(viewModel.state.value.values.isEmpty())
    }

    /**
     * The reason `VariableUse.kt` exists. Deleting a value has to be
     * answerable with which rules will break, by name and not merely how
     * many: a count answers "is anything using this", and the question
     * someone facing a delete button is actually asking is "what will I
     * break".
     */
    @Test
    fun a_value_read_by_two_rules_reports_both_of_those_rules_by_name() = runTest {
        val readerA = ruleReading("trip_count", ruleName = "Announce trip", id = "a")
        val readerB = ruleReading("trip_count", ruleName = "Log trip", id = "b")
        val unrelated = Rule(
            id = "c",
            name = "Unrelated",
            trigger = ComponentSpec("screen_state", mapOf("state" to "on")),
            actions = listOf(ComponentSpec("toast", mapOf("text" to "hello"))),
        )
        val repository = InMemoryRuleRepository(listOf(readerA, readerB, unrelated))
        val store = InMemoryVariableStore(initial = mapOf("trip_count" to "3"))

        val viewModel = viewModel(variableStore = store, ruleRepository = repository)

        val row = viewModel.state.value.values.single { it.name == "trip_count" }
        assertEquals(setOf("Announce trip", "Log trip"), row.readByRuleNames.toSet())
    }
}
