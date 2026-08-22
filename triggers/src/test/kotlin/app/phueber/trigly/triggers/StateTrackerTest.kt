package app.phueber.trigly.triggers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StateTrackerTest {

    @Test
    fun `a null key means every reading is an event`() {
        val tracker = StateTracker(suppressInitialState = false)

        assertTrue(tracker.accept(null))
        assertTrue(tracker.accept(null))
        assertTrue(tracker.accept(null))
    }

    @Test
    fun `repeated identical states collapse to one event`() {
        val tracker = StateTracker(suppressInitialState = false)

        assertTrue(tracker.accept("met"))
        assertFalse(tracker.accept("met"))
        assertFalse(tracker.accept("met"))
    }

    @Test
    fun `alternating states each produce an event`() {
        val tracker = StateTracker(suppressInitialState = false)

        assertTrue(tracker.accept("met"))
        assertTrue(tracker.accept("unmet"))
        assertTrue(tracker.accept("met"))
    }

    @Test
    fun `suppressing the initial state swallows a sticky broadcast replay`() {
        val tracker = StateTracker(suppressInitialState = true)

        // What a sticky broadcast delivers the instant we register.
        assertFalse(tracker.accept("plugged"))
        // Still the same state — nothing happened.
        assertFalse(tracker.accept("plugged"))
    }

    @Test
    fun `a suppressed initial state still arms the tracker for the next change`() {
        val tracker = StateTracker(suppressInitialState = true)

        assertFalse(tracker.accept("plugged"))
        assertTrue(tracker.accept("unplugged"))
        // And back again — this is the case that breaks if the suppressed
        // reading is not recorded.
        assertTrue(tracker.accept("plugged"))
    }

    @Test
    fun `suppression applies only to the very first reading`() {
        val tracker = StateTracker(suppressInitialState = true)

        assertFalse(tracker.accept("a"))
        assertTrue(tracker.accept("b"))
        assertFalse(tracker.accept("b"))
        assertTrue(tracker.accept("a"))
    }
}
