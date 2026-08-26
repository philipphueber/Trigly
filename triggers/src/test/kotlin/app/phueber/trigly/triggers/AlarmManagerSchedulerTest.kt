package app.phueber.trigly.triggers

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pure pieces of [AlarmManagerScheduler]: how wide a window it asks for,
 * and how it turns an absolute instant into a duration. Both are ordinary
 * arithmetic, and both are exactly where a sign error or an off-by-one would
 * hide on a device for weeks, so each branch gets its own case here instead
 * of trusting the formula by inspection.
 *
 * The class itself, which calls `AlarmManager`, is not exercised here: that
 * needs a device, per `docs/architecture.md`'s testing posture.
 */
class AlarmManagerSchedulerTest {

    @Test
    fun `a short wait gets the floor, not a sliver of itself`() {
        assertEquals(MIN_WINDOW_MILLIS, windowLengthMillis(1_000L))
    }

    @Test
    fun `a wait well above the floor gets a tenth of itself`() {
        val duration = MIN_WINDOW_MILLIS * 20
        assertEquals(duration / 10, windowLengthMillis(duration))
    }

    @Test
    fun `a long wait is capped, which is the drift this class promises`() {
        assertEquals(MAX_WINDOW_MILLIS, windowLengthMillis(24 * 60 * 60_000L))
    }

    @Test
    fun `a zero-length wait still gets the floor`() {
        assertEquals(MIN_WINDOW_MILLIS, windowLengthMillis(0L))
    }

    @Test
    fun `durationUntil is the plain gap when the instant is ahead`() {
        assertEquals(5_000L, durationUntil(nowMillis = 10_000L, atMillis = 15_000L))
    }

    @Test
    fun `durationUntil never goes negative for an instant already past`() {
        assertEquals(0L, durationUntil(nowMillis = 10_000L, atMillis = 5_000L))
    }

    @Test
    fun `durationUntil is zero for the instant that is now`() {
        assertEquals(0L, durationUntil(nowMillis = 10_000L, atMillis = 10_000L))
    }
}
