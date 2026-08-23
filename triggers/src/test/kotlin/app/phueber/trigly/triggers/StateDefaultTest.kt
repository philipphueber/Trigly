package app.phueber.trigly.triggers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What happens to a rule saved before its trigger grew a choice.
 *
 * This is the whole reason [parseTargetOrDefault] exists. The Bluetooth trigger
 * could only ever fire on *connect*, so no rule saved before 0.0.3 has a `state`
 * key — and the strict [parseTarget] refuses an absent one, which would have
 * turned an app update into a set of rules that quietly stopped firing. There is
 * no louder failure than that in this app, because nothing reports it.
 */
class StateDefaultTest {

    private val key = "state"

    @Test
    fun `an absent state falls back rather than failing`() {
        assertTrue(
            parseTargetOrDefault(emptyMap(), key, "connected", "disconnected", default = true)
        )
        // And the fallback is the caller's, not a fixed one.
        assertFalse(
            parseTargetOrDefault(emptyMap(), key, "connected", "disconnected", default = false)
        )
    }

    @Test
    fun `a stored state is honoured over the default`() {
        val stored = mapOf(key to "disconnected")

        assertFalse(
            parseTargetOrDefault(stored, key, "connected", "disconnected", default = true)
        )
    }

    @Test
    fun `case is ignored, as the strict parser does`() {
        assertTrue(
            parseTargetOrDefault(
                mapOf(key to "CONNECTED"), key, "connected", "disconnected", default = false,
            )
        )
    }

    /**
     * The line this draws: absent is a rule written before the question existed,
     * a typo is a wrong answer to it. Guessing from a typo would pick silently
     * between "when it connects" and "when it disconnects".
     */
    @Test
    fun `an unknown state is still an error, not a default`() {
        val thrown = assertThrows(IllegalStateException::class.java) {
            parseTargetOrDefault(
                mapOf(key to "conected"), key, "connected", "disconnected", default = true,
            )
        }
        assertTrue(
            "the message should quote what was actually stored: ${thrown.message}",
            thrown.message.orEmpty().contains("conected"),
        )
    }

    @Test
    fun `the strict parser still refuses an absent state`() {
        assertThrows(IllegalStateException::class.java) {
            parseTarget(emptyMap(), key, "connected", "disconnected")
        }
    }

    /**
     * The concrete promise: a `bluetooth_connected` rule saved by 0.0.2, which
     * has no state key at all, still means connect.
     */
    @Test
    fun `a bluetooth rule saved before disconnection existed still means connect`() {
        val savedByOlderVersion = mapOf("address" to "00:11:22:33:44:55")

        assertEquals(
            true,
            parseTargetOrDefault(
                config = savedByOlderVersion,
                key = BluetoothConnectionTrigger.CONFIG_STATE,
                onWord = BluetoothConnectionTrigger.CONNECTED,
                offWord = BluetoothConnectionTrigger.DISCONNECTED,
                default = true,
            ),
        )
    }
}
