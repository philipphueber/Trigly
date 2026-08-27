package app.phueber.trigly.actions

import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.ActiveNotification
import app.phueber.trigly.core.ComponentSpec
import app.phueber.trigly.core.NotificationButton
import app.phueber.trigly.core.NotificationController
import app.phueber.trigly.core.Rule
import app.phueber.trigly.core.SharedPayloadKeys
import app.phueber.trigly.core.TriggerEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeping a notification button and pressing it later, as far as the JVM can
 * see: which button gets kept, under what name, and what each refusal says.
 *
 * The part that cannot be tested here is the part that matters most, and it is
 * tested where it can be. A `PendingIntent` has no JVM implementation, so
 * whether a kept token still fires after its notification is dismissed is
 * answered by `CapturedButtonOutlivesDismissalTest` and by
 * `CapturedButtonOnDeviceTest`, both instrumented.
 */
class CapturedButtonActionsTest {

    private val event = TriggerEvent(
        triggerType = "notification_posted",
        firedAtMillis = 1_000,
        payload = mapOf(SharedPayloadKeys.NOTIFICATION_KEY to "key-1"),
    )

    private fun notification(
        key: String = "key-1",
        buttons: List<NotificationButton> = listOf(
            NotificationButton(index = 0, label = "Turn off for now"),
            NotificationButton(index = 1, label = "Pause for 30 min"),
        ),
    ) = ActiveNotification(
        key = key,
        packageName = "com.google.android.apps.wellbeing",
        title = "Bedtime mode is on",
        text = "",
        postedAtMillis = 10,
        buttons = buttons,
    )

    // --- keeping -----------------------------------------------------------------------

    @Test
    fun `the named button is kept, under the name given`() = runTest {
        val controller = FakeCaptureController(active = listOf(notification()))

        val result = CaptureNotificationButtonAction(
            controller = controller,
            name = "bedtime_off",
            buttonLabel = "Turn off for now",
            semanticAction = null,
            targetPackage = null,
        ).execute(event)

        assertTrue(result is ActionResult.Success)
        assertEquals(listOf("capture:key-1:0:bedtime_off"), controller.calls)
    }

    /**
     * The label decides, not the position. An app that reorders its buttons must
     * not silently change which one a rule keeps, which is the same guarantee
     * `chooseButton` gives the pressing action.
     */
    @Test
    fun `the second button is kept when its label is the one asked for`() = runTest {
        val controller = FakeCaptureController(active = listOf(notification()))

        CaptureNotificationButtonAction(
            controller = controller,
            name = "snooze",
            buttonLabel = "Pause for 30 min",
            semanticAction = null,
            targetPackage = null,
        ).execute(event)

        assertEquals(listOf("capture:key-1:1:snooze"), controller.calls)
    }

    /**
     * The name it kept the button under, reported so the pressing rule can read
     * it as `{{action.captured}}` instead of repeating a literal that could
     * drift from this one.
     */
    @Test
    fun `keeping reports the name as an output`() = runTest {
        val controller = FakeCaptureController(active = listOf(notification()))

        val result = CaptureNotificationButtonAction(
            controller = controller,
            name = "bedtime_off",
            buttonLabel = "Turn off for now",
            semanticAction = null,
            targetPackage = null,
        ).execute(event)

        assertEquals(
            mapOf(CaptureNotificationButtonAction.OUTPUT_NAME to "bedtime_off"),
            (result as ActionResult.Success).outputs,
        )
    }

    @Test
    fun `a name that cannot be written back is refused`() = runTest {
        val controller = FakeCaptureController(active = listOf(notification()))

        val result = CaptureNotificationButtonAction(
            controller = controller,
            name = "bedtime off",
            buttonLabel = "Turn off for now",
            semanticAction = null,
            targetPackage = null,
        ).execute(event)

        assertTrue(result is ActionResult.Failure)
        assertTrue("nothing should have been kept", controller.calls.isEmpty())
    }

