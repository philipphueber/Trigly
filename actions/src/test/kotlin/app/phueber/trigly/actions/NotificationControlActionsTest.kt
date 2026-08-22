package app.phueber.trigly.actions

import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.NotificationController
import app.phueber.trigly.core.SharedPayloadKeys
import app.phueber.trigly.core.TriggerEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The controller port is what makes these testable without a device: a fake
 * stands in for the listener service, so the interesting part — which
 * notification an action targets, and how it behaves when there isn't one — is
 * checked on the JVM.
 */
class NotificationControlActionsTest {

    private val fromNotificationRule = TriggerEvent(
        triggerType = "notification_posted",
        firedAtMillis = 1_000,
        payload = mapOf(SharedPayloadKeys.NOTIFICATION_KEY to "0|com.example|42|null|10123"),
    )

    private val fromOtherRule = TriggerEvent(
        triggerType = "battery_level",
        firedAtMillis = 1_000,
        payload = mapOf("level" to "20"),
    )

    @Test
    fun `dismiss targets the notification that fired the rule`() = runTest {
        val controller = FakeNotificationController()

        val result = DismissNotificationAction(controller, key = null)
            .execute(fromNotificationRule)

        assertEquals(ActionResult.Success, result)
        assertEquals(listOf("dismiss:0|com.example|42|null|10123"), controller.calls)
    }

    @Test
    fun `an explicitly configured key wins over the payload`() = runTest {
        val controller = FakeNotificationController()

        DismissNotificationAction(controller, key = "chosen-key").execute(fromNotificationRule)

        assertEquals(listOf("dismiss:chosen-key"), controller.calls)
    }

    @Test
    fun `with no key anywhere it fails and explains, rather than doing nothing`() = runTest {
        val controller = FakeNotificationController()

        val result = DismissNotificationAction(controller, key = null).execute(fromOtherRule)

        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).reason.contains("notification key"))
        assertTrue("must not call the controller", controller.calls.isEmpty())
    }

    @Test
    fun `a controller failure is reported as the action's failure`() = runTest {
        val controller = FakeNotificationController(
            result = ActionResult.Failure("notification access is not granted")
        )

        val result = DismissNotificationAction(controller, key = null)
            .execute(fromNotificationRule)

        assertEquals("notification access is not granted", (result as ActionResult.Failure).reason)
    }

    @Test
    fun `the button action passes the index through`() = runTest {
        val controller = FakeNotificationController()

        TriggerNotificationButtonAction(controller, key = null, buttonIndex = 2)
            .execute(fromNotificationRule)

        assertEquals(listOf("button:0|com.example|42|null|10123:2"), controller.calls)
    }

    @Test
    fun `the button action needs a key too`() = runTest {
        val controller = FakeNotificationController()

        val result = TriggerNotificationButtonAction(controller, key = null, buttonIndex = 0)
            .execute(fromOtherRule)

        assertTrue(result is ActionResult.Failure)
        assertTrue(controller.calls.isEmpty())
    }

    @Test
    fun `button index defaults to the first button`() {
        val action = TriggerNotificationButtonActionFactory(FakeNotificationController())
            .create(emptyMap())

        assertTrue(action is TriggerNotificationButtonAction)
    }

    @Test
    fun `a negative button index is rejected at construction`() {
        val factory = TriggerNotificationButtonActionFactory(FakeNotificationController())

        assertThrows(IllegalArgumentException::class.java) {
            factory.create(mapOf(TriggerNotificationButtonAction.CONFIG_BUTTON_INDEX to "-1"))
        }
    }

    @Test
    fun `a non-numeric button index is rejected at construction`() {
        val factory = TriggerNotificationButtonActionFactory(FakeNotificationController())

        assertThrows(IllegalStateException::class.java) {
            factory.create(mapOf(TriggerNotificationButtonAction.CONFIG_BUTTON_INDEX to "second"))
        }
    }
}

class DndModeTest {

    @Test
    fun `off means the framework's allow-everything filter`() {
        assertEquals(DndMode.OFF, DndMode.parse("off"))
        assertEquals(
            android.app.NotificationManager.INTERRUPTION_FILTER_ALL,
            DndMode.OFF.filter,
        )
    }

    @Test
    fun `each mode parses case insensitively`() {
        assertEquals(DndMode.PRIORITY, DndMode.parse("PRIORITY"))
        assertEquals(DndMode.ALARMS, DndMode.parse("Alarms"))
        assertEquals(DndMode.SILENCE, DndMode.parse("silence"))
    }

    @Test
    fun `an unknown mode lists the valid ones`() {
        val error = assertThrows(IllegalStateException::class.java) { DndMode.parse("quiet") }
        assertTrue(error.message!!.contains("silence"))
    }
}

private class FakeNotificationController(
    private val result: ActionResult = ActionResult.Success,
) : NotificationController {

    val calls = mutableListOf<String>()

    override val isConnected: Boolean = true

    override fun dismiss(key: String): ActionResult {
        calls += "dismiss:$key"
        return result
    }

    override fun triggerActionButton(key: String, actionIndex: Int): ActionResult {
        calls += "button:$key:$actionIndex"
        return result
    }
}
