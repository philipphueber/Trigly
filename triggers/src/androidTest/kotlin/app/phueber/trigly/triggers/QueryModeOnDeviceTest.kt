package app.phueber.trigly.triggers

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.phueber.trigly.core.Action
import app.phueber.trigly.core.ActionFactory
import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.ComponentSpec
import app.phueber.trigly.core.InMemoryRuleVariableStore
import app.phueber.trigly.core.InMemoryVariableStore
import app.phueber.trigly.core.Registry
import app.phueber.trigly.core.Rule
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerEngine
import app.phueber.trigly.core.TriggerEvent
import app.phueber.trigly.core.TriggerNode
import app.phueber.trigly.core.TriggerTrace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The two components of this file, asked as conditions on a real device.
 *
 * Instrumented, and it has to be. A condition answers through
 * [Trigger.currentlyHolds], which reads a system service or a sticky broadcast
 * behind a `runCatching`. A JVM test sees a stub, so it sees neither the
 * permission the platform demands nor the value the platform holds. The fault
 * this file exists for was exactly that: `WifiManager.isWifiEnabled` threw
 * `SecurityException`, the catch turned it into `null`, and `null` does not
 * hold. The rule could never fire and nothing on screen said why.
 */
@RunWith(AndroidJUnit4::class)
class QueryModeOnDeviceTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation()
            .uiAutomation.executeShellCommand(command)
        return FileInputStream(descriptor.fileDescriptor).use { it.readBytes().decodeToString() }
    }

    /** What the platform says right now, read the way nothing in the app reads it. */
    private fun pluggedBySystem(): Boolean =
        shell("dumpsys battery").lineSequence()
            .filter { it.contains("powered:") }
            .any { it.substringAfter("powered:").trim() == "true" }

    private fun wifiEnabledBySystem(): Boolean =
        shell("settings get global wifi_on").trim() == "1"

    /**
     * The device state this file changes is restored whether the test passed or
     * failed. A left-over `dumpsys battery unplug` is exactly the leaked state
     * that makes the next run of this file, or of anything else on this device,
     * fail for a reason that has nothing to do with the code.
     */
    @After
    fun restoreTheBattery() {
        shell("dumpsys battery reset")
    }

    private fun wifi(state: String): Trigger =
        WifiStateTriggerFactory(context).create(mapOf(WifiStateTrigger.CONFIG_STATE to state))

    private fun charger(state: String): Trigger =
        PowerConnectionTriggerFactory(context)
            .create(mapOf(PowerConnectionTrigger.CONFIG_STATE to state))

    /**
     * Waits for [answer] to reach [expected], because a `dumpsys` change reaches
     * the app through a broadcast rather than instantly. Bounded, and the
     * assertion after it is what fails: a component that never gets there fails
     * with the answer it was stuck on rather than by hanging.
     */
    private suspend fun settle(expected: Boolean?, answer: suspend () -> Boolean?): Boolean? {
        repeat(30) {
            val now = answer()
            if (now == expected) return now
            Thread.sleep(100)
        }
        return answer()
    }

    // --- The Wi-Fi radio ------------------------------------------------------

    /**
     * The regression: with no `ACCESS_WIFI_STATE` in any manifest,
     * `WifiManager.isWifiEnabled` throws and both directions answer `null`.
     * Asserted against the radio's real state rather than against a fixed
     * value, so the test neither changes the radio nor depends on which way
     * someone left it.
     */
    @Test
    fun the_wifi_radio_answers_a_state_and_not_unknown() = runBlocking {
        val enabled = wifiEnabledBySystem()

        assertEquals(
            "the radio is ${if (enabled) "on" else "off"}, so 'Wi-Fi is on' must say so",
            enabled,
            wifi(WifiStateTrigger.ENABLED).currentlyHolds(),
        )
        assertEquals(
            "and 'Wi-Fi is off' is the same fact, read the other way",
            !enabled,
            wifi(WifiStateTrigger.DISABLED).currentlyHolds(),
        )
    }

    // --- The charger ----------------------------------------------------------

    /**
     * The charger's level comes from a *different* broadcast than its events:
     * `ACTION_POWER_CONNECTED` is edge-only, so the level reads `EXTRA_PLUGGED`
     * off the sticky `ACTION_BATTERY_CHANGED`. Both directions are asked, in
     * both states of the device.
     *
     * **One trigger instance is asked across the change**, which is how the
     * engine asks: it builds one trigger per leaf when the rule starts and keeps
     * it for the life of the rule. An instance that answered from something it
     * read once would pass a per-state check and fail here.
     */
    @Test
    fun the_charger_answers_a_state_and_follows_the_device() = runBlocking {
        val connected = charger(PowerConnectionTrigger.CONNECTED)
        val disconnected = charger(PowerConnectionTrigger.DISCONNECTED)

        shell("dumpsys battery unplug")
        assertEquals("unplugged", false, settle(false) { connected.currentlyHolds() })
        assertEquals("unplugged, read the other way", true, disconnected.currentlyHolds())

        shell("dumpsys battery set ac 1")
        assertEquals("plugged in", true, settle(true) { connected.currentlyHolds() })
        assertEquals("plugged in, read the other way", false, disconnected.currentlyHolds())

        // And the platform agrees, so the two assertions above are about the
        // device rather than about a value the test itself supplied.
        assertEquals("the system says plugged in too", true, pluggedBySystem())
    }

    /**
     * What the charger's level reads, checked against the extra it reads it
     * from. The point is the *mapping*: any non-zero `EXTRA_PLUGGED` means some
     * charger is attached, and a missing extra is unknown rather than
     * "unplugged".
     */
    @Test
    fun the_charger_reads_the_plugged_extra_and_not_the_charging_status() = runBlocking {
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1

        assertEquals(
            "a sticky battery broadcast with no EXTRA_PLUGGED would make this test meaningless",
            true,
            plugged >= 0,
        )
        assertEquals(
            "any non-zero value is a charger",
            plugged != 0,
            charger(PowerConnectionTrigger.CONNECTED).currentlyHolds(),
        )
    }

    // --- The two together, which is how the report arrived -------------------

    /**
     * The report, reproduced whole: *"Wi-Fi radio // Charger do not work
     * correctly in query mode."*
     *
     * One rule, two leaves, and the charger is the one that starts it: plug the
     * charger in **and** the Wi-Fi radio is on. The charger fires a real
     * `ACTION_POWER_CONNECTED`, the engine asks the other leaf for its state,
     * and the rule runs.
     *
     * This is why one missing permission reads as two broken components. The
     * Wi-Fi leaf answered `null`, `null` does not hold, and the rule was
     * dropped. On screen that is the *charger* doing nothing when the charger
     * is plugged in, which is what the second half of the report describes. The
     * engine is honest about it in `onSuppressed`, which is where the assertion
     * below looks when the rule does not run.
     *
     * The whole engine, not just the trigger: the fault lives between a leaf
     * that fires and a leaf that is asked, and nothing below that pairing can
     * see it.
     */
    @Test
    fun the_charger_starts_a_rule_that_the_wifi_radio_checks() = runBlocking {
        enableTheWifiRadio()
        shell("dumpsys battery unplug")
        assertEquals(
            "the charger has to start out unplugged, or plugging it in below is no edge",
            false,
            settle(false) { charger(PowerConnectionTrigger.CONNECTED).currentlyHolds() },
        )

        val ran = CountDownLatch(1)
        val unreadable = mutableListOf<ComponentSpec>()
        // Every evaluation the engine ran, so a failure can say whether the
        // charger's event arrived at all. Without that, "the rule did not run"
        // and "nothing fired" read the same, and they are different faults.
        val evaluations = mutableListOf<String>()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val engine = TriggerEngine(
            registry = Registry(
                triggerFactories = listOf(
                    PowerConnectionTriggerFactory(context),
                    WifiStateTriggerFactory(context),
                ),
                actionFactories = listOf(RecordingActionFactory { ran.countDown() }),
            ),
            store = InMemoryVariableStore(),
            ruleStore = InMemoryRuleVariableStore(),
            scope = scope,
            onSuppressed = { _, _, specs -> synchronized(unreadable) { unreadable += specs } },
            onEvaluated = { _, event, trace ->
                synchronized(evaluations) { evaluations += "${event.triggerType}: ${describe(trace)}" }
            },
        )

        try {
            engine.startRule(
                Rule(
                    id = "query-mode",
                    name = "Charger, if the Wi-Fi radio is on",
                    trigger = TriggerNode.Group(
                        TriggerNode.Op.ALL,
                        listOf(
                            TriggerNode.One(
                                ComponentSpec(
                                    PowerConnectionTrigger.TYPE,
                                    mapOf(
                                        PowerConnectionTrigger.CONFIG_STATE to
                                            PowerConnectionTrigger.CONNECTED
                                    ),
                                )
                            ),
                            TriggerNode.One(
                                ComponentSpec(
                                    WifiStateTrigger.TYPE,
                                    mapOf(WifiStateTrigger.CONFIG_STATE to WifiStateTrigger.ENABLED),
                                )
                            ),
                        ),
                    ),
                    actions = listOf(ComponentSpec(RECORDING_ACTION)),
                )
            )
            // The receiver registers when the flow is collected, which happens on
            // the engine's scope rather than on this thread.
            Thread.sleep(1_000)

            shell("dumpsys battery set ac 1")
            assertEquals(
                "the charger has to read as plugged in, or no event was ever sent",
                true,
                settle(true) { charger(PowerConnectionTrigger.CONNECTED).currentlyHolds() },
            )

            // Waited for *before* the message is built, because the message
            // reports what the engine did and an argument is evaluated before
            // the call it belongs to. Building it first reports an engine that
            // has not been given its thirty seconds yet, which reads as "the
            // event never arrived" and is a different fault entirely.
            val didRun = ran.await(30, TimeUnit.SECONDS)

            assertEquals(
                "the rule did not run. Evaluations: " +
                    "${synchronized(evaluations) { evaluations.toList() }}. Leaves that " +
                    "could not answer: " +
                    "${synchronized(unreadable) { unreadable.map { it.type } }}",
                true,
                didRun,
            )
        } finally {
            engine.stop()
            scope.cancel()
        }
    }

    /**
     * The radio is switched on rather than assumed on, so the test carries its
     * own setup instead of depending on how the last run left the device. That
     * is the same reason the battery is reset in [restoreTheBattery]: a test
     * that needs a device state must make it, or it passes once and fails on
     * the next run.
     */
    private fun enableTheWifiRadio() {
        if (wifiEnabledBySystem()) return
        shell("svc wifi enable")
        repeat(50) {
            if (wifiEnabledBySystem()) return
            Thread.sleep(100)
        }
    }
}

/** One evaluation, flattened to something a failure message can carry. */
private fun describe(trace: TriggerTrace): String = when (trace) {
    is TriggerTrace.Leaf -> "${trace.spec.type}=${trace.outcome}"
    is TriggerTrace.Group -> trace.children.joinToString(
        separator = ", ",
        prefix = "${trace.op}(",
        postfix = ")=${trace.held}",
    ) { describe(it) }
}

private const val RECORDING_ACTION = "recording"

/** Counts one run of a rule, and nothing else. */
private class RecordingActionFactory(private val onRun: () -> Unit) : ActionFactory {
    override val type = RECORDING_ACTION

    override fun create(config: Map<String, String>): Action = object : Action {
        override suspend fun execute(event: TriggerEvent): ActionResult {
            onRun()
            return ActionResult.Success()
        }
    }
}