    /**
     * Naming no button keeps nothing, rather than keeping whichever button
     * happens to be first. `chooseButton` refuses to guess for the pressing
     * action, and the reason is stronger here: a wrongly kept button is only
     * discovered later, when the wrong thing is pressed.
     */
    @Test
    fun `naming no button keeps nothing rather than guessing`() = runTest {
        val controller = FakeCaptureController(active = listOf(notification()))

        val result = CaptureNotificationButtonAction(
            controller = controller,
            name = "bedtime_off",
            buttonLabel = null,
            semanticAction = null,
            targetPackage = null,
        ).execute(event)

        assertTrue(result is ActionResult.Failure)
        assertTrue("nothing should have been kept", controller.calls.isEmpty())
    }

    /**
     * The one place this action deliberately differs from `notification_button`.
     * That action falls back to pressing on screen when the system exposes no
     * button; there is no fallback here, because the screen cannot hand over a
     * token to keep for later.
     */
    @Test
    fun `a notification that exposes no buttons says there is nothing to keep`() = runTest {
        val controller = FakeCaptureController(
            active = listOf(notification(buttons = emptyList())),
        )

        val result = CaptureNotificationButtonAction(
            controller = controller,
            name = "bedtime_off",
            buttonLabel = "Turn off for now",
            semanticAction = null,
            targetPackage = null,
        ).execute(event)

        assertTrue(result is ActionResult.Failure)
        val reason = (result as ActionResult.Failure).reason
        assertTrue("was: $reason", reason.contains("nothing to keep"))
        assertTrue("was: $reason", reason.contains("draws them itself"))
    }

    @Test
    fun `a button that does not match names what is there instead`() = runTest {
        val controller = FakeCaptureController(active = listOf(notification()))

        val result = CaptureNotificationButtonAction(
            controller = controller,
            name = "bedtime_off",
            buttonLabel = "Snooze",
            semanticAction = null,
            targetPackage = null,
        ).execute(event)

        assertTrue(result is ActionResult.Failure)
        val reason = (result as ActionResult.Failure).reason
        assertTrue("was: $reason", reason.contains("Turn off for now"))
        assertTrue("nothing should have been kept", controller.calls.isEmpty())
    }

