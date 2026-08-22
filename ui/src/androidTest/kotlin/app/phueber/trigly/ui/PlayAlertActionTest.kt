package app.phueber.trigly.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.phueber.trigly.actions.AlertSound
import app.phueber.trigly.actions.PlayAlertAction
import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.TriggerEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The alert action's refusal paths, on a real device.
 *
 * The parsing around this action is unit-tested on the JVM; what needs a device
 * is that `execute` actually *refuses* rather than merely being able to. The
 * scheme guard is the security-relevant half of this action — an imported rule
 * with a remote sound URI would beacon on every fire — so it is worth proving at
 * the point it runs, not only at the pure function that informs it.
 *
 * Deliberately no test that plays a sound. Asserting audio came out needs
 * hardware this cannot inspect, and a suite that makes the device alarm for a
 * second on every run is a suite people start skipping. The playing path is
 * covered by `ConfigSchemaContractTest` constructing the action, and by hand.
 *
 * Lives in `:ui` for the same reason the contract test does: it is the module
 * that can see `:actions` and hand it a real `Context`.
 */
@RunWith(AndroidJUnit4::class)
class PlayAlertActionTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val event = TriggerEvent(
        triggerType = "notification_posted",
        firedAtMillis = 1_000,
        payload = emptyMap(),
    )

    private fun actionWithSound(uri: String?) = PlayAlertAction(
        context = context,
        sound = AlertSound.ALARM,
        customUri = uri,
        volumeGain = 0f,
        // Short, so a test that unexpectedly reaches playback does not hold the
        // suite open for seconds.
        durationMillis = 1,
    )

    @Test
    fun a_remote_sound_is_refused_before_anything_is_played() = runTest {
        val result = actionWithSound("https://example.com/siren.mp3").execute(event)

        assertTrue("expected a failure, got $result", result is ActionResult.Failure)
        val reason = (result as ActionResult.Failure).reason
        assertTrue("the reason should name the accepted schemes: $reason", reason.contains("content:"))
    }

    @Test
    fun a_path_with_no_scheme_is_refused() = runTest {
        val result = actionWithSound("/storage/emulated/0/Alarms/siren.ogg").execute(event)

        assertTrue("expected a failure, got $result", result is ActionResult.Failure)
    }

    @Test
    fun an_unreadable_local_sound_fails_without_throwing() = runTest {
        // A content URI of the right shape pointing at nothing. The action must
        // report this, not propagate it — one broken action must not take the
        // rest of the rule down with it.
        val result = actionWithSound("content://app.phueber.trigly.absent/nothing").execute(event)

        assertTrue("expected a failure, got $result", result is ActionResult.Failure)
    }
}
