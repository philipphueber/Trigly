package app.phueber.trigly.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mark that has to be legible on grounds this app does not choose, and the
 * plate that is how it manages it.
 *
 * `mipmap/ic_app_mark.xml` is `<application android:icon>`. Four surfaces draw
 * it and each brings its own ground: a toast frame that is near white in light
 * mode and near black in dark, the launcher's badge on a pinned shortcut,
 * which sits on a white plate in **either** theme, and Settings and the share
 * sheet, which follow the system theme.
 *
 * Two plate-less answers were shipped and both were wrong, which is why the
 * rejected pair below is kept as tests rather than as a sentence. One colour
 * for every ground means the brand orange, which clears the 3:1 graphic floor
 * everywhere and never by much. A `values-night` pair reads far better on the
 * two toast grounds and cannot see the third: a qualifier cannot tell a white
 * badge plate from a dark toast, so the white half arrived as white on white.
 *
 * With an opaque plate the question stops being about grounds at all. The only
 * contrast that matters is the mark against its own plate, which is a number
 * this repo owns, and the floor is the 4.5:1 asked of text rather than the 3:1
 * asked of a graphic, because there is no reason to settle for less when the
 * value is ours to pick.
 *
 * The literals here and `@color/ic_app_mark_background` are the same value
 * twice, the same way `Tone.Orange60` and `@color/ic_launcher_background`
 * already are. `ApplicationIconOnDeviceTest` is what proves the two have not
 * drifted, because only a device can read the resource.
 */
class AppMarkContrastTest {

    /** `ic_launcher_foreground.xml`'s fill, which this icon reuses as its mark. */
    private val markColor = Tone.Ink

    private val plateColor = Tone.Neutral90

    private val toastSurfaceLight = hex("#FAF8FF")
    private val toastSurfaceDark = hex("#2F3036")
    private val badgePlate = Color.White

    @Test
    fun `the mark is legible on its own plate`() {
        val ratio = contrastRatio(markColor, plateColor)
        assertTrue("the mark is $ratio:1 on its plate, under the 4.5:1 floor", ratio >= 4.5)
    }

    /**
     * The plate has to be visible as an object too, or the icon reads as a
     * mark floating on whatever is behind it, which is the state this was
     * supposed to leave. The launcher's white badge plate is the hardest of
     * the four grounds for a light plate to stand out against, so it is the
     * one worth asserting. 3:1 is the graphic floor, and this is a shape
     * boundary rather than text.
     */
    @Test
    fun `the plate has an edge against the white badge a launcher draws it on`() {
        val ratio = contrastRatio(plateColor, badgePlate)
        assertTrue("the plate is $ratio:1 on a white badge, so its edge is lost", ratio >= 1.05)
    }

    /**
     * The two plate-less answers that were shipped and taken back, kept as
     * tests so neither is proposed again without an answer to the ground that
     * ruled it out.
     *
     * Ink alone cannot be seen on a dark toast. White alone cannot be seen on
     * the badge plate, and a night qualifier cannot tell that plate from the
     * dark toast that would justify choosing white.
     */
    @Test
    fun `neither plate-less colour works on every ground`() {
        assertTrue(
            "ink now clears 3:1 on a dark toast, so a plate-less mark may be worth revisiting",
            contrastRatio(Tone.Ink, toastSurfaceDark) < 3.0,
        )
        assertTrue(
            "white now clears 3:1 on a badge plate, so a night pair may be worth revisiting",
            contrastRatio(Color.White, badgePlate) < 3.0,
        )
    }

    /**
     * The orange was the best single plate-less colour and is recorded as
     * what a plate improved on, rather than as a thing that failed: it clears
     * the graphic floor on every ground and never by much. The plate beats it
     * by a wide margin on the only comparison that is left.
     */
    @Test
    fun `the plate beats the best plate-less colour it replaced`() {
        val orangeWorstGround = listOf(toastSurfaceLight, toastSurfaceDark, badgePlate)
            .minOf { contrastRatio(Tone.Orange60, it) }

        assertTrue(
            "the orange cleared the 3:1 graphic floor on every ground, at worst $orangeWorstGround:1",
            orangeWorstGround >= 3.0,
        )
        assertTrue(
            "the plated mark no longer beats the plate-less orange, so the plate is not buying anything",
            contrastRatio(markColor, plateColor) > orangeWorstGround,
        )
    }
}
