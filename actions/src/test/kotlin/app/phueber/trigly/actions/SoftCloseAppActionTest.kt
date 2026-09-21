package app.phueber.trigly.actions

import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.ForegroundAppController
import app.phueber.trigly.core.TriggerEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What `soft_close_app` does in each of its four branches.
 *
 * The two that matter most are the two that do nothing. An action that quietly
 * does nothing because a service is off is the failure this project cares most
 * about, so "the service is off" must be a failure that names the grant. And
 * "another app is in front" must be a success that says so, because it is the
 * ordinary outcome of a rule that is working correctly, and a failure there
 * would make every such run read as broken.
 *
 * A fake port is the whole of the test rig. The real port reads a bound
 * accessibility service, which no JVM test can start; the on-device test covers
 * what the fake cannot see.
 */
class SoftCloseAppActionTest {

    private val event = TriggerEvent(triggerType = "test", firedAtMillis = 1_000)

    @Test
    fun `the app in front is sent back, and the result says so`() = runTest {
        val controller = FakeForegroundApp(front = "com.example.game")
        val action = SoftCloseAppAction(controller, packageName = "com.example.game")

        val result = action.execute(event)

        assertEquals(
            ActionResult.Success(
                outputs = mapOf(
                    SoftCloseAppAction.OUTPUT_SENT_BACK to SoftCloseAppAction.YES,
                    SoftCloseAppAction.OUTPUT_FOREGROUND to "com.example.game",
                ),
            ),
            result,
        )
        assertEquals(1, controller.homePresses)
    }

    /**
     * The branch the owner settled. Another app in front means the screen is
     * not touched at all, and the result reports the fact rather than failing.
     */
    @Test
    fun `another app in front is a success that touched nothing`() = runTest {
        val controller = FakeForegroundApp(front = "com.example.mail")
        val action = SoftCloseAppAction(controller, packageName = "com.example.game")

        val result = action.execute(event)

        assertEquals(
            ActionResult.Success(
                outputs = mapOf(
                    SoftCloseAppAction.OUTPUT_SENT_BACK to SoftCloseAppAction.NO,
                    SoftCloseAppAction.OUTPUT_FOREGROUND to "com.example.mail",
                ),
            ),
            result,
        )
        assertEquals("the screen must not be touched", 0, controller.homePresses)
    }

    /**
     * A locked phone reaches this branch, because the lock screen is the window
     * in front. The action reports the lock screen's own package, which is the
     * honest answer to "what did you see".
     */
    @Test
    fun `a locked phone reports the lock screen as the app in front`() = runTest {
        val controller = FakeForegroundApp(front = "com.android.systemui")
        val action = SoftCloseAppAction(controller, packageName = "com.example.game")

        val result = action.execute(event)

        assertTrue(result is ActionResult.Success)
        assertEquals(
            SoftCloseAppAction.NO,
            (result as ActionResult.Success).outputs[SoftCloseAppAction.OUTPUT_SENT_BACK],
        )
        assertEquals(0, controller.homePresses)
    }

    @Test
    fun `a service that is not bound fails and names the grant`() = runTest {
        val controller = FakeForegroundApp(front = "com.example.game", connected = false)
        val action = SoftCloseAppAction(controller, packageName = "com.example.game")

        val result = action.execute(event)

        assertTrue("expected a failure, got $result", result is ActionResult.Failure)
        val reason = (result as ActionResult.Failure).reason
        assertTrue("should name the grant: $reason", reason.contains("Accessibility"))
        assertTrue("the screen must not be touched", controller.homePresses == 0)
        assertTrue("it must not even look", !controller.lookedAtTheScreen)
    }

    /**
     * Separate from the branch above, and deliberately so. "The service is off"
     * is fixed in settings; "I cannot read the screen" usually means the screen
     * is off and fixes itself. One message for both would send somebody to the
     * wrong place.
     */
    @Test
    fun `a screen it cannot read fails, and says that instead`() = runTest {
        val controller = FakeForegroundApp(front = null)
        val action = SoftCloseAppAction(controller, packageName = "com.example.game")

        val result = action.execute(event)

        assertTrue("expected a failure, got $result", result is ActionResult.Failure)
        val reason = (result as ActionResult.Failure).reason
        assertTrue("should say it could not read: $reason", reason.contains("in front"))
        assertTrue("should not blame the grant: $reason", !reason.contains("Accessibility"))
        assertEquals(0, controller.homePresses)
    }

