package app.phueber.trigly.actions

import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.ActiveNotification
import app.phueber.trigly.core.NotificationButton
import app.phueber.trigly.core.NotificationController
import app.phueber.trigly.core.SharedPayloadKeys
import app.phueber.trigly.core.TextFilter
import app.phueber.trigly.core.TriggerEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The controller port is what makes these testable without a device: a fake
 * stands in for the listener service, so the interesting part (which
 * notification an action targets, and how it behaves when there isn't one) is
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

        val result = DismissNotificationAction(controller, targetPackage = null)
            .execute(fromNotificationRule)

        assertEquals(ActionResult.Success(), result)
        assertEquals(listOf("dismiss:0|com.example|42|null|10123"), controller.calls)
    }

    /**
     * The whole point of the selector: a rule whose trigger has nothing to do
     * with notifications can still dismiss one. "When I leave the house, clear
     * the shopping-list reminder."
     */
    @Test
    fun `a chosen app dismisses that app's notification on a rule with no notification`() =
        runTest {
            val controller = FakeNotificationController(
                active = listOf(
                    notification("0|com.shopping|7|null|10", "com.shopping", postedAt = 5_000),
                )
            )

            val result = DismissNotificationAction(controller, targetPackage = "com.shopping")
                .execute(fromOtherRule)

            assertEquals(ActionResult.Success(), result)
            assertEquals(listOf("dismiss:0|com.shopping|7|null|10"), controller.calls)
        }

    @Test
    fun `a chosen app takes its newest notification`() = runTest {
        val controller = FakeNotificationController(
            active = listOf(
                notification("0|com.shopping|1|null|10", "com.shopping", postedAt = 1_000),
                notification("0|com.shopping|9|null|10", "com.shopping", postedAt = 9_000),
                notification("0|com.other|3|null|10", "com.other", postedAt = 5_000),
            )
        )

        DismissNotificationAction(controller, targetPackage = "com.shopping")
            .execute(fromOtherRule)

        assertEquals(listOf("dismiss:0|com.shopping|9|null|10"), controller.calls)
    }

    @Test
    fun `a chosen app wins over the notification that fired the rule`() = runTest {
        // Naming an app means that app. Falling back to the trigger's
        // notification would dismiss the wrong one and report success.
        val controller = FakeNotificationController(
            active = listOf(
                notification("0|com.shopping|7|null|10", "com.shopping", postedAt = 5_000),
            )
        )

        DismissNotificationAction(controller, targetPackage = "com.shopping")
            .execute(fromNotificationRule)

        assertEquals(listOf("dismiss:0|com.shopping|7|null|10"), controller.calls)
    }

    @Test
    fun `a chosen app with nothing showing fails rather than dismissing something else`() =
        runTest {
            val controller = FakeNotificationController(
                active = listOf(
                    notification("0|com.other|3|null|10", "com.other", postedAt = 5_000),
                )
            )

            val result = DismissNotificationAction(controller, targetPackage = "com.shopping")
                .execute(fromNotificationRule)

            assertTrue("expected a failure, got $result", result is ActionResult.Failure)
            assertTrue(
                "the reason should name the app: ${(result as ActionResult.Failure).reason}",
                result.reason.contains("com.shopping"),
            )
            assertTrue("must not dismiss anything", controller.calls.isEmpty())
        }

    /** Text only: any app, the newest notification whose text matches. */
    @Test
    fun `a chosen text dismisses the matching notification from any app`() = runTest {
        val controller = FakeNotificationController(
            active = listOf(
                notification("0|com.shopping|1|null|10", "com.shopping", postedAt = 1_000, text = "Milk"),
                notification("0|com.other|2|null|10", "com.other", postedAt = 2_000, text = "Bread"),
            )
        )

        val result = DismissNotificationAction(
            controller = controller,
            targetPackage = null,
            text = TextFilter.of("milk"),
        ).execute(fromOtherRule)

        assertEquals(ActionResult.Success(), result)
        assertEquals(listOf("dismiss:0|com.shopping|1|null|10"), controller.calls)
    }

    @Test
    fun `a chosen text takes the newest notification that matches, not the newest overall`() =
        runTest {
            val controller = FakeNotificationController(
                active = listOf(
                    notification("0|com.a|1|null|10", "com.a", postedAt = 1_000, text = "Milk"),
                    notification("0|com.b|2|null|10", "com.b", postedAt = 9_000, text = "Bread"),
                    notification("0|com.c|3|null|10", "com.c", postedAt = 5_000, text = "Milk and eggs"),
                )
            )

            DismissNotificationAction(
                controller = controller,
                targetPackage = null,
                text = TextFilter.of("milk"),
            ).execute(fromOtherRule)

            assertEquals(listOf("dismiss:0|com.c|3|null|10"), controller.calls)
        }

    @Test
    fun `a chosen text with nothing matching fails, naming the text`() = runTest {
        val controller = FakeNotificationController(
            active = listOf(notification("0|com.a|1|null|10", "com.a", postedAt = 1_000, text = "Bread"))
        )

        val result = DismissNotificationAction(
            controller = controller,
            targetPackage = null,
            text = TextFilter.of("milk"),
        ).execute(fromOtherRule)

        assertTrue(result is ActionResult.Failure)
        val reason = (result as ActionResult.Failure).reason
        assertTrue("should name the text: $reason", reason.contains("milk"))
        assertTrue("must not name an app: $reason", !reason.contains("from '"))
        assertTrue("must not dismiss anything", controller.calls.isEmpty())
    }

    /**
     * The case the field help promises and a reader would assume the other way:
     * an app and a text filled in together must narrow the choice, not widen it.
     * Each of the three notifications below matches exactly one of the two
     * conditions, and none of them is the target. Only the fourth, which
     * matches both, is.
     */
    @Test
    fun `an app and a text together narrow the choice rather than widening it`() = runTest {
        val controller = FakeNotificationController(
            active = listOf(
                // Matches the app only, and is the newest of the app-only match
                // and the both-match below. If app alone decided, this would win.
                notification(
                    "0|com.shopping|1|null|10", "com.shopping", postedAt = 9_000, text = "Eggs",
                ),
                // Matches the text only, and is newer than the both-match below.
                // If text alone decided, this would win.
                notification(
                    "0|com.other|2|null|10", "com.other", postedAt = 8_000, text = "Milk",
                ),
                // Matches neither.
                notification(
                    "0|com.other|3|null|10", "com.other", postedAt = 7_000, text = "Eggs",
                ),
                // Matches both, and is the oldest of the four. It must still win,
                // because it is the only one that satisfies both filters at once.
                notification(
                    "0|com.shopping|4|null|10", "com.shopping", postedAt = 1_000, text = "Milk",
                ),
            )
        )

        val result = DismissNotificationAction(
            controller = controller,
            targetPackage = "com.shopping",
            text = TextFilter.of("milk"),
        ).execute(fromOtherRule)

        assertEquals(ActionResult.Success(), result)
        assertEquals(listOf("dismiss:0|com.shopping|4|null|10"), controller.calls)
    }

    @Test
    fun `an app and a text together fail when no notification satisfies both`() = runTest {
        val controller = FakeNotificationController(
            active = listOf(
                notification("0|com.shopping|1|null|10", "com.shopping", postedAt = 1_000, text = "Eggs"),
                notification("0|com.other|2|null|10", "com.other", postedAt = 2_000, text = "Milk"),
            )
        )

        val result = DismissNotificationAction(
            controller = controller,
            targetPackage = "com.shopping",
            text = TextFilter.of("milk"),
        ).execute(fromOtherRule)

        assertTrue(result is ActionResult.Failure)
        val reason = (result as ActionResult.Failure).reason
        assertTrue("should name the app: $reason", reason.contains("com.shopping"))
        assertTrue("should name the text: $reason", reason.contains("milk"))
        assertTrue("must not dismiss anything", controller.calls.isEmpty())
    }

    /**
     * The trigger's notification does not need to be in the active list to be
     * dismissed. The payload names it exactly. Looking it up would only add a
     * way to fail.
     */
    @Test
    fun `the triggering notification is dismissed without consulting the active list`() = runTest {
        val controller = FakeNotificationController(active = emptyList())

        val result = DismissNotificationAction(controller, targetPackage = null)
            .execute(fromNotificationRule)

        assertEquals(ActionResult.Success(), result)
        assertEquals(listOf("dismiss:0|com.example|42|null|10123"), controller.calls)
    }

    @Test
    fun `a key saved by an old rule is still honoured`() = runTest {
        // The raw-key text box is gone, but a rule saved when it existed must
        // keep doing what it did rather than being retargeted.
        val controller = FakeNotificationController()

        DismissNotificationAction(controller, targetPackage = null, legacyKey = "chosen-key")
            .execute(fromNotificationRule)

        assertEquals(listOf("dismiss:chosen-key"), controller.calls)
    }

    @Test
    fun `with no app and no notification it fails and explains, rather than doing nothing`() =
        runTest {
            val controller = FakeNotificationController()

            val result = DismissNotificationAction(controller, targetPackage = null)
                .execute(fromOtherRule)

            assertTrue(result is ActionResult.Failure)
            assertTrue((result as ActionResult.Failure).reason.contains("Choose an app"))
            assertTrue("must not call the controller", controller.calls.isEmpty())
        }

    @Test
    fun `a controller failure is reported as the action's failure`() = runTest {
        val controller = FakeNotificationController(
            result = ActionResult.Failure("notification access is not granted")
        )

        val result = DismissNotificationAction(controller, targetPackage = null)
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

        assertEquals(ActionResult.Success(), result)
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

    /** A notification with no buttons, for the dismiss selector's "which one" tests. */
    private fun notification(
        key: String,
        packageName: String,
        postedAt: Long,
        title: String = "Reminder",
        text: String = "Milk",
    ) = ActiveNotification(
        key = key,
        packageName = packageName,
        title = title,
        text = text,
        postedAtMillis = postedAt,
        buttons = emptyList(),
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
    private val result: ActionResult = ActionResult.Success(),
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
