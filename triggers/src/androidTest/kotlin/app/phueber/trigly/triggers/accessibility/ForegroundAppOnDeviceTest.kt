package app.phueber.trigly.triggers.accessibility

import android.app.UiAutomation
import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.phueber.trigly.core.ActionResult
import java.io.ByteArrayOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two claims behind `soft_close_app` that only a device can settle.
 *
 * The action's own branches are unit-tested against a fake port, so nothing
 * here repeats them. What a JVM test cannot see is whether the port tells the
 * truth: whether the window list really names the app a person is looking at,
 * and whether the Home key really takes that app out of the front. Both are
 * framework behaviour, and both fail silently if they are wrong. A wrong
 * reading makes the action report "another app is in front" for ever, which
 * looks exactly like a rule that works and never fires.
 *
 * The third claim is the one the help text makes and the name invites doubt
 * about: the app keeps running. That is measured by its process id, before and
 * after, because "it is still there" is the whole difference between this
 * action and a force stop.
 *
 * **`UiAutomation` is itself an accessibility service, and by default it turns
 * every other one off.** That is why this test asks for it with
 * `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`, and why every shell command here
 * goes through that one instance. Without the flag the setting is written, the
 * system accepts it, `dumpsys accessibility` reports the service as enabled,
 * and `Bound services` stays empty for the whole run. Nothing says why. The
 * only trace is one line from `UiAutomationManager` about registering the test
 * automation service. A test written the ordinary way therefore reports "the
 * service never bound" and looks like a fault in the code under test.
 *
 * **This test turns the accessibility service on and turns it back off.** It
 * reads both secure settings first and restores exactly what it found, because
 * the emulator is shared: a service left bound changes what another module's
 * tests measure, and a run that ends with the grant still on passes on its own
 * and changes the next run.
 */
