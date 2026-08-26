package app.phueber.trigly.triggers

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The freshness window on the durable-wake record, the same shape
 * [BootEventsTest] proves for [BootEvents] and for the same reason: the
 * record has to outlive the moment it is written in, but not by so long
 * that a rule enabled by hand long after some unrelated durable wait fired
 * borrows that wake as its own.
 *
 * Unlike [BootEvents], there is no reason dimension here; see
 * [AlarmWakeEvents]'s own KDoc for why one durable wake firing is enough
 * for any trigger's fresh collection to treat itself as caught up.
 */
class AlarmWakeEventsTest {

    @Before
    fun setUp() = AlarmWakeEvents.clear()

    @After
    fun tearDown() = AlarmWakeEvents.clear()

    @Test
    fun `with no wake recorded nothing is pending`() {
        assertFalse(AlarmWakeEvents.pending(nowMillis = 1_000))
    }

    @Test
    fun `a wake just recorded is pending`() {
        AlarmWakeEvents.record(atMillis = 1_000)

        assertTrue(AlarmWakeEvents.pending(nowMillis = 1_200))
    }

    @Test
    fun `a wake older than the window is not pending`() {
        AlarmWakeEvents.record(atMillis = 1_000)

        // The rule was enabled by hand long after some other rule's durable
        // wait fired.
        assertFalse(
            AlarmWakeEvents.pending(nowMillis = 1_000 + AlarmWakeEvents.DEFAULT_WINDOW_MILLIS + 1)
        )
    }

    @Test
    fun `the edge of the window still counts`() {
        AlarmWakeEvents.record(atMillis = 1_000)

        assertTrue(AlarmWakeEvents.pending(nowMillis = 1_000 + AlarmWakeEvents.DEFAULT_WINDOW_MILLIS))
    }

    @Test
    fun `reading does not consume, so two rules both see the same wake`() {
        AlarmWakeEvents.record(atMillis = 1_000)

        assertTrue(AlarmWakeEvents.pending(nowMillis = 1_100))
        assertTrue(
            "a second rule's fresh collection must also see it",
            AlarmWakeEvents.pending(nowMillis = 1_100),
        )
    }

    @Test
    fun `a clock that has gone backwards does not count as pending`() {
        AlarmWakeEvents.record(atMillis = 5_000)

        assertFalse(AlarmWakeEvents.pending(nowMillis = 4_000))
    }
}
