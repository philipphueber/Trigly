package app.phueber.trigly.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one colour in this app that has to work on a background the app does
 * not own.
 *
 * `mipmap/ic_app_mark.xml` is `<application android:icon>`, which is what
 * Android 12 and later draw inside every toast. The toast frame is the
 * platform's `?android:attr/colorSurface`, so the mark lands on a near-white
 * ground in light mode and a near-black one in dark. It is a `values-night`
 * pair for exactly that reason: ink on the light ground, white on the dark
 * one, each right on the ground it is drawn on.
 *
 * The floor is 3:1, not the 4.5:1 [ColorPresetContrastTest] holds text to.
 * The mark is a graphic, and 3:1 is what WCAG asks of a graphic whose shape
 * carries the meaning. Both halves of the pair clear it several times over,
 * which is the point of the pair.
 *
 * The two backgrounds are the platform's own values, read out of
 * `platforms/android-35/data/res/values/colors.xml`: `colorSurface` resolves
 * to `system_surface_light` in a light DeviceDefault theme and to
 * `system_neutral1_800` in a dark one. Material You moves both with the
 * wallpaper, but only along the same ramp, so these two stay the honest
 * representatives of "the lightest surface" and "the darkest surface".
 *
 * The literals here and `@color/ic_app_mark_foreground` are the same values
 * twice, the same way `Tone.Orange60` and `@color/ic_launcher_background`
 * already are. `ApplicationIconOnDeviceTest` is what proves the two have not
 * drifted, because only a device can read the resource.
 */
class AppMarkContrastTest {

    private val markLight = Tone.Ink
    private val markDark = Color.White

    private val toastSurfaceLight = hex("#FAF8FF")
    private val toastSurfaceDark = hex("#2F3036")

    private fun assertGraphicContrast(label: String, foreground: Color, background: Color) {
        val ratio = contrastRatio(foreground, background)
        assertTrue("$label is $ratio:1, under the 3:1 floor for a graphic", ratio >= 3.0)
    }

    @Test
    fun `the app mark is legible on a light toast`() {
        assertGraphicContrast("the ink mark on the light toast surface", markLight, toastSurfaceLight)
    }

    @Test
    fun `the app mark is legible on a dark toast`() {
        assertGraphicContrast("the white mark on the dark toast surface", markDark, toastSurfaceDark)
    }

    /**
     * What the night pair bought, kept as a test so the reason survives the
     * decision.
     *
     * One colour for both grounds was the first answer, and the best single
     * colour available was the brand orange: over the 3:1 floor on each
     * ground, and no more than that on either. Each half of the pair beats it
     * on the ground it serves by a wide margin. If a future change goes back
     * to one colour, this is the bar it has to argue against.
     */
    @Test
    fun `each half of the pair beats the best single colour on its own ground`() {
        val singleColour = Tone.Orange60
        assertTrue(
            "ink no longer beats the orange on a light toast",
            contrastRatio(markLight, toastSurfaceLight) > contrastRatio(singleColour, toastSurfaceLight),
        )
        assertTrue(
            "white no longer beats the orange on a dark toast",
            contrastRatio(markDark, toastSurfaceDark) > contrastRatio(singleColour, toastSurfaceDark),
        )
    }

    /**
     * The mark also appears in Settings and in the app info screen. Those are
     * the platform's surfaces too and follow the same system dark mode, so
     * each half meets the ground its own qualifier selects, and neither is
     * ever drawn on the other's.
     */
    @Test
    fun `each half of the pair is legible on the app's own page in the same mode`() {
        assertGraphicContrast("the ink mark on Paper", markLight, Tone.Paper)
        assertGraphicContrast("the white mark on Ink", markDark, Tone.Ink)
    }

    /**
     * The third background, and the one nobody chose. Android 12 and 12L load
     * this icon through the launcher's `IconFactory`, which shrinks a
     * non-adaptive icon onto a plain white wrapper.
     *
     * In light mode the ink mark is fine on it. In dark mode the white mark is
     * white on white and cannot be seen at all. That is a real cost and it is
     * asserted rather than described, so that nobody discovers it as a
     * surprise: it is bounded to two OS releases, neither of which is a gate
     * level, and what is lost is decoration on a toast that still carries its
     * text. See `mipmap/ic_app_mark.xml` for the trade in full.
     */
    @Test
    fun `the light mark survives the Android 12 plate and the dark one does not`() {
        assertGraphicContrast("the ink mark on the Android 12 wrapper", markLight, Color.White)
        assertTrue(
            "the white mark now shows on the Android 12 white plate, so the note about it is stale",
            contrastRatio(markDark, Color.White) < 3.0,
        )
    }
}
