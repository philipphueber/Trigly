package app.phueber.trigly.triggers

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId

/**
 * Proves [TimeOfDayTrigger.events] waits for the computed instant, and then
 * for the short anti-double-fire buffer, through the [AlarmScheduler] port
 * rather than a plain coroutine `delay`, and that the *durable* half of the
 * port is the one asked for. [TimeOfDayTriggerOccurrenceTest] is the
 * astronomy-free arithmetic this relies on; [TimeOfDayTriggerCatchUpTest] is
 * the catch-up half; [TimeOfDayTriggerZoneChangeTest] is the live zone-change
 * race.
 *
 * [now] is sourced from `runTest`'s own virtual clock rather than a fixed
 * value, so the second loop iteration sees a "now" that has genuinely moved
 * past the first fire, the same way [SolarTriggerSchedulingTest] already
 * relies on for [SolarTrigger].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimeOfDayTriggerSchedulingTest {

    private val zone = ZoneId.of("Europe/Berlin")
    private val start = 1_700_000_000_000L // an arbitrary but fixed instant

    @Test
    fun `waits durably until the computed instant, then a short buffer, before the next one`() = runTest {
        fun now() = start + currentTime

        val scheduler = FakeAlarmScheduler(now = ::now)
        val trigger = TimeOfDayTrigger(
            hour = 8,
            minute = 0,
            days = DayOfWeek.values().toSet(),
            scheduler = scheduler,
            zone = { zone },
            now = ::now,
        )
        val firstFireAt = checkNotNull(trigger.nextOccurrenceMillis(now(), zone))

        val events = trigger.events().take(2).toList()

        assertEquals(2, events.size)
        assertEquals(listOf(TimeOfDayTrigger.TYPE, TimeOfDayTrigger.TYPE), events.map { it.triggerType })
        assertEquals(listOf(1_000L), scheduler.waitForCalls)
        assertEquals(emptyList<Long>(), scheduler.waitUntilCalls)
        assertEquals(firstFireAt, scheduler.waitUntilDurableCalls[0])
        assertTrue(
            "the second occurrence must be strictly after the first",
            scheduler.waitUntilDurableCalls[1] > firstFireAt,
        )
    }

    @Test
    fun `the fired event carries the day of week it fired on`() = runTest {
        fun now() = start + currentTime

        val scheduler = FakeAlarmScheduler(now = ::now)
        val trigger = TimeOfDayTrigger(
            hour = 8,
            minute = 0,
            days = DayOfWeek.values().toSet(),
            scheduler = scheduler,
            zone = { zone },
            now = ::now,
        )

        val event = trigger.events().take(1).toList().single()

        assertTrue(event.payload[TimeOfDayTrigger.PAYLOAD_DAY] in DayOfWeek.values().map { it.configValue() })
    }
}