    /**
     * A refusal from the port is passed through word for word. The port knows
     * why it refused, and the locked-phone refusal in particular tells the
     * person something this action could not work out for itself.
     */
    @Test
    fun `a refused home press is reported as the port worded it`() = runTest {
        val controller = FakeForegroundApp(
            front = "com.example.game",
            home = ActionResult.Failure("The phone is locked."),
        )
        val action = SoftCloseAppAction(controller, packageName = "com.example.game")

        val result = action.execute(event)

        assertEquals(ActionResult.Failure("The phone is locked."), result)
    }

    /**
     * The port with nothing wired behind it, which is what `:actions` gets by
     * default. It must refuse rather than look like a working action.
     */
    @Test
    fun `the unavailable port refuses instead of doing nothing quietly`() = runTest {
        val action = SoftCloseAppAction(
            ForegroundAppController.Unavailable,
            packageName = "com.example.game",
        )

        val result = action.execute(event)

        assertTrue(result is ActionResult.Failure)
        assertNull(ForegroundAppController.Unavailable.foregroundPackage())
    }
}

/**
 * Config parsing, which is the half of this action that runs in the editor
 * rather than in a rule.
 */
class SoftCloseAppConfigTest {

    private val factory = SoftCloseAppActionFactory(ForegroundAppController.Unavailable)

    @Test
    fun `a chosen app builds an action`() {
        val action = factory.create(
            mapOf(SoftCloseAppAction.CONFIG_PACKAGE to "com.example.game")
        )

        assertTrue(action is SoftCloseAppAction)
    }

    /**
     * A package stored with stray spaces would match no window, so the action
     * would report "another app is in front" for ever and look like a rule that
     * works and never does anything.
     */
    @Test
    fun `surrounding space is trimmed rather than stored`() = runTest {
        val controller = FakeForegroundApp(front = "com.example.game")
        val action = SoftCloseAppActionFactory(controller).create(
            mapOf(SoftCloseAppAction.CONFIG_PACKAGE to "  com.example.game\n")
        )

        val result = action.execute(TriggerEvent(triggerType = "test", firedAtMillis = 1))

        assertEquals(1, controller.homePresses)
        assertTrue(result is ActionResult.Success)
    }

    @Test
    fun `a missing app is refused while the rule is built`() {
        assertThrows(IllegalArgumentException::class.java) {
            factory.create(emptyMap())
        }
    }

    @Test
    fun `a blank app is refused too`() {
        assertThrows(IllegalArgumentException::class.java) {
            factory.create(mapOf(SoftCloseAppAction.CONFIG_PACKAGE to "   "))
        }
    }

    /**
     * The editor can only say "turn accessibility access on" if the factory
     * declares it. Without this the action is the silent-failure case the
     * requirement model exists to stop.
     */
    @Test
    fun `the accessibility grant is declared as a requirement`() {
        val kinds = factory.requirements
            .filterIsInstance<app.phueber.trigly.core.ComponentRequirement.SpecialAccess>()
            .map { it.kind }

        assertTrue(
            "requirements were ${factory.requirements}",
            app.phueber.trigly.core.SpecialAccessKind.ACCESSIBILITY_SERVICE in kinds,
        )
    }

    /**
     * Both outputs the action can produce are declared, or a person cannot pick
     * them in the editor and `{{soft_close_app.sentBack}}` is a name only the
     * source code knows.
     */
    @Test
    fun `both outputs are offered to the editor`() {
        assertEquals(
            listOf(
                SoftCloseAppAction.OUTPUT_SENT_BACK,
                SoftCloseAppAction.OUTPUT_FOREGROUND,
            ),
            factory.variables.map { it.key },
        )
    }

    /**
     * The limit that reads as a fault has to be stated where the rule is built.
     * "It keeps running" is the sentence a person needs before they trust this
     * action to close anything.
     */
    @Test
    fun `the help and the warning both say the app is not stopped`() {
        val help = factory.configFields.single().help.orEmpty()
        assertTrue("help was: $help", help.contains("not stopped"))
        assertTrue("help was: $help", help.contains("keeps running"))
        assertTrue("warning was: ${factory.warning}", factory.warning.contains("keeps running"))
    }
}

private class FakeForegroundApp(
    private val front: String?,
    private val connected: Boolean = true,
    private val home: ActionResult = ActionResult.Success(),
) : ForegroundAppController {

    var homePresses = 0
        private set

    var lookedAtTheScreen = false
        private set

    override val isConnected: Boolean get() = connected

    override fun foregroundPackage(): String? {
        lookedAtTheScreen = true
        return front
    }

    override fun goHome(): ActionResult {
        homePresses++
        return home
    }
}
