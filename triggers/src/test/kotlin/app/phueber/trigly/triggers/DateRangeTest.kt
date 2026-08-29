package app.phueber.trigly.triggers

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The month/day arithmetic behind a date-range condition. See
 * [dateRangeHolds]'s own KDoc for the boundary convention each case below
 * exercises: both ends inclusive, wraparound as the complement, and
 * start-equals-end read as a single day rather than as "no restriction".
 *
 * Codes throughout are `month * 100 + day`, matching
 * [DateRangeCheck.monthDayCode]: 15 March is `315`, 6 January is `106`.
 */
class DateRangeTest {

    // --- an ordinary range, 1 June (601) to 31 August (831) -----------------

    @Test
    fun `ordinary range holds in the middle`() {
        assertTrue(dateRangeHolds(currentMonthDay = 715, startMonthDay = 601, endMonthDay = 831))
    }

    @Test
    fun `ordinary range does not hold before it starts`() {
        assertFalse(dateRangeHolds(currentMonthDay = 515, startMonthDay = 601, endMonthDay = 831))
    }

    @Test
    fun `ordinary range does not hold after it ends`() {
        assertFalse(dateRangeHolds(currentMonthDay = 915, startMonthDay = 601, endMonthDay = 831))
    }

    @Test
    fun `ordinary range's start day holds, inclusive`() {
        assertTrue(dateRangeHolds(currentMonthDay = 601, startMonthDay = 601, endMonthDay = 831))
    }

    @Test
    fun `ordinary range's end day holds too, inclusive`() {
        assertTrue(dateRangeHolds(currentMonthDay = 831, startMonthDay = 601, endMonthDay = 831))
    }

    @Test
    fun `the day after an ordinary range's end does not hold`() {
        assertFalse(dateRangeHolds(currentMonthDay = 901, startMonthDay = 601, endMonthDay = 831))
    }

    // --- a wraparound range, 1 December (1201) to 6 January (106) -----------

    @Test
    fun `wraparound range holds just after it starts, before the new year`() {
        assertTrue(dateRangeHolds(currentMonthDay = 1225, startMonthDay = 1201, endMonthDay = 106))
    }

    @Test
    fun `wraparound range holds just before it ends, after the new year`() {
        assertTrue(dateRangeHolds(currentMonthDay = 103, startMonthDay = 1201, endMonthDay = 106))
    }

    @Test
    fun `wraparound range does not hold in the middle of the year`() {
        assertFalse(dateRangeHolds(currentMonthDay = 615, startMonthDay = 1201, endMonthDay = 106))
    }

    @Test
    fun `wraparound range's start day holds, inclusive`() {
        assertTrue(dateRangeHolds(currentMonthDay = 1201, startMonthDay = 1201, endMonthDay = 106))
    }

    @Test
    fun `wraparound range's end day holds too, inclusive`() {
        assertTrue(dateRangeHolds(currentMonthDay = 106, startMonthDay = 1201, endMonthDay = 106))
    }

    @Test
    fun `wraparound range holds on new year's day itself`() {
        assertTrue(dateRangeHolds(currentMonthDay = 101, startMonthDay = 1201, endMonthDay = 106))
    }

    @Test
    fun `the day after a wraparound range's end does not hold`() {
        assertFalse(dateRangeHolds(currentMonthDay = 107, startMonthDay = 1201, endMonthDay = 106))
    }

    // --- start == end: a single day, deliberately not "no restriction" ------

    @Test
    fun `start equal to end holds only on that day`() {
        assertTrue(dateRangeHolds(currentMonthDay = 1225, startMonthDay = 1225, endMonthDay = 1225))
    }

    @Test
    fun `start equal to end does not hold the day before`() {
        assertFalse(dateRangeHolds(currentMonthDay = 1224, startMonthDay = 1225, endMonthDay = 1225))
    }

    @Test
    fun `start equal to end does not hold the day after`() {
        assertFalse(dateRangeHolds(currentMonthDay = 1226, startMonthDay = 1225, endMonthDay = 1225))
    }

    // --- the class itself: validation, clock injection, zone conversion,
    //     and the leap day ---------------------------------------------------

    @Test(expected = IllegalArgumentException::class)
    fun `30 February is refused, even though a two-digit day is otherwise in range`() {
        DateRangeCheck(startMonth = 2, startDay = 30, endMonth = 12, endDay = 31)
    }

    @Test
    fun `29 February is accepted as a boundary`() {
        // Would throw if the reference year used for validation were not a
        // leap year.
        DateRangeCheck(startMonth = 2, startDay = 29, endMonth = 3, endDay = 1)
    }

    @Test
    fun `a range ending on the leap day includes it in a leap year`() = runTest {
        val leapDay = Instant.parse("2024-02-29T12:00:00Z") // 2024 is a leap year
        val check = DateRangeCheck(
            startMonth = 2,
            startDay = 1,
            endMonth = 2,
            endDay = 29,
            zone = ZoneOffset.UTC,
            now = { leapDay.toEpochMilli() },
        )

        assertTrue(check.currentlyHolds())
    }

    @Test
    fun `the day after the leap day is outside a range ending on it`() = runTest {
        val marchFirst = Instant.parse("2024-03-01T12:00:00Z")
        val check = DateRangeCheck(
            startMonth = 2,
            startDay = 1,
            endMonth = 2,
            endDay = 29,
            zone = ZoneOffset.UTC,
            now = { marchFirst.toEpochMilli() },
        )

        assertFalse(check.currentlyHolds())
    }

    @Test
    fun `28 February is outside a range ending on the leap day, in a non-leap year`() = runTest {
        // 2026 is not a leap year, so the calendar never produces 29
        // February for this check to be asked about; 28 February is the
        // last day that exists and is still excluded, since it is not the
        // day the range says it ends on.
        val feb28 = Instant.parse("2026-02-28T12:00:00Z")
        val check = DateRangeCheck(
            startMonth = 2,
            startDay = 29,
            endMonth = 3,
            endDay = 1,
            zone = ZoneOffset.UTC,
            now = { feb28.toEpochMilli() },
        )

        assertFalse(check.currentlyHolds())
    }

    @Test
    fun `the check reads the date in the zone it was given, not UTC`() = runTest {
        // 23:30 UTC on 30 November is already 1 December in Berlin (UTC+1 in
        // winter), which falls inside a 1 December-6 January range only once
        // the zone conversion has happened.
        val utcInstant = Instant.parse("2026-11-30T23:30:00Z")
        val check = DateRangeCheck(
            startMonth = 12,
            startDay = 1,
            endMonth = 1,
            endDay = 6,
            zone = ZoneId.of("Europe/Berlin"),
            now = { utcInstant.toEpochMilli() },
        )

        assertTrue(check.currentlyHolds())
    }

    @Test
    fun `the check does not hold outside the range in its own zone`() = runTest {
        val utcInstant = Instant.parse("2026-11-30T23:30:00Z")
        val check = DateRangeCheck(
            startMonth = 12,
            startDay = 1,
            endMonth = 1,
            endDay = 6,
            zone = ZoneOffset.UTC,
            now = { utcInstant.toEpochMilli() },
        )

        assertFalse(check.currentlyHolds())
    }
}
