package app.phueber.trigly.triggers

import android.os.BatteryManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decoding `EXTRA_PLUGGED`, which is the one place this trigger can be wrong in a
 * way nobody would notice: a rule set to "mains" that quietly never fires on a
 * device reporting the flag differently.
 *
 * Constants only, so it runs on the JVM — `BatteryManager`'s `BATTERY_PLUGGED_*`
 * are compile-time ints, not Android behaviour.
 */
class ChargingSourceTest {

    @Test
    fun `each plug kind decodes to itself`() {
        assertEquals(
            ChargingSource.AC,
            ChargingSource.ofPluggedValue(BatteryManager.BATTERY_PLUGGED_AC),
        )
        assertEquals(
            ChargingSource.USB,
            ChargingSource.ofPluggedValue(BatteryManager.BATTERY_PLUGGED_USB),
        )
        assertEquals(
            ChargingSource.WIRELESS,
            ChargingSource.ofPluggedValue(BatteryManager.BATTERY_PLUGGED_WIRELESS),
        )
    }

    @Test
    fun `zero is unplugged, not a charger kind`() {
        // The framework says "not plugged in" with 0. Decoding that as any kind of
        // charger would make an unplug look like a plug.
        assertNull(ChargingSource.ofPluggedValue(0))
    }

    @Test
    fun `two flags at once resolve rather than reporting unplugged`() {
        // EXTRA_PLUGGED is documented as flags. Equality matching would fall
        // through to "unplugged" here, which is the silent-wrong-answer case.
        val both = BatteryManager.BATTERY_PLUGGED_AC or BatteryManager.BATTERY_PLUGGED_USB

        assertEquals(ChargingSource.AC, ChargingSource.ofPluggedValue(both))
    }

    @Test
    fun `an unknown future flag reads as unplugged rather than guessing`() {
        // A bit none of the known constants covers. Null is the honest answer: it
        // means "no rule of ours matches", and no rule fires.
        assertNull(ChargingSource.ofPluggedValue(1 shl 20))
    }

    @Test
    fun `config values parse in any case`() {
        assertEquals(ChargingSource.AC, ChargingSource.parse("ac"))
        assertEquals(ChargingSource.USB, ChargingSource.parse("USB"))
        assertEquals(ChargingSource.WIRELESS, ChargingSource.parse("wireless"))
    }

    @Test
    fun `an unknown config value is refused with the offending text`() {
        val thrown = assertThrows(IllegalStateException::class.java) {
            ChargingSource.parse("solar")
        }
        assertTrue(thrown.message.orEmpty().contains("solar"))
    }
}
