package app.phueber.trigly.triggers

import app.phueber.trigly.triggers.notification.NotificationWatchdogTrigger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Proves the poll between checks now goes through the scheduler port, not a
 * plain coroutine `delay`, and that the watchdog's own alert logic still
 * reaches the emitted event once it fires. `NotificationEvents.service` is
 * null in a JVM test, so every check here sees "absent", a deterministic
 * condition that needs no device and no fake listener service.
 *
 * [now] is sourced from `runTest`'s own virtual clock, so the gap
 * `AbsenceWatchdog` measures between checks is the real wait the scheduler
 * was asked for, not a value this test invents on its own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationWatchdogTriggerTest {

    @Test
    fun `asks the scheduler for pollMillis, and reports never-seen once the absence window passes`() =
        runTest {
            val scheduler = FakeAlarmScheduler(now = { currentTime })
            val trigger = NotificationWatchdogTrigger(
                packageName = "com.example.watched",
                requireOngoing = true,
                absenceMillis = 30_000L,
                pollMillis = 30_000L,
                scheduler = scheduler,
                now = { currentTime },
            )

            val event = trigger.events().take(1).toList().single()

            assertEquals(listOf(30_000L), scheduler.waitForCalls)
            assertEquals("never_seen", event.payload["reason"])
            assertEquals("com.example.watched", event.payload["package"])
        }
}
