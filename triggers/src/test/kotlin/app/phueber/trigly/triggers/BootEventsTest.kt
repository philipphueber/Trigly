package app.phueber.trigly.triggers

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The freshness window on the boot record, which is the whole correctness story
 * for `device_restart`.
 *
 * The record has to outlive the moment — it is written by a manifest receiver and
 * read by a trigger a few milliseconds later — but it must not outlive it by
 * hours, or a rule toggled off and on at lunchtime would announce the morning's
 * reboot. Neither half of that is observable by running the app once, which is
 * why it is a bounded pure function with tests rather than a flag.
 *
 * A process-wide object needs clearing between tests; that is what [clear] is
 * for, and forgetting it would let one test's boot leak into the next.
 */
class BootEventsTest {

    @Before
    fun setUp() = BootEvents.clear()

    @After
    fun tearDown() = BootEvents.clear()

    @Test
    fun `with no boot recorded nothing is pending`() {
        assertFalse(BootEvents.pending(nowMillis = 1_000, reason = BootReason.RESTART))
    }

    @Test
    fun `a boot just recorded is pending`() {
        BootEvents.record(BootReason.RESTART, atMillis = 1_000)

        assertTrue(BootEvents.pending(nowMillis = 1_200, reason = BootReason.RESTART))
    }

    @Test
    fun `a boot older than the window is not pending`() {
        BootEvents.record(BootReason.RESTART, atMillis = 1_000)

        // The rule was enabled by hand long after the restart finished.
        assertFalse(
            BootEvents.pending(
                nowMillis = 1_000 + BootEvents.DEFAULT_WINDOW_MILLIS + 1,
                reason = BootReason.RESTART,
            )
        )
    }

    @Test
    fun `the edge of the window still counts`() {
        BootEvents.record(BootReason.RESTART, atMillis = 1_000)

        assertTrue(
            BootEvents.pending(
                nowMillis = 1_000 + BootEvents.DEFAULT_WINDOW_MILLIS,
                reason = BootReason.RESTART,
            )
        )
    }

    @Test
    fun `an app update is not a restart, and vice versa`() {
        BootEvents.record(BootReason.APP_UPDATED, atMillis = 1_000)

        assertFalse(
            "a restart rule must not fire because Trigly updated",
            BootEvents.pending(nowMillis = 1_100, reason = BootReason.RESTART),
        )
        assertTrue(BootEvents.pending(nowMillis = 1_100, reason = BootReason.APP_UPDATED))
    }

    @Test
    fun `reading does not consume, so two rules both see the same boot`() {
        BootEvents.record(BootReason.RESTART, atMillis = 1_000)

        assertTrue(BootEvents.pending(nowMillis = 1_100, reason = BootReason.RESTART))
        assertTrue(
            "a second rule on the same trigger must also fire",
            BootEvents.pending(nowMillis = 1_100, reason = BootReason.RESTART),
        )
    }

    @Test
    fun `a clock that has gone backwards does not count as pending`() {
        BootEvents.record(BootReason.RESTART, atMillis = 5_000)

        // Negative age: the wall clock was corrected between the record and the
        // read, which happens on boot more than anywhere else. Firing on a
        // negative age would be firing for a boot that has not happened yet.
        assertFalse(BootEvents.pending(nowMillis = 4_000, reason = BootReason.RESTART))
    }
}
