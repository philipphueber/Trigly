package app.phueber.trigly.triggers

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.Month
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * [monthHolds] and the clock/zone handling around it in [MonthCheck]. See that
 * file's KDoc, and [DayOfWeekCheck]'s, for the reasoning each case exercises.
 */
class MonthTest {

    private val summer = setOf(Month.JUNE, Month.JULY, Month.AUGUST)
    private val quarterly = setOf(Month.MARCH, Month.JULY, Month.DECEMBER)

    @Test
    fun `a contiguous set holds on a month inside it`() {
        assertTrue(monthHolds(Month.JULY, summer))
    }

    @Test
    fun `a contiguous set does not hold on a month outside it`() {
        assertFalse(monthHolds(Month.JANUARY, summer))
    }

    @Test
    fun `a non-contiguous set holds on each picked month`() {
        assertTrue(monthHolds(Month.MARCH, quarterly))
        assertTrue(monthHolds(Month.JULY, quarterly))
        assertTrue(monthHolds(Month.DECEMBER, quarterly))
    }

    @Test
    fun `a non-contiguous set does not hold on a month between the picked ones`() {
        assertFalse(monthHolds(Month.APRIL, quarterly))
    }

    @Test
    fun `no months picked never holds`() {
        assertFalse(monthHolds(Month.JANUARY, emptySet()))
    }

    @Test
    fun `every month picked always holds`() {
        assertTrue(monthHolds(Month.entries.random(), Month.entries.toSet()))
    }

    // --- the class itself: clock injection and zone conversion -------------

    @Test
    fun `the check reads the month in the zone it was given, not UTC`() = runTest {
        // 23:30 UTC on 31 January is already 1 February in Berlin (UTC+1 in
        // winter), so a check that forgot to convert the zone would see
        // January and disagree with one that picks only February.
        val utcInstant = Instant.parse("2026-01-31T23:30:00Z")
        val check = MonthCheck(
            selectedMonths = setOf(Month.FEBRUARY),
            zone = ZoneId.of("Europe/Berlin"),
            now = { utcInstant.toEpochMilli() },
        )

        assertTrue(check.currentlyHolds())
    }

    @Test
    fun `the same instant reads as January in a zone that has not crossed midnight yet`() = runTest {
        val utcInstant = Instant.parse("2026-01-31T23:30:00Z")
        val check = MonthCheck(
            selectedMonths = setOf(Month.FEBRUARY),
            zone = ZoneOffset.UTC,
            now = { utcInstant.toEpochMilli() },
        )

        assertFalse(check.currentlyHolds())
    }
}
