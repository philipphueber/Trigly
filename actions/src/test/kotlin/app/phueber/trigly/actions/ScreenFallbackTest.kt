package app.phueber.trigly.actions

import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.ActiveNotification
import app.phueber.trigly.core.NotificationButton
import app.phueber.trigly.core.NotificationController
import app.phueber.trigly.core.SharedPayloadKeys
import app.phueber.trigly.core.TriggerEvent
import app.phueber.trigly.core.UiController
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When `notification_button` gives up, and when it reaches for the screen.
 *
 * The case that motivates all of it: an app that draws its own notification
 * buttons with `RemoteViews`. They are visible and tappable, and
 * `Notification.actions` is **empty** — so the notification listener has no
 * `PendingIntent` to send and cannot press them at any level of cleverness. The
 * screen is the only route left, and it is opt-in because it opens the shade in
 * front of the user.
 *
 * What these pin is the *decision*: the ordinary route stays first, the fallback
 * only runs when asked, and a refusal still says what the notification API found
 * so nobody is sent to the wrong setting.
 */
class ScreenFallbackTest {

    private val fromNotification = TriggerEvent(
        triggerType = "notification_posted",
        firedAtMillis = 1_000,
        payload = mapOf(SharedPayloadKeys.NOTIFICATION_KEY to "0|de.blitzer|9|null|10"),
    )

    private fun customLayoutNotification() = ActiveNotification(
        key = "0|de.blitzer|9|null|10",
        packageName = "de.blitzer",
        title = "Blitzer",
        text = "Radar ahead",
        postedAtMillis = 1_000,
        // The whole point: on screen there are two buttons, and the system
        // exposes none of them.
        buttons = emptyList(),
    )

    @Test
    fun `a notification exposing no buttons refuses by default, and explains`() = runTest {
        val ui = FakeUiController()
        val action = TriggerNotificationButtonAction(
            controller = FakeController(active = listOf(customLayoutNotification())),
            buttonLabel = "BEENDEN",
            semanticAction = null,
            targetPackage = null,
            legacyIndex = null,
            ui = ui,
            useScreenFallback = false,
        )

        val result = action.execute(fromNotification)

        assertTrue("expected a failure, got $result", result is ActionResult.Failure)
        val reason = (result as ActionResult.Failure).reason
        assertTrue("should say the app draws them itself: $reason", reason.contains("draws them"))
        assertTrue("should point at the setting: $reason", reason.contains("use the screen"))
        assertTrue("must not touch the screen", ui.presses.isEmpty())
    }

    @Test
    fun `with the fallback on it presses through the shade`() = runTest {
        val ui = FakeUiController()
        val action = TriggerNotificationButtonAction(
            controller = FakeController(active = listOf(customLayoutNotification())),
            buttonLabel = "BEENDEN",
            semanticAction = null,
            targetPackage = "de.blitzer",
            legacyIndex = null,
            ui = ui,
            useScreenFallback = true,
        )

        val result = action.execute(fromNotification)

        assertEquals(ActionResult.Success(), result)
        assertEquals(listOf("de.blitzer/BEENDEN"), ui.presses)
    }

    @Test
    fun `the fallback needs the button's name, and says so when there is none`() = runTest {
        // A rule saved when only a position could be stored has nothing to look
        // for on screen: an index describes a slot in an actions array that this
        // notification does not have.
        val ui = FakeUiController()
        val action = TriggerNotificationButtonAction(
            controller = FakeController(active = listOf(customLayoutNotification())),
            buttonLabel = null,
            semanticAction = null,
            targetPackage = null,
            legacyIndex = 1,
            ui = ui,
            useScreenFallback = true,
        )

        val result = action.execute(fromNotification)

        assertTrue(result is ActionResult.Failure)
        assertTrue(
            "should say the name is missing: ${(result as ActionResult.Failure).reason}",
            result.reason.contains("needs the button's name"),
        )
        assertTrue("must not guess a label", ui.presses.isEmpty())
    }

    @Test
    fun `a failure on screen keeps what the notification API said`() = runTest {
        // Two different problems — "no such button" and "accessibility is off" —
        // and reporting only the second sends someone to the wrong setting.
        val ui = FakeUiController(
            result = ActionResult.Failure("accessibility access is not granted")
        )
        val action = TriggerNotificationButtonAction(
            controller = FakeController(active = listOf(customLayoutNotification())),
            buttonLabel = "BEENDEN",
            semanticAction = null,
            targetPackage = null,
            legacyIndex = null,
            ui = ui,
            useScreenFallback = true,
        )

        val result = action.execute(fromNotification)

        val reason = (result as ActionResult.Failure).reason
        assertTrue("keeps the API's finding: $reason", reason.contains("draws them"))
        assertTrue("adds the screen's finding: $reason", reason.contains("accessibility access"))
    }

    @Test
    fun `an exposed button is still pressed the ordinary way, fallback or not`() = runTest {
        // The screen route must never become the normal one: it is slower, it
        // shows the shade, and it depends on the phone's layout.
        val ui = FakeUiController()
        val controller = FakeController(
            active = listOf(
                customLayoutNotification().copy(
                    buttons = listOf(
                        NotificationButton(
                            index = 0,
                            label = "BEENDEN",
                            semanticAction = null,
                            takesText = false,
                        )
                    )
                )
            )
        )
        val action = TriggerNotificationButtonAction(
            controller = controller,
            buttonLabel = "BEENDEN",
            semanticAction = null,
            targetPackage = null,
            legacyIndex = null,
            ui = ui,
            useScreenFallback = true,
        )

        val result = action.execute(fromNotification)

        assertEquals(ActionResult.Success(), result)
        assertEquals(listOf("button:0|de.blitzer|9|null|10:0"), controller.calls)
        assertTrue("the screen must not be used when the API can do it", ui.presses.isEmpty())
    }

    @Test
    fun `a wrongly named button falls through to the screen when allowed`() = runTest {
        // The notification has buttons, just not this one — which is also what a
        // custom layout looks like when it exposes *some* actions.
        val ui = FakeUiController()
        val action = TriggerNotificationButtonAction(
            controller = FakeController(
                active = listOf(
                    customLayoutNotification().copy(
                        buttons = listOf(
                            NotificationButton(
                                index = 0,
                                label = "MELDEN",
                                semanticAction = null,
                                takesText = false,
                            )
                        )
                    )
                )
            ),
            buttonLabel = "BEENDEN",
            semanticAction = null,
            targetPackage = null,
            legacyIndex = null,
            ui = ui,
            useScreenFallback = true,
        )

        val result = action.execute(fromNotification)

        assertEquals(ActionResult.Success(), result)
        assertEquals(listOf("null/BEENDEN"), ui.presses)
    }
}

private class FakeController(
    private val active: List<ActiveNotification>,
) : NotificationController {

    val calls = mutableListOf<String>()

    override val isConnected: Boolean = true
    override fun activeNotifications(): List<ActiveNotification> = active

    override fun dismiss(key: String): ActionResult {
        calls += "dismiss:$key"
        return ActionResult.Success()
    }

    override fun triggerActionButton(key: String, actionIndex: Int): ActionResult {
        calls += "button:$key:$actionIndex"
        return ActionResult.Success()
    }
}

private class FakeUiController(
    private val result: ActionResult = ActionResult.Success(),
) : UiController {

    val presses = mutableListOf<String>()

    override val isConnected: Boolean = true

    override suspend fun pressNotificationButton(
        packageName: String?,
        label: String,
    ): ActionResult {
        presses += "$packageName/$label"
        return result
    }
}
