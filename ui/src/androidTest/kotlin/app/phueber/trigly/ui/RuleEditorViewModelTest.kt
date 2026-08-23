package app.phueber.trigly.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.phueber.trigly.actions.actionFactories
import android.os.Build
import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.ComponentSpec
import app.phueber.trigly.core.InMemoryRuleRepository
import app.phueber.trigly.core.NotificationController
import app.phueber.trigly.core.Registry
import app.phueber.trigly.core.RequirementChecker
import app.phueber.trigly.core.Rule
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerEvent
import app.phueber.trigly.core.TriggerFactory
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The editor's logic, driven directly against the real registry so the schemas
 * the 46 factories declare are what gets exercised.
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
        triggerFactories = triggerFactories(context),
        actionFactories = actionFactories(context, NotificationController.Unavailable),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        repository: InMemoryRuleRepository = InMemoryRuleRepository(),
        ruleId: String? = null,
    ) = RuleEditorViewModel(repository, registry, RequirementChecker(context), ruleId)

    @Test
    fun a_complete_rule_saves() = runTest {
        val repository = InMemoryRuleRepository()
        val editor = viewModel(repository)

        editor.setName("Charger on")
        editor.chooseTrigger("power_connection")
        editor.setConfigValue(Slot.TRIGGER, 0, "state", "connected")
        editor.addAction("speak")
        editor.setConfigValue(Slot.ACTION, 0, "text", "Charging")
        editor.save()

        assertTrue("save should report completion", editor.state.value.finished)
        val saved = repository.rules().first().single()
        assertEquals("Charger on", saved.name)
        assertEquals("power_connection", saved.trigger.type)
        assertEquals("connected", saved.trigger.config["state"])
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
        editor.setConfigValue(Slot.TRIGGER, 0, "state", "connected")
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

    @Test
    fun actions_keep_the_order_they_were_given() = runTest {
        val repository = InMemoryRuleRepository()
        val editor = viewModel(repository)
        editor.setName("Ordered")
        editor.chooseTrigger("screen_state")
        editor.setConfigValue(Slot.TRIGGER, 0, "state", "on")
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
        assertEquals("off", editor.state.value.draft.trigger!!.config["state"])
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

    @Test
    fun a_rule_with_no_trigger_is_refused() = runTest {
        val editor = viewModel()
        editor.setName("Triggerless")

        editor.save()

        assertEquals("Choose a trigger.", editor.state.value.error)
    }

    @Test
    fun a_rule_with_no_actions_is_refused() = runTest {
        val editor = viewModel()
        editor.setName("Does nothing")
        editor.chooseTrigger("screen_state")

        editor.save()

        assertTrue(editor.state.value.error!!.contains("at least one action"))
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
        editor.setConfigValue(Slot.TRIGGER, 0, "package", "com.example.alerts")
        editor.setConfigValue(Slot.TRIGGER, 0, "absenceMillis", "60000")
        editor.setConfigValue(Slot.TRIGGER, 0, "pollMillis", "120000")
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
        editor.setConfigValue(Slot.TRIGGER, 0, "state", "on")
        editor.addAction("toast")
        editor.setConfigValue(Slot.ACTION, 0, "text", "fine")
        editor.addAction("set_alarm") // needs an hour

        editor.save()

        assertTrue(editor.state.value.error!!.contains("action 2"))
    }

    @Test
    fun an_edit_clears_a_stale_error() = runTest {
        val editor = viewModel()
        editor.save()
        assertTrue(editor.state.value.error != null)

        editor.setName("Now named")

        assertEquals(null, editor.state.value.error)
    }

    @Test
    fun choosing_a_trigger_seeds_its_declared_defaults() = runTest {
        val editor = viewModel()

        editor.chooseTrigger("battery_level")

        // `direction` declares a default; `threshold` does not.
        assertEquals("below", editor.state.value.draft.trigger!!.config["direction"])
        assertEquals(null, editor.state.value.draft.trigger!!.config["threshold"])
    }

    @Test
    fun changing_type_keeps_settings_the_new_type_understands() = runTest {
        val editor = viewModel()
        editor.chooseTrigger("wifi_state")
        editor.setConfigValue(Slot.TRIGGER, 0, "state", "enabled")

        // Both use a state of enabled/disabled, so the choice should survive.
        editor.chooseTrigger("bluetooth_adapter_state")

        assertEquals("enabled", editor.state.value.draft.trigger!!.config["state"])
    }

    @Test
    fun changing_type_drops_settings_that_no_longer_apply() = runTest {
        val editor = viewModel()
        editor.chooseTrigger("battery_level")
        editor.setConfigValue(Slot.TRIGGER, 0, "threshold", "20")

        editor.chooseTrigger("screen_state")

        assertEquals(null, editor.state.value.draft.trigger!!.config["threshold"])
    }

    @Test
    fun clearing_a_field_removes_the_key_rather_than_storing_empty() = runTest {
        // Several components read an absent key as "match anything", which an
        // empty string would not.
        val editor = viewModel()
        editor.chooseTrigger("notification_posted")
        editor.setConfigValue(Slot.TRIGGER, 0, "package", "com.example")
        editor.setConfigValue(Slot.TRIGGER, 0, "package", "")

        assertTrue(!editor.state.value.draft.trigger!!.config.containsKey("package"))
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
            null,
        )

        assertTrue(editor.triggerOptions.isEmpty())
        assertNotNull(editor.descriptorFor(Slot.TRIGGER, "from_the_future"))
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
