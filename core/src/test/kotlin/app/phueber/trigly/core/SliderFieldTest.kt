package app.phueber.trigly.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * [ConfigField.Slider] is the one field kind that refuses to be built wrong.
 *
 * The others tolerate a nonsensical declaration because a bad hint is only ugly.
 * A slider with no width, or a default outside its own scale, cannot be drawn at
 * all — the thumb has nowhere to go — so it fails where the mistake is, at the
 * factory that declared it, rather than at the screen that tries to render it.
 */
class SliderFieldTest {

    private fun slider(min: Long, max: Long, default: Long) = ConfigField.Slider(
        key = "volumePercent",
        label = "Volume",
        min = min,
        max = max,
        default = default,
    )

    @Test
    fun `a sane scale is accepted`() {
        val field = slider(min = 0, max = 100, default = 100)

        assertEquals(0L, field.min)
        assertEquals(100L, field.max)
        assertEquals(100L, field.default)
    }

    @Test
    fun `a scale needs width`() {
        val equal = assertThrows(IllegalArgumentException::class.java) {
            slider(min = 50, max = 50, default = 50)
        }
        assertEquals(true, equal.message!!.contains("must be below max"))

        assertThrows(IllegalArgumentException::class.java) {
            slider(min = 100, max = 0, default = 50)
        }
    }

    @Test
    fun `a default off the scale is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            slider(min = 0, max = 100, default = 101)
        }
        assertThrows(IllegalArgumentException::class.java) {
            slider(min = 0, max = 100, default = -1)
        }
    }

    @Test
    fun `the endpoints are valid defaults`() {
        // Zero volume is a real setting, not a missing one, so the bottom of the
        // scale has to be allowed as a starting position.
        assertEquals(0L, slider(0, 100, 0).default)
        assertEquals(100L, slider(0, 100, 100).default)
    }

    @Test
    fun `a slider is never required`() {
        // There is no such thing as an unset slider: it is drawn at a position
        // whether or not anyone has touched it, so demanding a value would be
        // demanding something the control cannot fail to provide.
        assertFalse(slider(0, 100, 50).required)
    }

    @Test
    fun `the editor starts a slider at its declared default`() {
        // Not at the minimum, which is the tempting shortcut: for volume that
        // would start every new alert silent.
        assertEquals("100", slider(0, 100, 100).defaultValue())
        assertEquals("30", slider(0, 100, 30).defaultValue())
    }
}
