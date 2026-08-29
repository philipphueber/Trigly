package app.phueber.trigly.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.phueber.trigly.actions.actionFactories
import android.os.Build
import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.ComponentSpec
import app.phueber.trigly.core.InMemoryRuleRepository
import app.phueber.trigly.core.InMemoryRuleVariableStore
import app.phueber.trigly.core.InMemoryVariableStore
import app.phueber.trigly.core.NotificationController
import app.phueber.trigly.core.Registry
import app.phueber.trigly.core.RequirementChecker
import app.phueber.trigly.core.Rule
import app.phueber.trigly.core.RuleRunnerHandle
import app.phueber.trigly.core.RuleVariableStore
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerEvent
import app.phueber.trigly.core.TriggerFactory
import app.phueber.trigly.core.TriggerNode
import app.phueber.trigly.core.VariableScope
import app.phueber.trigly.core.VariableStore
import app.phueber.trigly.core.leaves
import app.phueber.trigly.triggers.AlarmManagerScheduler
import app.phueber.trigly.triggers.triggerFactories
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The editor's logic, driven directly against the real registry so the schemas
 * the factories declare are what gets exercised.
 *
 * `viewModelScope` dispatches on `Dispatchers.Main`, and saving is deliberately
 * fire-and-forget, so the main dispatcher is replaced with an unconfined test one
 * for the duration. That is also why these live apart from the Compose rendering
 * tests: substituting Main interferes with Compose's own test clock.
 *
 * An instrumented test rather than a JVM one because the factories need a real
 * `Context`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class RuleEditorViewModelTest {

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
     * is.
     *
     * `resetMain` refuses while anything is still using the dispatcher, and a
     * test action here is real work on a real device: `play_alert` is asked to
     * run for a minute, and cancelling it does not finish it synchronously. A
     * cancelled job can still resume on Main *after* the test body has returned
     * its verdict, so a single attempt fails a test that had already passed, in
     * whichever test happens to be last. That reads as flakiness and is a race
     * in this teardown rather than in anything the app does.
     *
     * Bounded, and it rethrows when the wait is not enough. Being permanently
     * unable to hand Main back is a leak, and a leak is worth failing on.
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
        repository: InMemoryRuleRepository = InMemoryRuleRepository(),
        variableStore: VariableStore = InMemoryVariableStore(),
        ruleVariableStore: RuleVariableStore = InMemoryRuleVariableStore(),
        ruleId: String? = null,
    ) = RuleEditorViewModel(
        repository,
        registry,
        RequirementChecker(context),
        variableStore,
        ruleVariableStore,
        ruleId,
    )

    /** Unwraps a leaf, or null if [this] is a group or unchosen. */
    private val TriggerDraft?.leaf: ComponentDraft?
        get() = (this as? TriggerDraft.One)?.component

    private companion object {
        /** Long enough for a cancelled action to unwind, short enough to notice a leak. */
        const val RESET_TIMEOUT_MILLIS = 5_000L
        const val RESET_POLL_MILLIS = 20L
    }

    @Test
    fun a_complete_rule_saves() = runTest {
        val repository = InMemoryRuleRepository()
        val editor = viewModel(repository)

        editor.setName("Charger on")
        editor.chooseTrigger("power_connection")
        editor.setTriggerConfigValue(emptyList(), "state", "connected")
        editor.addAction("speak")
        editor.setConfigValue(Slot.ACTION, 0, "text", "Charging")
        editor.save()

        assertTrue("save should report completion", editor.state.value.finished)
        val saved = repository.rules().first().single()
        assertEquals("Charger on", saved.name)
        val leaf = saved.trigger as TriggerNode.One
        assertEquals("power_connection", leaf.spec.type)
        assertEquals("connected", leaf.spec.config["state"])
        assertEquals(listOf("speak"), saved.actions.map { it.type })
    }

    /**
     * The bug this guards is not in the ViewModel so much as in its lifetime.
     * This one is keyed by rule id and kept in the activity's store, so it
     * outlives the screen — and a `finished` flag left standing means the next
     * time that rule is opened, the editor reads "already done" and closes before
     * it is drawn. From the outside that is a rule that will not open, and a back
     * press swallowed by a screen that came and went.
     */
    @Test
    fun finishing_is_a_one_shot_signal_and_does_not_outlive_the_screen() = runTest {
        val repository = InMemoryRuleRepository()
        val editor = viewModel(repository)

        editor.setName("Charger on")
        editor.chooseTrigger("power_connection")
        editor.setTriggerConfigValue(emptyList(), "state", "connected")
        editor.addAction("speak")
        editor.setConfigValue(Slot.ACTION, 0, "text", "Charging")
        editor.save()
        assertTrue(editor.state.value.finished)

        // What the host does once it has navigated away.
        editor.exitHandled()

        assertFalse(
            "a reopened editor would close itself before being drawn",
            editor.state.value.finished,
        )
        // Consuming the signal must not undo the save, or the flag and the data
        // would disagree about whether anything happened.
        assertEquals(1, repository.rules().first().size)
    }

    @Test
    fun testing_an_action_reports_what_it_did() = runTest {
        val editor = viewModel()
        editor.setName("Buzz")
        editor.chooseTrigger("screen_state")
        editor.addAction("vibrate")

        editor.testAction(0)

        val result = editor.state.value.testResult
        assertNotNull("a test run has to say something, even when it worked", result)
        assertTrue("expected the action's own name, got: $result", result!!.contains("Vibrate"))
        // Nothing was saved: testing is not a side door into the repository.
        assertTrue(editor.state.value.draft.isNew)
    }

    /**
     * Config the factory refuses has to read as bad config, not as a failed run —
     * they call for different fixes, and the factory's own message is the one
     * written for a person.
     */
    @Test
    fun testing_an_action_with_config_its_factory_refuses_says_so() = runTest {
        val editor = viewModel()
        editor.addAction("open_url")
        editor.setConfigValue(Slot.ACTION, 0, "url", "not-a-url")

        editor.testAction(0)

        val result = editor.state.value.testResult
        assertNotNull(result)
        assertTrue("expected the component named, got: $result", result!!.contains("Open a website"))
        assertEquals("nothing should be left running", null, editor.state.value.testing)
    }

    /**
     * The stop half of the button, and the reason it is not optional: `play_alert`
     * loops for up to a minute, so a test that could not be cut short would be a
     * worse version of the trap the action's own duration cap exists to avoid.
     */
    @Test
    fun pressing_test_again_stops_a_running_action() = runTest {
        val editor = viewModel()
        editor.addAction("play_alert")
        // Long enough that it is certainly still running when stopped.
        editor.setConfigValue(Slot.ACTION, 0, "durationMillis", "60000")

        editor.testAction(0)
        assertEquals("the alert should be running", 0, editor.state.value.testing)

        editor.testAction(0)

        assertEquals(null, editor.state.value.testing)
        assertEquals("Stopped.", editor.state.value.testResult)
    }

    @Test
    fun testing_a_different_action_replaces_the_running_one() = runTest {
        val editor = viewModel()
        editor.addAction("play_alert")
        editor.setConfigValue(Slot.ACTION, 0, "durationMillis", "60000")
        editor.addAction("vibrate")

        editor.testAction(0)
        assertEquals(0, editor.state.value.testing)

        editor.testAction(1)

        // Whatever it settles on, it must not still claim to be running the first.
        assertTrue(editor.state.value.testing != 0)
    }

    /**
     * `docs/variables.md` section 12: the Test button substitutes samples and
     * says on screen that they are samples. `open_url` is the component that
     * proves it: `bluetooth_connected` declares `address` with a fixed sample,
     * and `OpenUrlAction.execute` echoes back whatever URL it was actually
     * given inside its own failure message. The sample showing up there is
     * proof the substitution reached the built action, not just the message
     * this test could have produced on its own.
     */
    @Test
    fun testing_an_action_that_reads_a_variable_uses_its_sample() = runTest {
        val editor = viewModel()
        editor.setName("Echo device address")
        editor.chooseTrigger("bluetooth_connected")
        editor.addAction("open_url")
        editor.setConfigValue(Slot.ACTION, 0, "url", "{{trigger.address}}")

        editor.testAction(0)

        val result = editor.state.value.testResult
        assertNotNull(result)
        val address = "AA:BB:CC:DD:EE:FF"
        assertTrue("expected the declared sample address in: $result", result!!.contains(address))
        assertTrue("expected a sample-value note in: $result", result.contains("sample value"))
    }

    @Test
    fun testing_an_action_with_a_variable_nobody_offers_is_reported_without_running() = runTest {
        val editor = viewModel()
        editor.addAction("open_url")
        editor.setConfigValue(Slot.ACTION, 0, "url", "{{trigger.nope}}")

        editor.testAction(0)

        val result = editor.state.value.testResult
        assertNotNull(result)
        assertTrue("was: $result", result!!.contains("could not fill in a sample"))
        assertEquals("nothing should be left running", null, editor.state.value.testing)
    }

    @Test
    fun actions_keep_the_order_they_were_given() = runTest {
        val repository = InMemoryRuleRepository()
        val editor = viewModel(repository)
        editor.setName("Ordered")
        editor.chooseTrigger("screen_state")
        editor.setTriggerConfigValue(emptyList(), "state", "on")
        editor.addAction("toast")
        editor.setConfigValue(Slot.ACTION, 0, "text", "first")
        editor.addAction("vibrate")

        editor.moveAction(1, 0)
        editor.save()

        assertEquals(
            listOf("vibrate", "toast"),
            repository.rules().first().single().actions.map { it.type },
        )
    }

    @Test
    fun deleting_removes_the_rule() = runTest {
        val existing = Rule(
            id = "doomed",
            name = "Doomed",
            trigger = ComponentSpec("screen_state", mapOf("state" to "on")),
            actions = listOf(ComponentSpec("toast", mapOf("text" to "hi"))),
        )
        val repository = InMemoryRuleRepository(listOf(existing))
        val editor = viewModel(repository, ruleId = "doomed")

        editor.delete()

        assertTrue(repository.rules().first().isEmpty())
    }

    @Test
    fun an_existing_rule_loads_into_the_form() = runTest {
        val existing = Rule(
            id = "existing",
            name = "Loaded",
            trigger = ComponentSpec("screen_state", mapOf("state" to "off")),
            actions = listOf(ComponentSpec("toast", mapOf("text" to "hello"))),
        )
        val repository = InMemoryRuleRepository(listOf(existing))

        val editor = viewModel(repository, ruleId = "existing")

        assertEquals("Loaded", editor.state.value.draft.name)
        assertEquals("off", editor.state.value.draft.trigger.leaf!!.config["state"])
        assertEquals("hello", editor.state.value.draft.actions.single().config["text"])
    }

    @Test
    fun a_rule_with_no_name_is_refused_with_a_readable_reason() = runTest {
        val editor = viewModel()
        editor.chooseTrigger("screen_state")
        editor.addAction("toast")

        editor.save()

        assertEquals("Give the rule a name.", editor.state.value.error)
    }

    /**
     * A rule mid-thought, not a mistake. See `RuleDraft.toRuleOrNull`. A blank
     * name is the only thing that still refuses a save, so this one goes
     * through, but disabled: [Rule.enableRefusal] is what stops an unfinished
     * rule being switched on, not the save.
     */
    @Test
    fun a_rule_with_no_trigger_saves_unfinished_and_disabled() = runTest {
        val repository = InMemoryRuleRepository()
        val editor = viewModel(repository)
        editor.setName("Triggerless")

        editor.save()

        assertNull("nothing should refuse the save", editor.state.value.error)
        assertTrue(editor.state.value.finished)
        val saved = repository.rules().first().single()
        assertFalse("an unfinished rule must not save enabled", saved.enabled)
        assertTrue(saved.trigger.leaves().isEmpty())
    }

    @Test
    fun a_rule_with_no_actions_saves_unfinished_and_disabled() = runTest {
        val repository = InMemoryRuleRepository()
        val editor = viewModel(repository)
        editor.setName("Does nothing")
        editor.chooseTrigger("screen_state")

        editor.save()

        assertNull(editor.state.value.error)
        val saved = repository.rules().first().single()
        assertFalse(saved.enabled)
        assertTrue(saved.actions.isEmpty())
    }

    /**
     * The editor's own switch, refusing before there is even anything saved
     * yet: one of the two switches this feature has to cover, and the message
     * names what is missing rather than only saying something is.
     */
    @Test
    fun the_editors_switch_refuses_to_turn_on_with_no_trigger() = runTest {
        val editor = viewModel()
        editor.setName("Triggerless")
        editor.addAction("toast")

        editor.setEnabled(true)

        assertEquals("Add a trigger before switching this on.", editor.state.value.error)
        assertFalse("the switch must not have moved", editor.state.value.draft.enabled)
    }

    @Test
    fun the_editors_switch_refuses_to_turn_on_with_no_actions() = runTest {
        val editor = viewModel()
        editor.setName("Does nothing")
        editor.chooseTrigger("screen_state")

        editor.setEnabled(true)

        assertEquals("Add an action before switching this on.", editor.state.value.error)
        assertFalse(editor.state.value.draft.enabled)
    }

    @Test
    fun the_editors_switch_names_both_when_both_are_missing() = runTest {
        val editor = viewModel()
        editor.setName("Nothing yet")

        editor.setEnabled(true)

        assertEquals(
            "Add a trigger and an action before switching this on.",
            editor.state.value.error,
        )
    }

    @Test
    fun turning_the_switch_off_is_never_refused() = runTest {
        val editor = viewModel()
        editor.setName("Triggerless")

        editor.setEnabled(false)

        assertNull("turning off is always allowed", editor.state.value.error)
        assertFalse(editor.state.value.draft.enabled)
    }

    /**
     * The case that matters most: a rule saved before it is finished, opened
     * again once it is, and switched on. That is the whole point of letting
     * it save unfinished in the first place.
     */
    @Test
    fun a_rule_saved_unfinished_then_finished_then_switched_on_works() = runTest {
        val repository = InMemoryRuleRepository()
        val editor = viewModel(repository)
        editor.setName("Half then whole")

        editor.save()
        val halfBuilt = repository.rules().first().single()
        assertFalse(halfBuilt.enabled)

        val reopened = viewModel(repository, ruleId = halfBuilt.id)
        reopened.chooseTrigger("screen_state")
        reopened.addAction("toast")
        reopened.setConfigValue(Slot.ACTION, 0, "text", "go")

        reopened.setEnabled(true)
        assertNull("the switch should now move", reopened.state.value.error)

        reopened.save()

        val finished = repository.rules().first().single()
        assertTrue(finished.enabled)
        assertEquals("screen_state", (finished.trigger as TriggerNode.One).spec.type)
        assertEquals(listOf("toast"), finished.actions.map { it.type })
    }

    @Test
    fun the_factory_decides_validity_and_its_message_is_shown() = runTest {
        // The watchdog enforces poll <= absence across two fields, which no
        // per-field schema can express. The editor must surface that rather than
        // save something the engine will reject.
        val repository = InMemoryRuleRepository()
        val editor = viewModel(repository)
        editor.setName("Watchdog")
        editor.chooseTrigger("notification_watchdog")
        editor.setTriggerConfigValue(emptyList(), "package", "com.example.alerts")
        editor.setTriggerConfigValue(emptyList(), "absenceMillis", "60000")
        editor.setTriggerConfigValue(emptyList(), "pollMillis", "120000")
        editor.addAction("toast")
        editor.setConfigValue(Slot.ACTION, 0, "text", "gone")

        editor.save()

        val error = editor.state.value.error
        assertTrue("was: $error", error!!.contains("pollMillis"))
        assertTrue("was: $error", error.contains("absenceMillis"))
        assertTrue("nothing should be stored", repository.rules().first().isEmpty())
    }

    @Test
    fun a_missing_required_field_is_reported_against_the_component_that_wants_it() = runTest {
        val editor = viewModel()
        editor.setName("No threshold")
        editor.chooseTrigger("battery_level")
        editor.addAction("toast")
        editor.setConfigValue(Slot.ACTION, 0, "text", "low")

        editor.save()

        val error = editor.state.value.error
        assertTrue("was: $error", error!!.startsWith("Battery level:"))
        assertTrue("was: $error", error.contains("threshold"))
    }

    @Test
    fun an_invalid_action_names_its_position() = runTest {
        val editor = viewModel()
        editor.setName("Second action broken")
        editor.chooseTrigger("screen_state")
        editor.setTriggerConfigValue(emptyList(), "state", "on")
        editor.addAction("toast")
        editor.setConfigValue(Slot.ACTION, 0, "text", "fine")
        editor.addAction("set_alarm") // needs an hour

        editor.save()

        assertTrue(editor.state.value.error!!.contains("action 2"))
    }

    /**
     * `docs/variables.md` section 9: a well-formed reference to a name nobody
     * declares is a save-time error, stated while the person is still looking
     * at the rule rather than discovered from a rule that silently never does
     * what it says.
     */
    @Test
    fun a_save_is_refused_for_a_variable_nobody_offers() = runTest {
        val repository = InMemoryRuleRepository()
        val editor = viewModel(repository)
        editor.setName("Echo")
        editor.chooseTrigger("bluetooth_connected")
        editor.addAction("toast")
        editor.setConfigValue(Slot.ACTION, 0, "text", "{{trigger.nope}}")

        editor.save()

        val error = editor.state.value.error
        assertNotNull("an unknown variable must refuse the save", error)
        assertTrue("was: $error", error!!.contains("trigger.nope"))
        assertTrue("nothing should be stored", repository.rules().first().isEmpty())
    }

    /**
     * `docs/variables.md` section 9 and section 12: a reference to a value that
     * is only *sometimes* present is a legitimate rule, not a save error. The
     * picker's mark is where that risk is communicated, not a refusal here.
     * `bluetooth_connected`'s `name` is declared `alwaysPresent = false`
     * because a Bluetooth device with no advertised name is common.
     */
    @Test
    fun a_reference_to_a_sometimes_present_value_saves() = runTest {
        val repository = InMemoryRuleRepository()
        val editor = viewModel(repository)
        editor.setName("Echo device name")
        editor.chooseTrigger("bluetooth_connected")
        editor.addAction("toast")
        editor.setConfigValue(Slot.ACTION, 0, "text", "Connected: {{trigger.name}}")

        editor.save()

        assertNull("a sometimes-empty reference is not a save error", editor.state.value.error)
        assertTrue(editor.state.value.finished)
        assertEquals(1, repository.rules().first().size)
    }

    /**
     * An action reads what an action above it produced. The engine has
     * resolved `{{action.*}}` from the moment `ActionOutputs` existed, but
     * `availableVariables` only ever walked the trigger tree, so save-time
     * validation refused every such field as a name nobody offers. The
     * feature worked in the engine and was unreachable from the editor.
     */
    @Test
    fun an_action_can_read_what_the_action_above_it_produced() = runTest {
        val repository = InMemoryRuleRepository()
        val editor = viewModel(repository)
        editor.setName("Count and say so")
        editor.chooseTrigger("bluetooth_connected")
        editor.addAction("set_variable")
        editor.setConfigValue(Slot.ACTION, 0, "name", "trip_count")
        editor.setConfigValue(Slot.ACTION, 0, "mode", "add")
        editor.setConfigValue(Slot.ACTION, 0, "value", "1")
        editor.addAction("toast")
        editor.setConfigValue(Slot.ACTION, 1, "text", "Trip {{action.value}}")

        editor.save()

        assertNull("an earlier action's output is a real name", editor.state.value.error)
        assertEquals(1, repository.rules().first().size)
    }

    /**
     * And the same reference one position higher is still refused. The engine
     * grows `ActionOutputs` as each action returns, so an action naming a
     * *later* action's output resolves absent on every firing. Accepting it
     * would be a rule that saves cleanly and never works, which is the
     * failure this whole check exists to prevent.
     */
    @Test
    fun an_action_cannot_read_what_a_later_action_produces() = runTest {
        val repository = InMemoryRuleRepository()
        val editor = viewModel(repository)
        editor.setName("Says it too early")
        editor.chooseTrigger("bluetooth_connected")
        editor.addAction("toast")
        editor.setConfigValue(Slot.ACTION, 0, "text", "Trip {{action.value}}")
        editor.addAction("set_variable")
        editor.setConfigValue(Slot.ACTION, 1, "name", "trip_count")
        editor.setConfigValue(Slot.ACTION, 1, "mode", "add")
        editor.setConfigValue(Slot.ACTION, 1, "value", "1")

        editor.save()

        val error = editor.state.value.error
        assertNotNull("a later action's output cannot be read", error)
        assertTrue("was: $error", error!!.contains("action.value"))
        assertTrue("nothing should be stored", repository.rules().first().isEmpty())
    }

    /**
     * The picker's half of the same answer, per action rather than per screen.
     * `set_rule_enabled` declares one output, so the action after it is
     * offered that and the action before it is not.
     */
    @Test
    fun the_picker_offers_an_action_output_only_below_the_action_that_makes_it() = runTest {
        val editor = viewModel()
        editor.chooseTrigger("bluetooth_connected")
        editor.addAction("set_rule_enabled")
        editor.addAction("toast")

        val first = editor.availableVariablesForAction(0)
        val second = editor.availableVariablesForAction(1)

        assertTrue(
            "the first action has nothing above it",
            first.none { it.scope == VariableScope.ACTION },
        )
        assertTrue(
            "was: ${second.map { it.reference }}",
            second.any { it.reference == "{{action.enabled}}" },
        )
    }

    /**
     * Phase 2: what `VariableStore.scoped()` holds has to reach the picker,
     * not only the trigger tree's own declarations. Collected into state in
     * [RuleEditorViewModel]'s `init`, under the same `UnconfinedTestDispatcher`
     * every other test here relies on to have already run by the time this
     * reads `availableVariables`.
     */
    @Test
    fun a_stored_app_variable_appears_in_available_variables() = runTest {
        val editor = viewModel(variableStore = InMemoryVariableStore(mapOf("trip_count" to "3")))

        val found = editor.availableVariables.singleOrNull {
            it.scope == VariableScope.APP && it.spec.key == "trip_count"
        }

        assertNotNull("the store's variable should be offered", found)
        assertEquals("3", found!!.spec.sample)
    }

    /**
     * `docs/variables.md` section 9: an app-scope reference is accepted on
     * sight, unlike `trigger.*`, because the rule that reads
     * `{{app.trip_count}}` is very often saved before the rule that first sets
     * it. This is the invariant most likely to be broken later by someone
     * tightening validation to "every reference must resolve against
     * something that exists right now" without noticing app scope is the one
     * place that rule is wrong.
     */
    @Test
    fun a_save_is_allowed_for_an_app_variable_the_store_has_never_written() = runTest {
        val repository = InMemoryRuleRepository()
        val editor = viewModel(repository, variableStore = InMemoryVariableStore())
        editor.setName("Reads before anything writes")
        editor.chooseTrigger("screen_state")
        editor.setTriggerConfigValue(emptyList(), "state", "on")
        editor.addAction("toast")
        editor.setConfigValue(Slot.ACTION, 0, "text", "{{app.not_yet_written}}")

        editor.save()

        assertNull("an app-scope reference is never a save error", editor.state.value.error)
        assertEquals(1, repository.rules().first().size)
    }

    /**
     * The same proof [testing_an_action_that_reads_a_variable_uses_its_sample]
     * gives for trigger scope, for app scope: `VariableStore.scoped()` samples
     * a variable with its own real value, "unlike a trigger's payload, this
     * one is known right now" — so a test run reads the value actually in the
     * store, not a placeholder.
     */
    @Test
    fun testing_an_action_that_reads_an_app_variable_uses_its_stored_value() = runTest {
        val editor = viewModel(variableStore = InMemoryVariableStore(mapOf("endpoint" to "trip-9")))
        editor.addAction("open_url")
        editor.setConfigValue(Slot.ACTION, 0, "url", "{{app.endpoint}}")

        editor.testAction(0)

        val result = editor.state.value.testResult
        assertNotNull(result)
        assertTrue("expected the stored value in: $result", result!!.contains("trip-9"))
        assertTrue("expected a sample-value note in: $result", result.contains("sample value"))
    }

    @Test
    fun an_edit_clears_a_stale_error() = runTest {
        val editor = viewModel()
        editor.save()
        assertTrue(editor.state.value.error != null)

        editor.setName("Now named")

        assertEquals(null, editor.state.value.error)
    }

    // --- Folder: the rule's own property, not a component's config. ---

    @Test
    fun setting_the_folder_reaches_the_draft() = runTest {
        val editor = viewModel()

        editor.setFolder("Car")

        assertEquals("Car", editor.state.value.draft.folder)
    }

    @Test
    fun an_existing_rules_folder_is_loaded_into_the_draft() = runTest {
        val existing = Rule(
            id = "rule-1",
            name = "Charger on",
            trigger = TriggerNode.One(ComponentSpec("power_connection", mapOf("state" to "connected"))),
            actions = listOf(ComponentSpec("speak", mapOf("text" to "Charging"))),
            folder = "Car",
        )
        val repository = InMemoryRuleRepository()
        repository.upsert(existing)

        val editor = viewModel(repository, ruleId = "rule-1")

        assertEquals("Car", editor.state.value.draft.folder)
    }

    /**
     * The whole reason [RuleDraft.folder] is a plain, non-nullable [String]
     * rather than mirroring [Rule.folder]'s nullability: a text field cannot
     * hold "unset", so the draft holds "" for it, and clearing the field has to
     * actually take the rule out of its folder rather than leaving the old
     * value in place because nothing collapsed it back to null.
     */
    @Test
    fun clearing_an_existing_folder_saves_with_no_folder() = runTest {
        val existing = Rule(
            id = "rule-1",
            name = "Charger on",
            trigger = TriggerNode.One(ComponentSpec("power_connection", mapOf("state" to "connected"))),
            actions = listOf(ComponentSpec("speak", mapOf("text" to "Charging"))),
            folder = "Car",
        )
        val repository = InMemoryRuleRepository()
        repository.upsert(existing)
        val editor = viewModel(repository, ruleId = "rule-1")
        assertEquals("Car", editor.state.value.draft.folder)

        editor.setFolder("")
        editor.save()

        val saved = repository.rules().first().single()
        assertNull("clearing the folder field must take the rule out of its folder", saved.folder)
    }

    /**
     * Whitespace is not a folder name — [normalizeFolder] in `:core` is where
     * that is decided, and [RuleDraft.toRuleOrNull] has to actually call it
     * rather than storing whatever the field held, or a rule could be saved
     * into a folder called "   " that looks identical to no folder at all and
     * still gets its own heading in the list.
     */
    @Test
    fun a_blank_or_whitespace_folder_saves_as_no_folder_rather_than_an_empty_string() = runTest {
        val editor = viewModel()
        editor.setName("Untitled")
        editor.chooseTrigger("screen_state")
        editor.setTriggerConfigValue(emptyList(), "state", "on")
        editor.addAction("toast")
        editor.setConfigValue(Slot.ACTION, 0, "text", "hi")
        editor.setFolder("   ")

        val rule = editor.state.value.draft.toRuleOrNull()

        assertNotNull(rule)
        assertNull(rule!!.folder)
    }

    @Test
    fun choosing_a_trigger_seeds_its_declared_defaults() = runTest {
        val editor = viewModel()

        editor.chooseTrigger("battery_level")

        // `direction` declares a default; `threshold` does not.
        assertEquals("below", editor.state.value.draft.trigger.leaf!!.config["direction"])
        assertEquals(null, editor.state.value.draft.trigger.leaf!!.config["threshold"])
    }

    @Test
    fun changing_type_keeps_settings_the_new_type_understands() = runTest {
        val editor = viewModel()
        editor.chooseTrigger("wifi_state")
        editor.setTriggerConfigValue(emptyList(), "state", "enabled")

        // Both use a state of enabled/disabled, so the choice should survive.
        editor.chooseTrigger("bluetooth_adapter_state")

        assertEquals("enabled", editor.state.value.draft.trigger.leaf!!.config["state"])
    }

    @Test
    fun changing_type_drops_settings_that_no_longer_apply() = runTest {
        val editor = viewModel()
        editor.chooseTrigger("battery_level")
        editor.setTriggerConfigValue(emptyList(), "threshold", "20")

        editor.chooseTrigger("screen_state")

        assertEquals(null, editor.state.value.draft.trigger.leaf!!.config["threshold"])
    }

    @Test
    fun clearing_a_field_removes_the_key_rather_than_storing_empty() = runTest {
        // Several components read an absent key as "match anything", which an
        // empty string would not.
        val editor = viewModel()
        editor.chooseTrigger("notification_posted")
        editor.setTriggerConfigValue(emptyList(), "package", "com.example")
        editor.setTriggerConfigValue(emptyList(), "package", "")

        assertTrue(!editor.state.value.draft.trigger.leaf!!.config.containsKey("package"))
    }

    @Test
    fun removing_an_action_leaves_the_others_alone() = runTest {
        val editor = viewModel()
        editor.chooseTrigger("screen_state")
        editor.addAction("toast")
        editor.addAction("vibrate")
        editor.addAction("speak")

        editor.removeAction(1)

        assertEquals(
            listOf("toast", "speak"),
            editor.state.value.draft.actions.map { it.type },
        )
    }

    @Test
    fun moving_an_action_out_of_range_is_ignored_rather_than_crashing() = runTest {
        val editor = viewModel()
        editor.chooseTrigger("screen_state")
        editor.addAction("toast")

        editor.moveAction(0, 5)

        assertEquals(listOf("toast"), editor.state.value.draft.actions.map { it.type })
    }

    // --- The trigger tree: a group is a trigger, chosen from the same picker. ---

    @Test
    fun adding_a_second_trigger_promotes_a_lone_one_into_an_all_group() = runTest {
        val editor = viewModel()
        editor.chooseTrigger("screen_state")

        editor.addTrigger(emptyList(), "power_connection")

        val group = editor.state.value.draft.trigger as TriggerDraft.Group
        assertEquals(TriggerNode.Op.ALL, group.op)
        assertEquals(
            listOf("screen_state", "power_connection"),
            group.children.map { it.leaf!!.type },
        )
    }

    @Test
    fun removing_a_child_back_down_to_one_collapses_the_group() = runTest {
        val editor = viewModel()
        editor.chooseTrigger("screen_state")
        editor.addTrigger(emptyList(), "power_connection")

        editor.removeTrigger(listOf(1))

        val trigger = editor.state.value.draft.trigger
        assertTrue("a group left with one child must un-promote back to a lone trigger", trigger is TriggerDraft.One)
        assertEquals("screen_state", trigger.leaf!!.type)
    }

    @Test
    fun removing_the_only_trigger_clears_the_slot() = runTest {
        val editor = viewModel()
        editor.chooseTrigger("screen_state")

        editor.removeTrigger(emptyList())

        assertNull(editor.state.value.draft.trigger)
    }

    /**
     * Builds `ALL(screen_state, ALL(power_connection, battery_level))` — the
     * three-deep shape several of the tests below share — by growing a lone
     * trigger through the same [RuleEditorViewModel.addTrigger] a person would
     * use: choose one, add a sibling (promotes to a group), then add a sibling
     * to that sibling (promotes it again, one level deeper).
     */
    private fun RuleEditorViewModel.buildThreeDeepTree() {
        chooseTrigger("screen_state")
        addTrigger(emptyList(), "power_connection")
        addTrigger(listOf(1), "battery_level")
    }

    @Test
    fun changing_a_type_at_a_nested_path_leaves_the_other_branches_untouched() = runTest {
        val editor = viewModel()
        editor.buildThreeDeepTree()

        editor.changeTriggerType(listOf(1, 0), "wifi_state")

        val root = editor.state.value.draft.trigger as TriggerDraft.Group
        assertEquals("screen_state", root.children[0].leaf!!.type)
        val inner = root.children[1] as TriggerDraft.Group
        assertEquals("wifi_state", inner.children[0].leaf!!.type)
        assertEquals("battery_level", inner.children[1].leaf!!.type)
    }

    @Test
    fun writing_two_config_keys_at_a_nested_path_keeps_the_tree_shape() = runTest {
        val editor = viewModel()
        editor.chooseTrigger("screen_state")
        editor.addTrigger(emptyList(), "power_connection")
        editor.addTrigger(listOf(1), "location")

        // What "Use where I am now" does: one latitude write, then one
        // longitude write, because a coordinate is two config keys.
        editor.setTriggerConfigValue(listOf(1, 1), "latitude", "52.5")
        editor.setTriggerConfigValue(listOf(1, 1), "longitude", "13.4")

        val root = editor.state.value.draft.trigger as TriggerDraft.Group
        assertEquals(2, root.children.size)
        val inner = root.children[1] as TriggerDraft.Group
        assertEquals(2, inner.children.size)
        val leaf = inner.children[1].leaf!!
        assertEquals("location", leaf.type)
        assertEquals("52.5", leaf.config["latitude"])
        assertEquals("13.4", leaf.config["longitude"])
    }

    /**
     * The shape a person builds by picking "Any of these" from the trigger
     * picker: it arrives empty, so it holds one child until the second is
     * added. Saving used to replace the group with that one child, so the OR
     * was gone when the rule was reopened.
     */
    @Test
    fun a_group_holding_one_trigger_keeps_its_group_through_a_save() = runTest {
        val editor = viewModel()
        editor.setName("Mixed")
        editor.addAction("toast")
        editor.setConfigValue(Slot.ACTION, 0, "text", "go")
        editor.chooseTrigger("screen_state")
        editor.addTrigger(emptyList(), GROUP_ANY_TYPE)
        editor.addTrigger(listOf(1), "power_connection")

        val saved = editor.state.value.draft.toRuleOrNull()!!.trigger as TriggerNode.Group

        assertEquals(TriggerNode.Op.ALL, saved.op)
        assertEquals(2, saved.children.size)
        val inner = saved.children[1] as TriggerNode.Group
        assertEquals(TriggerNode.Op.ANY, inner.op)
        assertEquals("power_connection", (inner.children.single() as TriggerNode.One).spec.type)
    }

    @Test
    fun an_or_group_inside_an_and_group_survives_a_save_intact() = runTest {
        val editor = viewModel()
        editor.setName("Mixed")
        editor.addAction("toast")
        editor.setConfigValue(Slot.ACTION, 0, "text", "go")
        editor.chooseTrigger("screen_state")
        editor.addTrigger(emptyList(), GROUP_ANY_TYPE)
        editor.addTrigger(listOf(1), "power_connection")
        editor.addTrigger(listOf(1), "battery_level")

        val saved = editor.state.value.draft.toRuleOrNull()!!.trigger as TriggerNode.Group

        assertEquals(TriggerNode.Op.ALL, saved.op)
        assertEquals("screen_state", (saved.children[0] as TriggerNode.One).spec.type)
        val inner = saved.children[1] as TriggerNode.Group
        assertEquals(TriggerNode.Op.ANY, inner.op)
        assertEquals(
            listOf("power_connection", "battery_level"),
            inner.children.map { (it as TriggerNode.One).spec.type },
        )
    }

    /**
     * A group with nothing in it used to refuse the save outright, even
     * though the rule beside it was otherwise complete. It is kept in the
     * saved tree exactly as it stood, unfinished branch and all. See
     * `TriggerDraft.toNode`. It is switched off rather than refused, the same
     * as a rule with no trigger at all.
     */
    @Test
    fun an_empty_group_nested_in_the_tree_saves_unfinished_and_disabled() = runTest {
        val repository = InMemoryRuleRepository()
        val editor = viewModel(repository)
        editor.setName("Half built")
        editor.addAction("toast")
        editor.setConfigValue(Slot.ACTION, 0, "text", "go")
        editor.chooseTrigger("screen_state")
        editor.addTrigger(emptyList(), GROUP_ANY_TYPE)

        editor.save()

        assertNull(editor.state.value.error)
        assertTrue(editor.state.value.finished)
        val saved = repository.rules().first().single()
        assertFalse(saved.enabled)
        val root = saved.trigger as TriggerNode.Group
        assertEquals(TriggerNode.Group(TriggerNode.Op.ANY, emptyList()), root.children[1])
    }

    /**
     * An unfilled group must not empty the picker. Refusing to convert a tree
     * that holds an empty group is right at save time and wrong here: it would
     * filter out every candidate, including the ones that would fill the group.
     */
    @Test
    fun an_empty_group_in_the_tree_does_not_empty_the_picker() = runTest {
        val editor = viewModel()
        editor.chooseTrigger("screen_state")
        editor.addTrigger(emptyList(), GROUP_ANY_TYPE)

        // Inside the empty group, and at the root beside it.
        assertTrue(editor.triggerOptionsFor(listOf(1)).size > 1)
        assertTrue(editor.triggerOptionsFor(emptyList()).size > 1)
    }

    /**
     * "Is in an area" is not offered where it would be the only trigger.
     *
     * It declares `producesEvents = false`, so a tree holding it alone cannot
     * start, and the picker is derived from exactly that question. This is what
     * makes it a safe picker row rather than a switch: as a switch it could be
     * turned on after the leaf existed, and the rule became unstartable in place.
     */
    @Test
    fun the_area_check_is_not_offered_as_a_rules_only_trigger() = runTest {
        val editor = viewModel()

        val atRoot = editor.triggerOptionsFor(emptyList()).map { it.type }

        assertTrue("the watching one is offered", "location" in atRoot)
        assertFalse("the checking one cannot start a rule", "location_check" in atRoot)
    }

    /** And is offered beside a trigger that can start the rule. */
    @Test
    fun the_area_check_is_offered_beside_a_trigger_that_starts_the_rule() = runTest {
        val editor = viewModel()
        editor.chooseTrigger("screen_state")

        val beside = editor.triggerOptionsFor(emptyList()).map { it.type }

        assertTrue("location_check was not offered: $beside", "location_check" in beside)
    }

    /**
     * Switching a block between watching an area and checking one keeps what was
     * typed. The two factories share their config keys for this reason: changing
     * your mind is one tap and costs no coordinates.
     */
    @Test
    fun swapping_between_watching_an_area_and_checking_it_keeps_the_coordinates() = runTest {
        val editor = viewModel()
        editor.chooseTrigger("screen_state")
        editor.addTrigger(emptyList(), "location")
        editor.setTriggerConfigValue(listOf(1), "latitude", "52.5")
        editor.setTriggerConfigValue(listOf(1), "longitude", "13.4")
        editor.setTriggerConfigValue(listOf(1), "radiusMeters", "150")
        editor.setTriggerConfigValue(listOf(1), "state", "entered")

        editor.changeTriggerType(listOf(1), "location_check")

        val leaf = (editor.state.value.draft.trigger as TriggerDraft.Group).children[1].leaf!!
        assertEquals("location_check", leaf.type)
        assertEquals("52.5", leaf.config["latitude"])
        assertEquals("13.4", leaf.config["longitude"])
        assertEquals("150", leaf.config["radiusMeters"])
        assertEquals("entered", leaf.config["state"])
    }

    /**
     * A rule whose only trigger only answers a question is not "unfinished":
     * there is a trigger, and it is not incomplete, only unable to ever start
     * the rule it is attached to. That is a different problem from no trigger
     * at all, and it moved from a save-time refusal to an enable-time one. The
     * save now goes through, disabled; the switch is where the reason is
     * given, both here in the editor and from the rules list.
     */
    @Test
    fun a_rule_whose_only_trigger_only_checks_saves_unfinished_and_refuses_to_enable() = runTest {
        val repository = InMemoryRuleRepository()
        val editor = viewModel(repository)
        editor.setName("Home only")
        editor.addAction("toast")
        editor.setConfigValue(Slot.ACTION, 0, "text", "here")
        editor.chooseTrigger("location_check")
        editor.setTriggerConfigValue(emptyList(), "latitude", "52.5")
        editor.setTriggerConfigValue(emptyList(), "longitude", "13.4")
        editor.setTriggerConfigValue(emptyList(), "radiusMeters", "100")
        editor.setTriggerConfigValue(emptyList(), "state", "entered")

        editor.save()

        assertNull("nothing should refuse the save", editor.state.value.error)
        val saved = repository.rules().first().single()
        assertFalse("cannot ever start, so it must not save enabled", saved.enabled)

        editor.setEnabled(true)

        val error = editor.state.value.error
        assertTrue("was: $error", error!!.startsWith("This rule can never start."))
        assertFalse("the switch must not have moved", editor.state.value.draft.enabled)
    }

    /** The shape it exists for: something else starts the rule, the area answers. */
    @Test
    fun an_area_check_beside_a_starting_trigger_saves() = runTest {
        val editor = viewModel()
        editor.setName("Home and screen")
        editor.addAction("toast")
        editor.setConfigValue(Slot.ACTION, 0, "text", "here")
        editor.chooseTrigger("screen_state")
        editor.setTriggerConfigValue(emptyList(), "state", "on")
        editor.addTrigger(emptyList(), "location_check")
        editor.setTriggerConfigValue(listOf(1), "latitude", "52.5")
        editor.setTriggerConfigValue(listOf(1), "longitude", "13.4")
        editor.setTriggerConfigValue(listOf(1), "radiusMeters", "100")
        editor.setTriggerConfigValue(listOf(1), "state", "entered")

        editor.save()

        assertEquals(null, editor.state.value.error)
    }

    @Test
    fun set_trigger_op_at_a_nested_path_leaves_the_root_op_alone() = runTest {
        val editor = viewModel()
        editor.buildThreeDeepTree()

        editor.setTriggerOp(listOf(1), TriggerNode.Op.ANY)

        val root = editor.state.value.draft.trigger as TriggerDraft.Group
        assertEquals(TriggerNode.Op.ALL, root.op)
        val inner = root.children[1] as TriggerDraft.Group
        assertEquals(TriggerNode.Op.ANY, inner.op)
    }

    @Test
    fun to_rule_or_null_builds_the_expected_tree_for_a_three_deep_draft() = runTest {
        val editor = viewModel()
        editor.setName("Nested")
        editor.buildThreeDeepTree()
        editor.addAction("toast")
        editor.setConfigValue(Slot.ACTION, 0, "text", "go")

        val rule = editor.state.value.draft.toRuleOrNull()

        assertNotNull(rule)
        val root = rule!!.trigger as TriggerNode.Group
        assertEquals(TriggerNode.Op.ALL, root.op)
        assertEquals("screen_state", (root.children[0] as TriggerNode.One).spec.type)
        val inner = root.children[1] as TriggerNode.Group
        assertEquals(TriggerNode.Op.ALL, inner.op)
        assertEquals(
            listOf("power_connection", "battery_level"),
            inner.children.map { (it as TriggerNode.One).spec.type },
        )
    }

    /**
     * `time_window`'s `events()` is empty by design — see `TriggerFactory
     * .producesEvents` — so a rule built around it alone would wait forever
     * with nothing on screen to say why. The picker has to refuse it before
     * the rule is ever saved, since [RuleEditorViewModel.save] only catches
     * config a factory *refuses*, not a tree that is merely pointless.
     */
    @Test
    fun trigger_options_for_the_empty_root_exclude_a_component_that_cannot_start_alone() = runTest {
        val editor = viewModel()

        val options = editor.triggerOptionsFor(emptyList())

        assertTrue(
            "time_window cannot be a rule's only trigger",
            options.none { it.type == "time_window" },
        )
        assertTrue(options.any { it.type == "screen_state" })
    }

    /**
     * `sms_received` and `clipboard_changed` are both pure edges — neither can
     * answer "is this true right now" — so an `ALL` group holding both could
     * never be satisfied: whichever fires, the other is asked for a state it
     * does not have. `power_connection` can answer that question, so it is
     * still offered alongside the same edge.
     */
    @Test
    fun trigger_options_exclude_a_second_edge_only_component_from_an_all_group_that_already_has_one() = runTest {
        val editor = viewModel()
        editor.chooseTrigger("sms_received")
        editor.addTrigger(emptyList(), "screen_state")

        val options = editor.triggerOptionsFor(emptyList())

        assertTrue(
            "a second edge-only component can never join this ALL group",
            options.none { it.type == "clipboard_changed" },
        )
        assertTrue(
            "a component that can also answer a state question is still welcome",
            options.any { it.type == "power_connection" },
        )
    }

    @Test
    fun the_pickers_hide_what_this_device_can_never_run() {
        // Stand-in components rather than real ones, so the assertion does not
        // depend on what the test device happens to support.
        val editor = RuleEditorViewModel(
            InMemoryRuleRepository(),
            Registry(
                triggerFactories = listOf(
                    FakeTriggerFactory(
                        "from_the_future",
                        "From the future",
                        ComponentRequirement.MinApiLevel(Build.VERSION.SDK_INT + 1),
                    ),
                    FakeTriggerFactory(
                        "absent_hardware",
                        "Absent hardware",
                        ComponentRequirement.SystemFeature("trigly.test.no.such.feature"),
                    ),
                    FakeTriggerFactory(
                        "needs_permission",
                        "Needs a permission",
                        ComponentRequirement.RuntimePermission("android.permission.READ_SMS"),
                    ),
                    FakeTriggerFactory(
                        "play_restricted",
                        "Play would refuse this",
                        ComponentRequirement.PolicyRestricted("Play policy"),
                    ),
                    FakeTriggerFactory("plain", "Plain"),
                ),
                actionFactories = emptyList(),
            ),
            RequirementChecker(context),
            InMemoryVariableStore(),
            InMemoryRuleVariableStore(),
            null,
        )

        // Hidden: nothing the user can do fixes a missing API or a missing radio.
        // Kept: a permission is a prompt away, and a Play restriction says
        // nothing about whether it works on the device in front of you.
        assertEquals(
            listOf("needs_permission", "plain", "play_restricted"),
            editor.triggerOptions.map { it.type }.sorted(),
        )
    }

    @Test
    fun a_hidden_component_still_resolves_for_a_rule_that_already_uses_it() {
        // The filter is for the picker only. An imported rule, or one built on a
        // newer phone, must still render rather than going blank.
        val editor = RuleEditorViewModel(
            InMemoryRuleRepository(),
            Registry(
                triggerFactories = listOf(
                    FakeTriggerFactory(
                        "from_the_future",
                        "From the future",
                        ComponentRequirement.MinApiLevel(Build.VERSION.SDK_INT + 1),
                    ),
                ),
                actionFactories = emptyList(),
            ),
            RequirementChecker(context),
            InMemoryVariableStore(),
            InMemoryRuleVariableStore(),
            null,
        )

        assertTrue(editor.triggerOptions.isEmpty())
        assertNotNull(editor.descriptorFor(Slot.TRIGGER, "from_the_future"))
    }

    /**
     * The bug: "New rule" showed the last one.
     *
     * An unsaved rule has no id, so its editor can only be keyed on the constant
     * "editor-new" — and these ViewModels live in the activity's store, so that
     * one instance served every new rule for the life of the activity, carrying
     * the previous draft with it. [RuleEditorViewModel.reset] is what the screen
     * calls when it is genuinely left, and this is the behaviour it owes.
     */
    @Test
    fun resetting_a_new_rule_leaves_it_empty() = runTest {
        val editor = viewModel()

        editor.setName("Half-built")
        editor.chooseTrigger("power_connection")
        editor.addAction("speak")

        editor.reset()

        val draft = editor.state.value.draft
        assertEquals("", draft.name)
        assertNull("a reset new rule must have no trigger", draft.trigger)
        assertTrue("a reset new rule must have no actions", draft.actions.isEmpty())
        assertTrue("and must still be a new rule", draft.isNew)
    }

    /**
     * For a rule that exists, "empty" is the wrong target — the stored rule is.
     * Reset reloads rather than blanks, so abandoning an edit and reopening shows
     * what is saved, not the abandoned typing and not a blank form.
     */
    @Test
    fun resetting_an_existing_rule_reloads_what_is_stored() = runTest {
        val stored = Rule(
            id = "rule-1",
            name = "Charger on",
            trigger = ComponentSpec("power_connection", mapOf("state" to "connected")),
            actions = listOf(ComponentSpec("speak", mapOf("text" to "Charging"))),
        )
        val repository = InMemoryRuleRepository()
        repository.upsert(stored)

        val editor = viewModel(repository, ruleId = "rule-1")
        assertEquals("Charger on", editor.state.value.draft.name)

        editor.setName("Abandoned edit")
        editor.removeAction(0)
        editor.reset()

        val draft = editor.state.value.draft
        assertEquals("Charger on", draft.name)
        assertEquals(listOf("speak"), draft.actions.map { it.type })
        assertEquals("rule-1", draft.id)
    }

    /**
     * The editor's *exit* now calls [RuleEditorViewModel.stopTest], not [reset],
     * and that difference is the fix: leaving the editor — a rotation is a leave
     * too — must not throw the draft away. Emptying a new rule happens on entry
     * instead. So stopping a test has to silence a run and touch nothing else.
     */
    @Test
    fun stopping_a_test_leaves_the_draft_alone() = runTest {
        val editor = viewModel()

        editor.setName("Half-built")
        editor.chooseTrigger("power_connection")
        editor.addAction("speak")

        editor.stopTest()

        val draft = editor.state.value.draft
        assertEquals("Half-built", draft.name)
        assertEquals("power_connection", draft.trigger.leaf?.type)
        assertEquals(listOf("speak"), draft.actions.map { it.type })
        assertNull("stopping a test must not leave a run marked active", editor.state.value.testing)
    }

    private class FakeTriggerFactory(
        override val type: String,
        override val displayName: String,
        vararg requirement: ComponentRequirement,
    ) : TriggerFactory {
        override val category: String = "Test"
        override val requirements: List<ComponentRequirement> = requirement.toList()
        override fun create(config: Map<String, String>): Trigger =
            object : Trigger {
                override fun events() = emptyFlow<TriggerEvent>()
            }
    }
}
