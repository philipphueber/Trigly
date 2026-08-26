package app.phueber.trigly.triggers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /**
     * The backstop must not be able to share a batch with the in-process alarm.
     *
     * Two alarms asked for the same instant both fire on a live process, and the
     * surviving one then broadcasts and tries to start the engine on every tick
     * of every wait, refused on any device without the battery exemption. This
     * is the arithmetic that keeps them apart, so it is worth a test of its own
     * rather than a comment.
     */
    @Test
    fun `the backstop asks for a time past the first alarm's window`() {
        val window = windowLengthMillis(60_000L)

        val backstop = backstopAtMillis(triggerAtMillis = 1_000_000L, windowLengthMillis = window)

        assertTrue(
            "the backstop must land after the window closes, was $backstop",
            backstop > 1_000_000L + window,
        )
        assertEquals(1_000_000L + window + BACKSTOP_MARGIN_MILLIS, backstop)
    }

    /** The margin is the same whatever the wait, so a short wait cannot lose it. */
    @Test
    fun `a short wait keeps the whole margin`() {
        val shortWindow = windowLengthMillis(1_000L)

        assertEquals(
            shortWindow + BACKSTOP_MARGIN_MILLIS,
            backstopAtMillis(triggerAtMillis = 0L, windowLengthMillis = shortWindow),
        )
    }
}