    @Test
    fun `a reply box is refused rather than kept`() = runTest {
        val controller = FakeCaptureController(
            active = listOf(
                notification(
                    buttons = listOf(
                        NotificationButton(index = 0, label = "Reply", takesText = true),
                    ),
                ),
            ),
        )

        val result = CaptureNotificationButtonAction(
            controller = controller,
            name = "reply",
            buttonLabel = "Reply",
            semanticAction = null,
            targetPackage = null,
        ).execute(event)

        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).reason.contains("reply box"))
    }

    @Test
    fun `no notification to act on is reported without keeping anything`() = runTest {
        val controller = FakeCaptureController(active = emptyList())

        val result = CaptureNotificationButtonAction(
            controller = controller,
            name = "bedtime_off",
            buttonLabel = "Turn off for now",
            semanticAction = null,
            targetPackage = null,
        ).execute(event)

        assertTrue(result is ActionResult.Failure)
        assertTrue(controller.calls.isEmpty())
    }

    // --- pressing what was kept -------------------------------------------------------

    @Test
    fun `pressing asks for the kept name and nothing else`() = runTest {
        val controller = FakeCaptureController()

        val result = PressCapturedButtonAction(controller, name = "bedtime_off").execute(event)

        assertTrue(result is ActionResult.Success)
        assertEquals(listOf("press:bedtime_off"), controller.calls)
    }

    /**
     * The name arrives from config, and config can hold a reference the engine
     * substituted, so surrounding space is the ordinary case rather than a typo.
     * Normalised on the way in so it matches the name the keeping action stored.
     */
    @Test
    fun `a kept name is normalised before it is looked up`() = runTest {
        val controller = FakeCaptureController()

        PressCapturedButtonAction(controller, name = "  bedtime_off  ").execute(event)

        assertEquals(listOf("press:bedtime_off"), controller.calls)
    }

    @Test
    fun `pressing passes the controller's own failure through`() = runTest {
        val controller = FakeCaptureController(
            result = ActionResult.Failure("Nothing is captured under 'bedtime_off'."),
        )

        val result = PressCapturedButtonAction(controller, name = "bedtime_off").execute(event)

        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).reason.contains("Nothing is captured"))
    }

    // --- The names the editor offers ---------------------------------------------------

    @Test
    fun `a rule that keeps a button declares its name`() {
        val rules = listOf(ruleKeeping("bedtime_off", ruleName = "Evening"))

        assertEquals(
            listOf(DeclaredKeptButton("bedtime_off", "Evening")),
            declaredKeptButtons(rules),
        )
    }

    @Test
    fun `a name is reported once, with the first rule that keeps it`() {
        val rules = listOf(
            ruleKeeping("bedtime_off", ruleName = "Evening"),
            ruleKeeping("bedtime_off", ruleName = "Weekend"),
        )

        assertEquals(
            listOf(DeclaredKeptButton("bedtime_off", "Evening")),
            declaredKeptButtons(rules),
        )
    }

    @Test
    fun `an action that is not keeping a button declares nothing`() {
        val rules = listOf(
            Rule(
                id = "r1",
                name = "Something else",
                trigger = ComponentSpec("screen_state"),
                actions = listOf(ComponentSpec("toast", mapOf("text" to "hi"))),
            ),
        )

        assertEquals(emptyList<DeclaredKeptButton>(), declaredKeptButtons(rules))
    }

    /**
     * A reference is an instruction to work a name out at run time, not a name.
     * Offering it in the picker would put a reference to one rule's outputs into
     * a rule that has none of them.
     */
    @Test
    fun `a name that is a variable reference is not offered`() {
        val rules = listOf(ruleKeeping("{{action.captured}}", ruleName = "Evening"))

        assertEquals(emptyList<DeclaredKeptButton>(), declaredKeptButtons(rules))
    }

    @Test
    fun `a blank name is not offered`() {
        val rules = listOf(ruleKeeping("   ", ruleName = "Evening"))

        assertEquals(emptyList<DeclaredKeptButton>(), declaredKeptButtons(rules))
    }

    @Test
    fun `a name is offered trimmed, the way the action stores it`() {
        val rules = listOf(ruleKeeping("  bedtime_off  ", ruleName = "Evening"))

        assertEquals(listOf("bedtime_off"), declaredKeptButtons(rules).map { it.name })
    }

    private fun ruleKeeping(name: String, ruleName: String) = Rule(
        id = "rule-$ruleName",
        name = ruleName,
        trigger = ComponentSpec("notification_posted"),
        actions = listOf(
            ComponentSpec(
                CaptureNotificationButtonAction.TYPE,
                mapOf(
                    CaptureNotificationButtonAction.CONFIG_NAME to name,
                    CaptureNotificationButtonAction.CONFIG_BUTTON to "Turn off for now",
                ),
            ),
        ),
    )
}

private class FakeCaptureController(
    private val result: ActionResult = ActionResult.Success(),
    private val active: List<ActiveNotification> = emptyList(),
) : NotificationController {

    val calls = mutableListOf<String>()

    override val isConnected: Boolean = true

    override fun activeNotifications(): List<ActiveNotification> = active

    override fun dismiss(key: String): ActionResult = result

    override fun triggerActionButton(key: String, actionIndex: Int): ActionResult = result

    override fun captureActionButton(key: String, actionIndex: Int, `as`: String): ActionResult {
        calls += "capture:$key:$actionIndex:${`as`}"
        return result
    }

    override fun pressCaptured(name: String): ActionResult {
        calls += "press:$name"
        return result
    }
}
