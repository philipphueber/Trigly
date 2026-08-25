package app.phueber.trigly.triggers

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The freshness window on the tap record, which is the whole correctness story
 * for `shortcut`'s cold-start path — the same story [BootEventsTest] tells for
 * `device_restart`, plus the one thing a boot never has to answer:
 * [ShortcutEvents.lastTapAtMillis], which [ShortcutTrigger] uses to tell "the
 * tap I already reported from [ShortcutEvents.pending]" apart from a genuinely
 * later one. [ShortcutEvents.pending] itself stays non-consuming — two rules
 * sharing a `shortcutId` must both be able to read the same tap — so the
 * "does not fire twice" guarantee this trigger needs lives in
 * `ShortcutTrigger`, keyed off [ShortcutEvents.lastTapAtMillis]; what belongs
 * here is proving that primitive actually distinguishes "the same tap" from
 * "a new one", which is all it is asked to do.
 */
class ShortcutEventsTest {

    @Before
    fun setUp() = ShortcutEvents.clear()

    @After
    fun tearDown() = ShortcutEvents.clear()

    @Test
    fun `with no tap recorded nothing is pending`() {
        assertFalse(ShortcutEvents.pending(nowMillis = 1_000, id = "abc"))
    }

    @Test
    fun `a tap just recorded is pending`() {
        ShortcutEvents.record("abc", atMillis = 1_000)

        assertTrue(ShortcutEvents.pending(nowMillis = 1_200, id = "abc"))
    }

    @Test
    fun `a tap older than the window is not pending`() {
        ShortcutEvents.record("abc", atMillis = 1_000)

        // The rule was enabled by hand long after the shortcut was tapped.
        assertFalse(
            ShortcutEvents.pending(
                nowMillis = 1_000 + ShortcutEvents.DEFAULT_WINDOW_MILLIS + 1,
                id = "abc",
            )
        )
    }

    @Test
    fun `the edge of the window still counts`() {
        ShortcutEvents.record("abc", atMillis = 1_000)

        assertTrue(
            ShortcutEvents.pending(
                nowMillis = 1_000 + ShortcutEvents.DEFAULT_WINDOW_MILLIS,
                id = "abc",
            )
        )
    }

    @Test
    fun `a tap for a different shortcut id is not pending`() {
        ShortcutEvents.record("abc", atMillis = 1_000)

        assertFalse(
            "a tap on shortcut abc must not fire a rule waiting on shortcut xyz",
            ShortcutEvents.pending(nowMillis = 1_100, id = "xyz"),
        )
    }

    @Test
    fun `reading does not consume, so two rules sharing an id both see the same tap`() {
        ShortcutEvents.record("abc", atMillis = 1_000)

        assertTrue(ShortcutEvents.pending(nowMillis = 1_100, id = "abc"))
        assertTrue(
            "a second rule sharing this shortcut must also fire",
            ShortcutEvents.pending(nowMillis = 1_100, id = "abc"),
        )
    }

    @Test
    fun `a clock that has gone backwards does not count as pending`() {
        ShortcutEvents.record("abc", atMillis = 5_000)

        // Negative age: the wall clock was corrected between the tap and the
        // read. Firing on a negative age would be firing for a tap that has
        // not happened yet.
        assertFalse(ShortcutEvents.pending(nowMillis = 4_000, id = "abc"))
    }

    @Test
    fun `lastTapAtMillis is null until this id has ever been tapped`() {
        ShortcutEvents.record("abc", atMillis = 1_000)

        assertNull(ShortcutEvents.lastTapAtMillis("xyz"))
    }

    @Test
    fun `lastTapAtMillis names the tap that pending answers for`() {
        ShortcutEvents.record("abc", atMillis = 1_000)

        // This is the primitive ShortcutTrigger uses to recognise a repeat of
        // the exact tap it already reported, rather than a later, distinct one
        // — see its class doc. It has to agree with pending() about which tap
        // is current, or the two would talk past each other.
        assertEquals(1_000L, ShortcutEvents.lastTapAtMillis("abc"))
    }

    @Test
    fun `a second, later tap changes lastTapAtMillis, so it is not mistaken for the first`() {
        ShortcutEvents.record("abc", atMillis = 1_000)
        ShortcutEvents.record("abc", atMillis = 2_000)

        assertEquals(2_000L, ShortcutEvents.lastTapAtMillis("abc"))
    }

    @Test
    fun `record publishes on the live bus for a trigger that is already collecting`() = runTest {
        ShortcutEvents.taps.events.test {
            ShortcutEvents.record("abc", atMillis = 1_000)

            assertEquals("abc", awaitItem())
        }
    }
}
