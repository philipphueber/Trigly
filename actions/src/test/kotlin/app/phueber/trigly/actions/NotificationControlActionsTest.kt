package app.phueber.trigly.actions

import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.ActiveNotification
import app.phueber.trigly.core.NotificationButton
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
    fun `the button action presses the button it captured`() = runTest {
        val controller = FakeNotificationController(
            active = listOf(
                chatNotification(buttons = listOf(button(0, "Reply", takesText = true), button(1, "Snooze"))),
            )
        )

        TriggerNotificationButtonAction(
            controller = controller,
            buttonLabel = "Snooze",
            semanticAction = null,
            targetPackage = null,
            legacyIndex = null,
        ).execute(fromNotificationRule)

        assertEquals(listOf("button:0|com.example|42|null|10123:1"), controller.calls)
    }

    /**
     * The case that justifies a package at all: the rule is fired by something
     * that is not a notification, and acts on one anyway.
     */
    @Test
    fun `a configured package lets a non-notification trigger act on a notification`() = runTest {
        val controller = FakeNotificationController(
            active = listOf(
                ActiveNotification(
                    key = "media-1",
                    packageName = "com.example.music",
                    title = "A track",
                    text = null,
                    postedAtMillis = 5,
                    buttons = listOf(button(0, "Play")),
                )
            )
        )

        val result = TriggerNotificationButtonAction(
            controller = controller,
            buttonLabel = "Play",
            semanticAction = null,
            targetPackage = "com.example.music",
            legacyIndex = null,
        ).execute(fromOtherRule)

        assertEquals(ActionResult.Success, result)
        assertEquals(listOf("button:media-1:0"), controller.calls)
    }

    @Test
    fun `a reply button is refused rather than pressed to no effect`() = runTest {
        val controller = FakeNotificationController(
            active = listOf(chatNotification(buttons = listOf(button(0, "Reply", takesText = true))))
        )

        val result = TriggerNotificationButtonAction(
            controller = controller,
            buttonLabel = "Reply",
            semanticAction = null,
            targetPackage = null,
            legacyIndex = null,
        ).execute(fromNotificationRule)

        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).reason.contains("reply box"))
        assertTrue("must not fire the intent", controller.calls.isEmpty())
    }

    @Test
    fun `a button that is gone is reported, with what is actually there`() = runTest {
        val controller = FakeNotificationController(
            active = listOf(chatNotification(buttons = listOf(button(0, "Archive"))))
        )

        val result = TriggerNotificationButtonAction(
            controller = controller,
            buttonLabel = "Snooze",
            semanticAction = null,
            targetPackage = null,
            legacyIndex = null,
        ).execute(fromNotificationRule)

        val reason = (result as ActionResult.Failure).reason
        assertTrue("should name what it wanted: $reason", reason.contains("Snooze"))
        assertTrue("should list what exists: $reason", reason.contains("Archive"))
        assertTrue("must not press something else", controller.calls.isEmpty())
    }

    @Test
    fun `with nothing to act on it explains rather than doing nothing`() = runTest {
        val controller = FakeNotificationController(active = emptyList())

        val result = TriggerNotificationButtonAction(
            controller = controller,
            buttonLabel = "Snooze",
            semanticAction = null,
            targetPackage = null,
            legacyIndex = null,
        ).execute(fromOtherRule)

        assertTrue(result is ActionResult.Failure)
        assertTrue(controller.calls.isEmpty())
    }

    /** A rule saved when a position was the only option must keep working. */
    @Test
    fun `a legacy index still resolves`() = runTest {
        val controller = FakeNotificationController(
            active = listOf(chatNotification(buttons = listOf(button(0, "A"), button(1, "B"))))
        )

        TriggerNotificationButtonAction(
            controller = controller,
            buttonLabel = null,
            semanticAction = null,
            targetPackage = null,
            legacyIndex = 1,
        ).execute(fromNotificationRule)

        assertEquals(listOf("button:0|com.example|42|null|10123:1"), controller.calls)
    }

    @Test
    fun `a non-numeric legacy index is rejected at construction`() {
        val factory = TriggerNotificationButtonActionFactory(FakeNotificationController())

        assertThrows(IllegalStateException::class.java) {
            factory.create(mapOf(TriggerNotificationButtonAction.CONFIG_BUTTON_INDEX to "second"))
        }
    }

    @Test
    fun `a negative legacy index is rejected at construction`() {
        val factory = TriggerNotificationButtonActionFactory(FakeNotificationController())

        assertThrows(IllegalArgumentException::class.java) {
            factory.create(mapOf(TriggerNotificationButtonAction.CONFIG_BUTTON_INDEX to "-1"))
        }
    }

    private fun button(index: Int, label: String, takesText: Boolean = false) =
        NotificationButton(index = index, label = label, semanticAction = null, takesText = takesText)

    private fun chatNotification(buttons: List<NotificationButton>) = ActiveNotification(
        key = "0|com.example|42|null|10123",
        packageName = "com.example",
        title = "A message",
        text = "Hello",
        postedAtMillis = 1,
        buttons = buttons,
    )
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
    private val active: List<ActiveNotification> = emptyList(),
) : NotificationController {

    val calls = mutableListOf<String>()

    override val isConnected: Boolean = true

    override fun activeNotifications(): List<ActiveNotification> = active

    override fun dismiss(key: String): ActionResult {
        calls += "dismiss:$key"
        return result
    }

    override fun triggerActionButton(key: String, actionIndex: Int): ActionResult {
        calls += "button:$key:$actionIndex"
        return result
    }
}
