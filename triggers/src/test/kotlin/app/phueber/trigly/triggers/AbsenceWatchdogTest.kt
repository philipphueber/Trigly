package app.phueber.trigly.triggers

import app.phueber.trigly.triggers.notification.AbsenceWatchdog
import app.phueber.trigly.triggers.notification.WatchdogAlert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * This is the logic that decides whether someone's alerting app is reported as
 * dead. Every branch is exercised here, because the alternative is finding out
 * on a device five minutes at a time.
 */
class AbsenceWatchdogTest {

    private val start = 1_000_000L
    private val absence = 60_000L // one minute

    private fun watchdog() = AbsenceWatchdog(startedAtMillis = start, absenceMillis = absence)

    @Test
    fun `a notification that is present stays quiet`() {
        val w = watchdog()

        w.onSeen(start)
        assertNull(w.onTick(start + 30_000))

        w.onSeen(start + 60_000)
        assertNull(w.onTick(start + 90_000))
    }

    @Test
    fun `a notification that disappears alerts once the window passes`() {
        val w = watchdog()
        w.onSeen(start)

        assertNull("still inside the window", w.onTick(start + 59_000))
        assertEquals(WatchdogAlert.DISAPPEARED, w.onTick(start + 60_000))
    }

    @Test
    fun `the alert is raised once, not on every tick`() {
        val w = watchdog()
        w.onSeen(start)

        assertEquals(WatchdogAlert.DISAPPEARED, w.onTick(start + 60_000))
        assertNull(w.onTick(start + 120_000))
        assertNull(w.onTick(start + 600_000))
    }

    @Test
    fun `a service that comes back re-arms the alarm`() {
        val w = watchdog()
        w.onSeen(start)
        assertEquals(WatchdogAlert.DISAPPEARED, w.onTick(start + 60_000))

        // The app restarts and posts its notification again.
        w.onSeen(start + 70_000)
        assertNull(w.onTick(start + 80_000))

        // And dies a second time — this must alert again.
        assertEquals(WatchdogAlert.DISAPPEARED, w.onTick(start + 130_000))
    }

    @Test
    fun `never seeing the notification is reported as misconfiguration, not death`() {
        val w = watchdog()

        assertNull(w.onTick(start + 30_000))
        assertEquals(WatchdogAlert.NEVER_SEEN, w.onTick(start + 60_000))
    }

    @Test
    fun `never-seen becomes disappeared once it has been seen at least once`() {
        val w = watchdog()
        assertEquals(WatchdogAlert.NEVER_SEEN, w.onTick(start + 60_000))
        assertFalse(w.hasEverBeenSeen)

        w.onSeen(start + 70_000)
        assertTrue(w.hasEverBeenSeen)

        assertEquals(WatchdogAlert.DISAPPEARED, w.onTick(start + 140_000))
    }

    @Test
    fun `the grace period for a never-seen notification runs from the start, not from zero`() {
        // Guards the bug where an absent lastSeen is treated as epoch 0, making
        // every watchdog fire NEVER_SEEN on its very first tick.
        val w = watchdog()

        assertNull(w.onTick(start))
        assertNull(w.onTick(start + 1))
    }
}
