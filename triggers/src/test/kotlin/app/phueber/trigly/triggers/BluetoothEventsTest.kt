package app.phueber.trigly.triggers

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The two things that make [BluetoothEvents] a correct ingress rather than
 * merely a working one: the freshness window on [BluetoothEvents.pending],
 * which is the same story [BootEventsTest] and [ShortcutEventsTest] tell for
 * their own records, and the duplicate window on [BluetoothEvents.record],
 * which neither of those has to have because neither of their sources is
 * known to resend the same edge twice.
 *
 * What is *not* tested here is the exactly-once guarantee across a cold and a
 * warm collection together. That guard lives in
 * [BluetoothConnectionTrigger], keyed off the timestamp this object hands
 * back, the same way [ShortcutTrigger] is keyed off
 * [ShortcutEvents.lastTapAtMillis]. What belongs here is proving the
 * primitives that guard depends on: a sighting is not silently dropped by
 * [pending]'s window when it should not be, and a genuine resend is dropped by
 * [record]'s window before it ever reaches a trigger at all.
 */
class BluetoothEventsTest {

    @Before
    fun setUp() = BluetoothEvents.clear()

    @After
    fun tearDown() = BluetoothEvents.clear()

    @Test
    fun `with nothing recorded nothing is pending`() {
        assertNull(BluetoothEvents.pending(nowMillis = 1_000))
    }

    @Test
    fun `a sighting just recorded is pending`() {
        BluetoothEvents.record(BluetoothEvents.Action.CONNECTED, "AA:BB", "Speaker", atMillis = 1_000)

        val sighting = BluetoothEvents.pending(nowMillis = 1_200)

        assertEquals(BluetoothEvents.Action.CONNECTED, sighting?.action)
        assertEquals("AA:BB", sighting?.address)
        assertEquals("Speaker", sighting?.name)
    }

    @Test
    fun `a sighting older than the window is not pending`() {
        BluetoothEvents.record(BluetoothEvents.Action.CONNECTED, "AA:BB", null, atMillis = 1_000)

        // The rule was enabled by hand long after the connect finished.
        assertNull(
            BluetoothEvents.pending(
                nowMillis = 1_000 + BluetoothEvents.DEFAULT_WINDOW_MILLIS + 1,
            )
        )
    }

    @Test
    fun `the edge of the window still counts`() {
        BluetoothEvents.record(BluetoothEvents.Action.CONNECTED, "AA:BB", null, atMillis = 1_000)

        assertEquals(
            1_000L,
            BluetoothEvents.pending(nowMillis = 1_000 + BluetoothEvents.DEFAULT_WINDOW_MILLIS)?.atMillis,
        )
    }

    @Test
    fun `reading does not consume, so two rules both see the same sighting`() {
        BluetoothEvents.record(BluetoothEvents.Action.CONNECTED, "AA:BB", null, atMillis = 1_000)

        assertEquals(1_000L, BluetoothEvents.pending(nowMillis = 1_100)?.atMillis)
        assertEquals(
            "a second rule watching the same device must also see it",
            1_000L,
            BluetoothEvents.pending(nowMillis = 1_100)?.atMillis,
        )
    }

    @Test
    fun `a clock that has gone backwards does not count as pending`() {
        BluetoothEvents.record(BluetoothEvents.Action.CONNECTED, "AA:BB", null, atMillis = 5_000)

        assertNull(BluetoothEvents.pending(nowMillis = 4_000))
    }

    @Test
    fun `a repeat of the same edge for the same device within the duplicate window updates nothing`() {
        BluetoothEvents.record(BluetoothEvents.Action.DISCONNECTED, "AA:BB", "Earbuds", atMillis = 1_000)

        // The known real-world case: some accessories send a second
        // ACL_DISCONNECTED for the same disconnect several seconds later.
        BluetoothEvents.record(BluetoothEvents.Action.DISCONNECTED, "AA:BB", "Earbuds", atMillis = 10_000)

        assertEquals(
            "the repeat must not become the new pending sighting",
            1_000L,
            BluetoothEvents.pending(nowMillis = 10_100)?.atMillis,
        )
    }

    @Test
    fun `the address comparison for a duplicate ignores case, matching how Android reports it`() {
        BluetoothEvents.record(BluetoothEvents.Action.CONNECTED, "aa:bb", "Speaker", atMillis = 1_000)
        BluetoothEvents.record(BluetoothEvents.Action.CONNECTED, "AA:BB", "Speaker", atMillis = 2_000)

        assertEquals(1_000L, BluetoothEvents.pending(nowMillis = 2_100)?.atMillis)
    }

