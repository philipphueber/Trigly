package app.phueber.trigly.triggers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ThresholdTest {

    @Test
    fun `below is inclusive at the threshold`() {
        assertEquals(STATE_MET, thresholdState(20, 20, Direction.BELOW))
        assertEquals(STATE_MET, thresholdState(19, 20, Direction.BELOW))
        assertEquals(STATE_UNMET, thresholdState(21, 20, Direction.BELOW))
    }

    @Test
    fun `above is inclusive at the threshold`() {
        assertEquals(STATE_MET, thresholdState(80, 80, Direction.ABOVE))
        assertEquals(STATE_MET, thresholdState(81, 80, Direction.ABOVE))
        assertEquals(STATE_UNMET, thresholdState(79, 80, Direction.ABOVE))
    }

    @Test
    fun `direction defaults to below when unset`() {
        assertEquals(Direction.BELOW, Direction.parse(null))
    }

    @Test
    fun `direction parsing is case insensitive`() {
        assertEquals(Direction.ABOVE, Direction.parse("ABOVE"))
        assertEquals(Direction.BELOW, Direction.parse("Below"))
    }

    @Test
    fun `an unrecognised direction fails loudly rather than defaulting`() {
        val error = assertThrows(IllegalStateException::class.java) {
            Direction.parse("sideways")
        }
        assertEquals(true, error.message?.contains("sideways"))
    }

    @Test
    fun `battery percent comes from the level and scale pair`() {
        assertEquals(50, batteryPercent(level = 50, scale = 100))
        assertEquals(25, batteryPercent(level = 1, scale = 4))
    }

    @Test
    fun `battery percent is null when the extras are missing`() {
        // getIntExtra defaults we use for "absent".
        assertEquals(null, batteryPercent(level = -1, scale = -1))
        assertEquals(null, batteryPercent(level = 50, scale = 0))
    }
}
