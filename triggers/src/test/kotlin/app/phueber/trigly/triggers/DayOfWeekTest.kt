package app.phueber.trigly.triggers

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * [dayOfWeekHolds] and the clock/zone handling around it in [DayOfWeekCheck].
 * See that file's KDoc for the reasoning each case below is picked to exercise.
 */
class DayOfWeekTest {

    private val weekdays = setOf(
        DayOfWeek.MONDAY,
        DayOfWeek.TUESDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY,
    )
    private val weekend = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

    @Test
    fun `weekdays holds on a day inside the set`() {
        assertTrue(dayOfWeekHolds(DayOfWeek.WEDNESDAY, weekdays))
    }

    @Test
    fun `weekdays does not hold on a day outside the set`() {
        assertFalse(dayOfWeekHolds(DayOfWeek.SATURDAY, weekdays))
    }

    @Test
    fun `weekend holds on both weekend days`() {
        assertTrue(dayOfWeekHolds(DayOfWeek.SATURDAY, weekend))
        assertTrue(dayOfWeekHolds(DayOfWeek.SUNDAY, weekend))
    }

    @Test
    fun `weekend does not hold on a weekday`() {
        assertFalse(dayOfWeekHolds(DayOfWeek.MONDAY, weekend))
    }

    @Test
    fun `a single picked day holds only on that day`() {
        val onlyFriday = setOf(DayOfWeek.FRIDAY)
        assertTrue(dayOfWeekHolds(DayOfWeek.FRIDAY, onlyFriday))
        assertFalse(dayOfWeekHolds(DayOfWeek.THURSDAY, onlyFriday))
        assertFalse(dayOfWeekHolds(DayOfWeek.SATURDAY, onlyFriday))
    }

    @Test
    fun `no days picked never holds`() {
        assertFalse(dayOfWeekHolds(DayOfWeek.MONDAY, emptySet()))
    }

    @Test
    fun `every day picked always holds`() {
        assertTrue(dayOfWeekHolds(DayOfWeek.entries.random(), DayOfWeek.entries.toSet()))
    }

    // --- the class itself: clock injection and zone conversion -------------

    @Test
    fun `the check reads the day in the zone it was given, not UTC`() = runTest {
        // 23:30 UTC on a Sunday is already Monday in Berlin (UTC+1 in
        // winter), so a check that forgot to convert the zone would see
        // Sunday and disagree with one that picks only Monday.
        val utcInstant = Instant.parse("2026-01-11T23:30:00Z") // a Sunday, UTC
        val check = DayOfWeekCheck(
            selectedDays = setOf(DayOfWeek.MONDAY),
            zone = ZoneId.of("Europe/Berlin"),
            now = { utcInstant.toEpochMilli() },
        )

        assertTrue(check.currentlyHolds())
    }

    @Test
    fun `the same instant reads as Sunday in a zone that has not crossed midnight yet`() = runTest {
        val utcInstant = Instant.parse("2026-01-11T23:30:00Z") // a Sunday, UTC
        val check = DayOfWeekCheck(
            selectedDays = setOf(DayOfWeek.MONDAY),
            zone = ZoneOffset.UTC,
            now = { utcInstant.toEpochMilli() },
        )

        assertFalse(check.currentlyHolds())
    }
}
