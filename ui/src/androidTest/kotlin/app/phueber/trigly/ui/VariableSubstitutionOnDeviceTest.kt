package app.phueber.trigly.ui

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.phueber.trigly.actions.PostNotificationAction
import app.phueber.trigly.actions.actionFactories
import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.ComponentSpec
import app.phueber.trigly.core.Registry
import app.phueber.trigly.core.Rule
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerEngine
import app.phueber.trigly.core.TriggerEvent
import app.phueber.trigly.core.TriggerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The end-to-end half of `docs/variables.md`'s phase 1: a real action, on a real
 * device, showing a value a trigger carried.
 *
 * Every other test of this feature is a JVM test, and they cover the parser, the
 * encodings and the rebuild rule far better than a device test could. What none
 * of them can answer is whether the whole path holds together with a real
 * `NotificationManager` at the end of it: the engine resolving a field, building
 * the action from the resolved config, and the platform posting text that came
 * out of a payload. That is what this checks, and it is deliberately the only
 * thing it checks.
 *
 * The trigger is a fake because the point is the seam, not the trigger. Firing a
 * real notification or a real Bluetooth connect from a test would test the
 * platform's delivery, which other tests already do, and would need access this
 * one does not.
 *
 * Reading the notification back needs no listener access:
 * `getActiveNotifications` returns this app's own notifications, which is
 * exactly the set under test.
 */
@RunWith(AndroidJUnit4::class)
class VariableSubstitutionOnDeviceTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    private var scope: CoroutineScope? = null

    @Before
    fun setUp() {
        grantNotifications()
        manager.cancelAll()
    }

    @After
    fun tearDown() {
        scope?.cancel()
        manager.cancelAll()
    }

    @Test
    fun postsANotificationHoldingAValueTheTriggerCarried() {
        val outcome = runRule(
            payload = mapOf(PAYLOAD_TITLE to "Are we still on for tonight?"),
            body = "Chat said: {{trigger.$PAYLOAD_TITLE}}",
        )

        assertEquals(ActionResult.Success, outcome)
        val posted = awaitNotification()
        assertNotNull("no notification was posted", posted)
        assertEquals(
            "Chat said: Are we still on for tonight?",
            posted?.getString(Notification.EXTRA_TEXT),
        )
    }

    /**
     * The invariant that matters most in the whole feature, checked where it
     * finally reaches a person: an absent value does not quietly become an empty
     * string. A notification reading "Chat said: " would look like the rule
     * worked.
     */
    @Test
    fun refusesToActWhenTheValueIsMissing() {
        val outcome = runRule(
            payload = emptyMap(),
            body = "Chat said: {{trigger.$PAYLOAD_TITLE}}",
        )

        assertTrue("expected a failure, got $outcome", outcome is ActionResult.Failure)
        val reason = (outcome as ActionResult.Failure).reason
        assertTrue("the reason must name the field: $reason", reason.contains("body"))
        assertTrue("the reason must name the variable: $reason", reason.contains(PAYLOAD_TITLE))
        assertNull("nothing may be posted", awaitNotification())
    }

    /** A fallback is how a person says that empty is acceptable here. */
    @Test
    fun usesTheFallbackWhenTheValueIsMissing() {
        val outcome = runRule(
            payload = emptyMap(),
            body = "Chat said: {{trigger.$PAYLOAD_TITLE | nothing}}",
        )

        assertEquals(ActionResult.Success, outcome)
        assertEquals(
            "Chat said: nothing",
            awaitNotification()?.getString(Notification.EXTRA_TEXT),
        )
    }

    /**
     * Runs one rule through the real engine and returns what its one action
     * reported.
     *
     * A registry assembled here rather than the app's own, because this needs a
     * trigger it can fire on demand. The actions are the real ones.
     */
    private fun runRule(payload: Map<String, String>, body: String): ActionResult? {
        val registry = Registry(
            triggerFactories = listOf(FakeTriggerFactory(payload)),
            actionFactories = actionFactories(context),
        )
        val outcomes = Channel<ActionResult>(capacity = Channel.BUFFERED)
        val scope = CoroutineScope(SupervisorJob()).also { this.scope = it }

        val engine = TriggerEngine(
            registry = registry,
            scope = scope,
            onOutcome = { _, _, _, result -> outcomes.trySend(result) },
        )

        engine.startRule(
            Rule(
                id = "variables-on-device",
                name = "variables on device",
                trigger = ComponentSpec(FakeTriggerFactory.TYPE),
                actions = listOf(
                    ComponentSpec(
                        PostNotificationAction.TYPE,
                        mapOf(
                            PostNotificationAction.CONFIG_TITLE to TITLE,
                            PostNotificationAction.CONFIG_BODY to body,
                        ),
                    ),
                ),
            )
        )

        return runBlocking { withTimeoutOrNull(TIMEOUT_MILLIS) { outcomes.receive() } }
    }

    /**
     * The extras of the notification this test's rule posts, or null if none
     * arrived.
     *
     * Matched by title rather than taken as the first one on screen. The engine's
     * own foreground-service notification belongs to this same app, so it appears
     * in this list whenever the service happens to be running, and a test that
     * read the first entry would sometimes assert against it.
     *
     * Polled rather than read once: `notify` returns before the system has
     * finished listing the notification, and a single read is the kind of test
     * that passes on an idle device and fails on a loaded one.
     */
    private fun awaitNotification(): android.os.Bundle? {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            manager.activeNotifications
                .map { it.notification.extras }
                .firstOrNull { it.getString(Notification.EXTRA_TITLE) == TITLE }
                ?.let { return it }
            Thread.sleep(POLL_MILLIS)
        }
        return null
    }

    private fun grantNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
    }

    /** A trigger that fires once, with the payload the test wants to read back. */
    private class FakeTriggerFactory(private val payload: Map<String, String>) : TriggerFactory {
        override val type = TYPE

        override fun create(config: Map<String, String>): Trigger = object : Trigger {
            override fun events(): Flow<TriggerEvent> = flow {
                emit(TriggerEvent(TYPE, System.currentTimeMillis(), payload))
            }
        }

        companion object {
            const val TYPE = "variables_on_device_test"
        }
    }

    private companion object {
        const val TITLE = "Variables on device"
        const val PAYLOAD_TITLE = "title"
        const val TIMEOUT_MILLIS = 5_000L
        const val POLL_MILLIS = 50L
    }
}
