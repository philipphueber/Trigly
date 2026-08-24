package app.phueber.trigly.actions

import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.ActiveNotification
import app.phueber.trigly.core.NotificationController
import app.phueber.trigly.core.SharedPayloadKeys
import app.phueber.trigly.core.TriggerEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Stop the alert once the notification is gone", decided and waited for.
 *
 * Both halves are here because both are things no manual test would catch. The
 * decision has two ways of being impossible — a rule no notification fired, and
 * notification access not granted — and each must produce a *report* rather than
 * a quiet full-length alarm. The wait has to end immediately when the
 * notification was already gone before the first note played, which is the case
 * an edge-triggered listener would sleep through forever.
 *
 * The waiting tests run on virtual time, so a minute of polling costs nothing
 * and the *timing* is asserted rather than tolerated.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AlertStopWhenGoneTest {

    private val key = "0|com.example|42|null|10123"

    private val fromNotification = TriggerEvent(
        triggerType = "notification_posted",
        firedAtMillis = 1_000,
        payload = mapOf(SharedPayloadKeys.NOTIFICATION_KEY to key),
    )

    private val fromBattery = TriggerEvent(
        triggerType = "battery_level",
        firedAtMillis = 1_000,
        payload = mapOf("level" to "20"),
    )

    // --- what the alert watches ---------------------------------------------

    @Test
    fun `with the option off nothing is watched, key or no key`() {
        assertEquals(
            AlertStop.Duration,
            alertStop(stopWhenGone = false, event = fromNotification, notificationAccess = true),
        )
    }

    @Test
    fun `with the option on it watches the notification that fired the rule`() {
        assertEquals(
            AlertStop.WhenGone(key),
            alertStop(stopWhenGone = true, event = fromNotification, notificationAccess = true),
        )
    }

    @Test
    fun `a rule no notification fired says so, and names the trigger`() {
        val stop = alertStop(stopWhenGone = true, event = fromBattery, notificationAccess = true)

        assertTrue("expected Unwatchable, got $stop", stop is AlertStop.Unwatchable)
        val reason = (stop as AlertStop.Unwatchable).reason
        assertTrue("should name the trigger that did fire: $reason", reason.contains("battery_level"))
        assertTrue("should say the alert still played: $reason", reason.contains("full length"))
    }

    @Test
    fun `a blank key is treated as no key rather than watched`() {
        // A payload can carry the key as an empty string; watching "" would wait
        // on a notification that cannot exist and burn the whole duration.
        val stop = alertStop(
            stopWhenGone = true,
            event = fromNotification.copy(
                payload = mapOf(SharedPayloadKeys.NOTIFICATION_KEY to "  ")
            ),
            notificationAccess = true,
        )

        assertTrue("expected Unwatchable, got $stop", stop is AlertStop.Unwatchable)
    }

    @Test
    fun `without notification access it reports that, not the missing trigger`() {
        val stop = alertStop(stopWhenGone = true, event = fromNotification, notificationAccess = false)

        assertTrue("expected Unwatchable, got $stop", stop is AlertStop.Unwatchable)
        val reason = (stop as AlertStop.Unwatchable).reason
        assertTrue("should name the access it needs: $reason", reason.contains("notification access"))
    }

    // --- waiting for it to go away ------------------------------------------

    @Test
    fun `a notification already gone stops the alert without a single delay`() = runTest {
        val notifications = FakePresence(presentForChecks = 0)

        awaitNotificationGone(notifications, key, pollMillis = 500)

        assertEquals("must not sleep before the first look", 0L, testScheduler.currentTime)
        assertEquals(1, notifications.checks)
    }

    @Test
    fun `it returns on the poll that first finds the notification gone`() = runTest {
        val notifications = FakePresence(presentForChecks = 2)

        awaitNotificationGone(notifications, key, pollMillis = 500)

        // Present at 0 and at 500, gone at 1000.
        assertEquals(1_000L, testScheduler.currentTime)
        assertEquals(3, notifications.checks)
    }

    @Test
    fun `a notification that never goes away leaves the duration in charge`() = runTest {
        val notifications = FakePresence(presentForChecks = Int.MAX_VALUE)

        val finished = withTimeoutOrNull(2_000) {
            awaitNotificationGone(notifications, key, pollMillis = 500)
        }

        assertNull("the wait must not end on its own", finished)
        assertEquals(2_000L, testScheduler.currentTime)
    }

    @Test
    fun `another app's notification going away is not this alert's cue`() = runTest {
        val notifications = FakePresence(
            presentForChecks = Int.MAX_VALUE,
            key = "0|com.other|1|null|10",
        )

        val finished = withTimeoutOrNull(2_000) {
            awaitNotificationGone(notifications, key, pollMillis = 500)
        }

        // The watched key is absent from the start, so this returns at once —
        // proving the match is on the key rather than on "anything is posted".
        assertEquals(Unit, finished)
        assertEquals(0L, testScheduler.currentTime)
    }
}

/**
 * A listener that holds one notification for a given number of looks.
 *
 * Counting *checks* rather than time is what makes the poll interval assertable:
 * the test says how many times it was asked, and virtual time says how long that
 * took.
 */
private class FakePresence(
    private val presentForChecks: Int,
    private val key: String = "0|com.example|42|null|10123",
) : NotificationController {

    var checks = 0
        private set

    override val isConnected: Boolean = true

    override fun activeNotifications(): List<ActiveNotification> {
        val present = checks < presentForChecks
        checks++
        return if (present) listOf(notification(key)) else emptyList()
    }

    override fun dismiss(key: String): ActionResult =
        throw AssertionError("watching must not dismiss anything")

    override fun triggerActionButton(key: String, actionIndex: Int): ActionResult =
        throw AssertionError("watching must not press anything")

    private fun notification(key: String) = ActiveNotification(
        key = key,
        packageName = key.substringAfter('|').substringBefore('|'),
        title = "Doorbell",
        text = "Someone is at the door",
        postedAtMillis = 1_000,
        buttons = emptyList(),
    )
}
