package app.phueber.trigly.triggers

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The wraparound arithmetic behind a time-window condition, which is the
 * whole reason [timeWindowHolds] is a pure function rather than something
 * proven only by poking [TimeWindowCheck] on a device — see `docs/conditions.md`,
 * "Passive-only checks".
 *
 * Every case below is picked to exercise the boundary convention this file
 * documents on [timeWindowHolds]: start inclusive, end exclusive, and a
 * start-equals-end window read as "no restriction" rather than "never".
 */
class TimeWindowTest {

    // --- an ordinary window, 09:00-17:00 (540..1020) ------------------------

    @Test
    fun `ordinary window holds in the middle`() {
        assertTrue(timeWindowHolds(nowMinuteOfDay = 720, startMinuteOfDay = 540, endMinuteOfDay = 1020))
    }

    @Test
    fun `ordinary window does not hold before it starts`() {
        assertFalse(timeWindowHolds(nowMinuteOfDay = 300, startMinuteOfDay = 540, endMinuteOfDay = 1020))
    }

    @Test
    fun `ordinary window does not hold after it ends`() {
        assertFalse(timeWindowHolds(nowMinuteOfDay = 1100, startMinuteOfDay = 540, endMinuteOfDay = 1020))
    }

    @Test
    fun `ordinary window's start is inclusive`() {
        assertTrue(timeWindowHolds(nowMinuteOfDay = 540, startMinuteOfDay = 540, endMinuteOfDay = 1020))
    }

    @Test
    fun `ordinary window's end is exclusive`() {
        assertFalse(timeWindowHolds(nowMinuteOfDay = 1020, startMinuteOfDay = 540, endMinuteOfDay = 1020))
    }

    // --- a wraparound window, 22:00-07:00 (1320..420) ------------------------

    @Test
    fun `wraparound window holds just after it starts, before midnight`() {
        assertTrue(timeWindowHolds(nowMinuteOfDay = 1350, startMinuteOfDay = 1320, endMinuteOfDay = 420))
    }

    @Test
    fun `wraparound window holds in the middle of the night`() {
        assertTrue(timeWindowHolds(nowMinuteOfDay = 120, startMinuteOfDay = 1320, endMinuteOfDay = 420))
    }

    @Test
    fun `wraparound window holds just before it ends, after midnight`() {
        assertTrue(timeWindowHolds(nowMinuteOfDay = 419, startMinuteOfDay = 1320, endMinuteOfDay = 420))
    }

    @Test
    fun `wraparound window does not hold in the middle of the day`() {
        assertFalse(timeWindowHolds(nowMinuteOfDay = 720, startMinuteOfDay = 1320, endMinuteOfDay = 420))
    }

    @Test
    fun `wraparound window's start is inclusive`() {
        assertTrue(timeWindowHolds(nowMinuteOfDay = 1320, startMinuteOfDay = 1320, endMinuteOfDay = 420))
    }

    @Test
    fun `wraparound window's end is exclusive`() {
        assertFalse(timeWindowHolds(nowMinuteOfDay = 420, startMinuteOfDay = 1320, endMinuteOfDay = 420))
    }

    @Test
    fun `wraparound window holds at midnight itself`() {
        assertTrue(timeWindowHolds(nowMinuteOfDay = 0, startMinuteOfDay = 1320, endMinuteOfDay = 420))
    }

    // --- an ordinary window against midnight, to show the same instant reads
    //     differently depending on which window is asking -------------------

    @Test
    fun `ordinary window does not hold at midnight when midnight is outside its span`() {
        assertFalse(timeWindowHolds(nowMinuteOfDay = 0, startMinuteOfDay = 540, endMinuteOfDay = 1020))
    }

    // --- start == end: defined as "no restriction", not "never" -------------

    @Test
    fun `start equal to end holds at that instant`() {
        assertTrue(timeWindowHolds(nowMinuteOfDay = 600, startMinuteOfDay = 600, endMinuteOfDay = 600))
    }

    @Test
    fun `start equal to end holds at midnight too`() {
        assertTrue(timeWindowHolds(nowMinuteOfDay = 0, startMinuteOfDay = 0, endMinuteOfDay = 0))
    }

    @Test
    fun `start equal to end holds at an unrelated hour, meaning no restriction`() {
        assertTrue(timeWindowHolds(nowMinuteOfDay = 1439, startMinuteOfDay = 600, endMinuteOfDay = 600))
    }

    // --- the class itself: clock injection and zone conversion, not just the
    //     pure function --------------------------------------------------

    @Test
    fun `the check reads the clock in the zone it was given, not UTC`() = runTest {
        // 23:30 UTC is 00:30 the next day in Berlin (UTC+1 in winter), which
        // falls inside a 22:00-07:00 window only once the zone conversion has
        // happened — a check that forgot to convert would see 23:30 and agree
        // by accident, so the instant is chosen where the two zones disagree
        // on whether it is even the same day.
        val utcInstant = Instant.parse("2026-01-15T23:30:00Z")
        val check = TimeWindowCheck(
            startHour = 22,
            startMinute = 0,
            endHour = 7,
            endMinute = 0,
            zone = ZoneId.of("Europe/Berlin"),
            now = { utcInstant.toEpochMilli() },
        )

        assertTrue(check.currentlyHolds())
    }

    @Test
    fun `the check does not hold outside the window in its own zone`() = runTest {
        val utcInstant = Instant.parse("2026-01-15T12:00:00Z")
        val check = TimeWindowCheck(
            startHour = 22,
            startMinute = 0,
            endHour = 7,
            endMinute = 0,
            zone = ZoneOffset.UTC,
            now = { utcInstant.toEpochMilli() },
        )

        assertFalse(check.currentlyHolds())
    }
}
