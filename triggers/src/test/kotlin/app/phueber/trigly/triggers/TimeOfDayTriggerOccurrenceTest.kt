package app.phueber.trigly.triggers

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * [TimeOfDayTrigger.nextOccurrenceMillis], the pure calculation the whole
 * trigger stands on. No clock, no scheduler, and no coroutine here: every
 * case is an instant in, an instant out.
 *
 * `TimeOfDayTriggerSchedulingTest` and `TimeOfDayTriggerCatchUpTest` cover
 * how [TimeOfDayTrigger.events] uses this; `TimeOfDayTriggerZoneChangeTest`
 * covers the live zone-change race.
 */
class TimeOfDayTriggerOccurrenceTest {

    private val berlin = ZoneId.of("Europe/Berlin")
    private val newYork = ZoneId.of("America/New_York")

    private fun trigger(
        hour: Int = 8,
        minute: Int = 0,
        days: Set<DayOfWeek> = DayOfWeek.values().toSet(),
    ) = TimeOfDayTrigger(
        hour = hour,
        minute = minute,
        days = days,
        scheduler = FakeAlarmScheduler(),
    )

    private fun instantOf(zone: ZoneId, year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun `the next occurrence is the fixed hour and minute, not now plus a day`() {
        // "Now" is itself a few minutes late, the way a real inexact alarm's
        // own firing often is. That lateness must not carry forward: the
        // next occurrence is exactly 08:00, never "however late today's
        // firing was, plus 24 hours".
        val trigger = trigger(hour = 8, minute = 0)
        val firedLateAt = instantOf(berlin, 2026, 3, 10, 8, 4)

        val next = checkNotNull(trigger.nextOccurrenceMillis(firedLateAt, berlin))

        assertEquals(instantOf(berlin, 2026, 3, 11, 8, 0), next)
    }

    @Test
    fun `a search anchored just before midnight still finds the right calendar day`() {
        val trigger = trigger(hour = 0, minute = 30)
        val fromMillis = instantOf(berlin, 2026, 3, 9, 23, 59)

        val next = checkNotNull(trigger.nextOccurrenceMillis(fromMillis, berlin))

        assertEquals(instantOf(berlin, 2026, 3, 10, 0, 30), next)
    }

    @Test
    fun `a day that is not selected is skipped for the next one that is`() {
        // 2026-03-10 is a Tuesday. Only Monday is selected, so the next
        // occurrence is the following Monday, not tomorrow.
        val trigger = trigger(hour = 8, minute = 0, days = setOf(DayOfWeek.MONDAY))
        val fromMillis = instantOf(berlin, 2026, 3, 10, 0, 0)

        val next = checkNotNull(trigger.nextOccurrenceMillis(fromMillis, berlin))

        assertEquals(instantOf(berlin, 2026, 3, 16, 8, 0), next)
    }

    @Test
    fun `one selected day wraps a full week, exercising the search bound`() {
        val trigger = trigger(hour = 8, minute = 0, days = setOf(DayOfWeek.MONDAY))
        // Anchored just after this Monday's own occurrence: the next one is
        // exactly seven days out, so an off-by-one in the search bound would
        // miss it.
        val fromMillis = instantOf(berlin, 2026, 3, 16, 8, 0)

        val next = checkNotNull(trigger.nextOccurrenceMillis(fromMillis, berlin))

        assertEquals(instantOf(berlin, 2026, 3, 23, 8, 0), next)
    }

    @Test
    fun `spring forward in Europe Berlin still fires at 8am, not 9am`() {
        // Europe/Berlin moves its clocks from 02:00 to 03:00 local on
        // 2026-03-29. Adding a fixed 24 hours in UTC to the previous
        // occurrence would land on 09:00 local, because the UTC offset
        // itself moved between the two dates; resolving through the zone
        // instead keeps 08:00 exact.
        val trigger = trigger(hour = 8, minute = 0)
        val fromMillis = instantOf(berlin, 2026, 3, 28, 8, 0)

        val next = checkNotNull(trigger.nextOccurrenceMillis(fromMillis, berlin))

        val expected = ZonedDateTime.of(2026, 3, 29, 8, 0, 0, 0, berlin)
        assertEquals(expected.toInstant().toEpochMilli(), next)
    }

    @Test
    fun `fall back in Europe Berlin still fires at 8am, not 7am`() {
        // The same clock moves back from 03:00 to 02:00 local on
        // 2026-10-25.
        val trigger = trigger(hour = 8, minute = 0)
        val fromMillis = instantOf(berlin, 2026, 10, 24, 8, 0)

        val next = checkNotNull(trigger.nextOccurrenceMillis(fromMillis, berlin))

        val expected = ZonedDateTime.of(2026, 10, 25, 8, 0, 0, 0, berlin)
        assertEquals(expected.toInstant().toEpochMilli(), next)
    }

    @Test
    fun `a zone that moves the other direction is equally exact`() {
        // America/New_York springs forward on 2026-03-08, the opposite
        // direction from Berlin's own transition above, and on a different
        // date.
        val trigger = trigger(hour = 8, minute = 0)
        val fromMillis = instantOf(newYork, 2026, 3, 7, 8, 0)

        val next = checkNotNull(trigger.nextOccurrenceMillis(fromMillis, newYork))

        val expected = ZonedDateTime.of(2026, 3, 8, 8, 0, 0, 0, newYork)
        assertEquals(expected.toInstant().toEpochMilli(), next)
    }
}
