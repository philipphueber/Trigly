package app.phueber.trigly.triggers

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId

/**
 * The same behaviour [SolarTriggerCatchUpTest] proves for [SolarTrigger], for
 * [TimeOfDayTrigger] instead: once a durable wait's alarm fired recently, a
 * fresh collection searches from a little before "now", so an occurrence
 * that was already due is found rather than skipped in favour of next
 * week's.
 *
 * [AlarmWakeEvents] is a process-wide object; each test clears it so one
 * case cannot leak a recorded wake into the next.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimeOfDayTriggerCatchUpTest {

    private val zone = ZoneId.of("Europe/Berlin")
    private val start = 1_700_000_000_000L // an arbitrary but fixed instant

    @Before
    fun setUp() = AlarmWakeEvents.clear()

    @After
    fun tearDown() = AlarmWakeEvents.clear()

    private fun eightAmTrigger(scheduler: FakeAlarmScheduler, now: () -> Long) = TimeOfDayTrigger(
        hour = 8,
        minute = 0,
        days = DayOfWeek.values().toSet(),
        scheduler = scheduler,
        zone = { zone },
        now = now,
    )

    @Test
    fun `a durable wake pending recovers an occurrence already due`() = runTest {
        fun now() = start + currentTime

        val todayEight = checkNotNull(
            eightAmTrigger(FakeAlarmScheduler(), ::now).nextOccurrenceMillis(now(), zone)
        )

        // The process comes back a couple of minutes after the very 08:00
        // it was durably woken for, well inside the catch-up window.
        val wokenAt = todayEight + 2 * 60_000L
        AlarmWakeEvents.record(atMillis = wokenAt)

        val scheduler = FakeAlarmScheduler(now = { wokenAt })
        val trigger = eightAmTrigger(scheduler) { wokenAt }

        trigger.events().take(1).toList()

        assertEquals(
            "the 08:00 already due must be found, not skipped for tomorrow's",
            todayEight,
            scheduler.waitUntilDurableCalls.single(),
        )
    }

    @Test
    fun `with nothing pending a late cold start skips to the next occurrence`() = runTest {
        fun now() = start + currentTime

        val todayEight = checkNotNull(
            eightAmTrigger(FakeAlarmScheduler(), ::now).nextOccurrenceMillis(now(), zone)
        )

        // No AlarmWakeEvents.record: an ordinary rule enabled by hand two
        // minutes after some unrelated 08:00, not a fresh collection woken
        // by this trigger's own durable wait.
        val enabledAt = todayEight + 2 * 60_000L
        val scheduler = FakeAlarmScheduler(now = { enabledAt })
        val trigger = eightAmTrigger(scheduler) { enabledAt }

        trigger.events().take(1).toList()

        assertTrue(
            "an ordinary cold start must not fire for an 08:00 already two minutes gone",
            scheduler.waitUntilDurableCalls.single() > todayEight,
        )
    }
}