    @Test
    fun `a repeat past the duplicate window is a new sighting`() {
        BluetoothEvents.record(BluetoothEvents.Action.DISCONNECTED, "AA:BB", "Earbuds", atMillis = 1_000)

        BluetoothEvents.record(
            BluetoothEvents.Action.DISCONNECTED,
            "AA:BB",
            "Earbuds",
            atMillis = 1_000 + BluetoothEvents.DUPLICATE_WINDOW_MILLIS + 1,
        )

        assertEquals(
            1_000 + BluetoothEvents.DUPLICATE_WINDOW_MILLIS + 1,
            BluetoothEvents.pending(nowMillis = 1_000 + BluetoothEvents.DUPLICATE_WINDOW_MILLIS + 100)?.atMillis,
        )
    }

    @Test
    fun `a different device connecting is never a duplicate, however soon after`() {
        BluetoothEvents.record(BluetoothEvents.Action.CONNECTED, "AA:BB", "Speaker", atMillis = 1_000)
        BluetoothEvents.record(BluetoothEvents.Action.CONNECTED, "CC:DD", "Watch", atMillis = 1_001)

        val sighting = BluetoothEvents.pending(nowMillis = 1_100)

        assertEquals("CC:DD", sighting?.address)
    }

    @Test
    fun `a disconnect is never a duplicate of a connect for the same device`() {
        BluetoothEvents.record(BluetoothEvents.Action.CONNECTED, "AA:BB", "Speaker", atMillis = 1_000)
        BluetoothEvents.record(BluetoothEvents.Action.DISCONNECTED, "AA:BB", "Speaker", atMillis = 1_001)

        val sighting = BluetoothEvents.pending(nowMillis = 1_100)

        // The reconnect-cancels-disconnect debounce depends on both edges for
        // the same device reaching a collecting trigger; deduplicating across
        // actions would break it.
        assertEquals(BluetoothEvents.Action.DISCONNECTED, sighting?.action)
    }

    @Test
    fun `record publishes on the live bus for a trigger that is already collecting`() = runTest {
        BluetoothEvents.sightings.events.test {
            BluetoothEvents.record(BluetoothEvents.Action.CONNECTED, "AA:BB", "Speaker", atMillis = 1_000)

            val sighting = awaitItem()
            assertEquals(BluetoothEvents.Action.CONNECTED, sighting.action)
            assertEquals("AA:BB", sighting.address)
        }
    }

    @Test
    fun `a duplicate is not published on the live bus either`() = runTest {
        BluetoothEvents.record(BluetoothEvents.Action.DISCONNECTED, "AA:BB", "Earbuds", atMillis = 1_000)

        BluetoothEvents.sightings.events.test {
            BluetoothEvents.record(BluetoothEvents.Action.DISCONNECTED, "AA:BB", "Earbuds", atMillis = 1_500)
            // A collecting trigger must not see the swallowed repeat. A later,
            // genuinely new sighting proves the bus itself is still live.
            BluetoothEvents.record(BluetoothEvents.Action.CONNECTED, "AA:BB", "Earbuds", atMillis = 1_600)

            val sighting = awaitItem()
            assertEquals(BluetoothEvents.Action.CONNECTED, sighting.action)
        }
    }

    /**
     * The case a field-by-field comparison got wrong. A repeat broadcast does
     * not have to carry the same fields as the first: the stack may not have
     * resolved the device's name when it sends the first one, and may have it a
     * few seconds later. The address is the identity, so this is one connect
     * reported twice, not two connects.
     */
    @Test
    fun `a repeat that resolves the name late is still a repeat`() {
        BluetoothEvents.record(
            BluetoothEvents.Action.CONNECTED,
            address = "AA:BB:CC:DD:EE:FF",
            name = null,
            atMillis = 1_000L,
        )
        BluetoothEvents.record(
            BluetoothEvents.Action.CONNECTED,
            address = "aa:bb:cc:dd:ee:ff",
            name = "Car",
            atMillis = 4_000L,
        )

        val pending = BluetoothEvents.pending(nowMillis = 4_100L)
        assertEquals(1_000L, pending?.atMillis)
        assertNull("the late name must not replace the first sighting", pending?.name)
    }
}