@RunWith(AndroidJUnit4::class)
class ForegroundAppOnDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    /**
     * The one automation instance for the whole test, and the flag is the whole
     * point of it. See the class note: the default instance suppresses the
     * service under test. Asking for it once here also keeps a later
     * `getUiAutomation()` with no flags from replacing it.
     */
    private val automation: UiAutomation = instrumentation.getUiAutomation(
        UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
    )

    private val controller = ServiceForegroundAppController()

    private var previousServices: String? = null
    private var previousEnabled: String? = null

    @Before
    fun setUp() {
        previousServices = secure(ENABLED_SERVICES)
        previousEnabled = secure(ACCESSIBILITY_ENABLED)

        // The window list is empty while the screen is off, and every claim
        // here is about what is on screen.
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")

        // Measured, not guessed: without this the two settings below are
        // written and then wiped by the system a moment later, and the service
        // never binds. Android 13 put the accessibility grant behind a
        // "restricted setting" for any app that did not arrive from an app
        // store. `AccessibilityManagerService` re-reads the setting, finds the
        // app-op denied, and drops the component from the list. Nothing is
        // logged. See `docs/actions.md`: a sideloaded Trigly meets the same
        // wall, and the person has to clear it by hand in the app info screen.
        shell("appops set ${context.packageName} $RESTRICTED_SETTINGS allow")

        val component =
            "${context.packageName}/${TriglyAccessibilityService::class.java.name}"
        shell("settings put secure $ENABLED_SERVICES $component")
        shell("settings put secure $ACCESSIBILITY_ENABLED 1")

        // Waited for rather than assumed: the bind is asynchronous, so reading
        // the service straight away fails on a cold device and passes on a warm
        // one, which is the worst of both.
        val bound = waitFor(seconds = 20) { controller.isConnected }
        assertTrue(
            "The accessibility service never bound, so nothing can be read. " +
                "The setting holds '${secure(ENABLED_SERVICES)}'. " +
                "The app-op reads " +
                "'${shell("appops get ${context.packageName} $RESTRICTED_SETTINGS").trim()}'. " +
                "An empty setting means the system dropped the component, which " +
                "is the restricted setting. A setting that holds the component " +
                "while nothing binds means something suppressed the service, " +
                "which is a UiAutomation taken without the flag above.",
            bound,
        )

        shell("input keyevent KEYCODE_HOME")
    }

    @After
    fun tearDown() {
        restore(ENABLED_SERVICES, previousServices)
        restore(ACCESSIBILITY_ENABLED, previousEnabled)
        shell("appops set ${context.packageName} $RESTRICTED_SETTINGS default")
        shell("am force-stop $SETTINGS")
        shell("input keyevent KEYCODE_HOME")
    }

    /**
     * The read that everything else rests on. An app is opened, and the port
     * must name that app and not the keyboard, the shade, or the app that was
     * there before.
     */
    @Test
    fun the_window_list_names_the_app_that_is_in_front() {
        openSettings()

        assertEquals(
            "The port named the wrong app as the one in front.",
            SETTINGS,
            controller.foregroundPackage(),
        )
    }

    /**
     * The other half: the Home key really does take the front app away. The
     * platform reports `performGlobalAction` as accepted in cases where nothing
     * happens, so the return value alone proves nothing and the front app is
     * read again.
     */
    @Test
    fun going_home_takes_the_app_out_of_the_front() {
        openSettings()

        val result = controller.goHome()
        assertTrue("Home was refused: $result", result is ActionResult.Success)

        val left = waitFor(seconds = 10) { controller.foregroundPackage() != SETTINGS }
        assertTrue(
            "The app was still in front after Home. The port reported a success " +
                "that did not happen, which is the failure this check exists for.",
            left,
        )
        assertNotEquals(SETTINGS, controller.foregroundPackage())
    }

    /**
     * The claim the help text makes, measured rather than asserted in prose.
     * This is a soft close: the process must be the same process afterwards.
     * A different process id would mean the app was killed and restarted; no
     * process id at all would mean it was stopped.
     */
    @Test
    fun the_app_keeps_running_after_it_is_sent_back() {
        openSettings()
        val before = processId(SETTINGS)
        assertTrue("Settings had no process to keep.", before.isNotEmpty())

        controller.goHome()
        waitFor(seconds = 10) { controller.foregroundPackage() != SETTINGS }

        assertEquals(
            "The app did not survive being sent back. This action is a soft " +
                "close and must not stop anything.",
            before,
            processId(SETTINGS),
        )
    }

    private fun openSettings() {
        shell("am start -a android.settings.SETTINGS")
        val shown = waitFor(seconds = 15) { controller.foregroundPackage() == SETTINGS }
        assertTrue(
            "Settings never came to the front, so there is nothing to read. " +
                "The port saw '${controller.foregroundPackage()}'.",
            shown,
        )
    }

    /** Empty when the app has no process. */
    private fun processId(packageName: String): String =
        shell("pidof $packageName").trim()

    private fun secure(key: String): String? =
        shell("settings get secure $key").trim().takeIf { it.isNotEmpty() && it != "null" }

    private fun restore(key: String, value: String?) {
        if (value == null) shell("settings delete secure $key")
        else shell("settings put secure $key $value")
    }

    private fun waitFor(seconds: Int, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + seconds * 1_000L
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    private fun shell(command: String): String {
        val descriptor = automation.executeShellCommand(command)
        return ByteArrayOutputStream().use { out ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                input.copyTo(out)
            }
            out.toString()
        }
    }

    private companion object {
        const val ENABLED_SERVICES = "enabled_accessibility_services"
        const val ACCESSIBILITY_ENABLED = "accessibility_enabled"
        const val RESTRICTED_SETTINGS = "ACCESS_RESTRICTED_SETTINGS"

        /**
         * A stand-in for "some app the person is looking at". Settings is on
         * every Android build, needs no login, and opens from a shell command,
         * which the test APK's own package cannot do: a library module has no
         * launcher activity of its own.
         */
        const val SETTINGS = "com.android.settings"
    }
}
