package app.phueber.trigly.ui

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.compose.ui.graphics.toArgb
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The application icon, which is the icon the toast draws and the one icon
 * that cannot follow the chosen colour scheme.
 *
 * [LauncherIconAliasOnDeviceTest] covers the icons that *do* follow it. This
 * covers the one that does not, and it exists because the reason it cannot is
 * invisible in the manifest: `<application android:icon>` looks exactly like
 * a launcher icon, an alias icon is what the launcher actually reads, and
 * pointing this attribute back at a coloured plate would look like a tidy-up
 * while quietly putting the wrong colour in every toast for eight of the nine
 * schemes. See `mipmap/ic_app_mark.xml` and `ToastAction`.
 *
 * [restoreOrange] runs after every test for the same reason it does in
 * [LauncherIconAliasOnDeviceTest]: an alias switch is real device state, and
 * a second run of this class must start where the first one did.
 */
@RunWith(AndroidJUnit4::class)
class ApplicationIconOnDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val packageManager = context.packageManager
    private val allIds = ColorPresets.map { it.id }
    private val enabler = PackageManagerComponentEnabler(context)

    private fun applicationIconRes(): Int =
        packageManager.getApplicationInfo(context.packageName, 0).icon

    @After
    fun restoreOrange() {
        switchLauncherIcon("orange", allIds, enabler)
    }

    @Test
    fun the_application_icon_is_the_plateless_mark() {
        assertEquals(R.mipmap.ic_app_mark, applicationIconRes())
    }

    /**
     * The whole finding, as one assertion: switching the scheme moves the
     * launcher icon and leaves the application icon exactly where it was, so
     * the toast cannot be made to follow the scheme by this route or any
     * other the app has.
     */
    @Test
    fun switching_the_scheme_does_not_move_the_application_icon() {
        val before = applicationIconRes()

        switchLauncherIcon("lime", allIds, enabler)

        assertEquals(before, applicationIconRes())
        assertEquals(R.mipmap.ic_app_mark, applicationIconRes())
    }

    /**
     * The other half of the manifest edit. Every alias must still name a
     * coloured icon of its own, or pointing `<application android:icon>` at
     * the plain mark would have taken the launcher icon down with it: an
     * activity with no icon of its own falls back to the application icon.
     */
    @Test
    fun every_alias_still_names_a_coloured_icon_of_its_own() {
        val appIcon = applicationIconRes()

        allIds.forEach { id ->
            val info = packageManager.getActivityInfo(
                aliasComponentName(context, id),
                PackageManager.MATCH_DISABLED_COMPONENTS,
            )
            assertNotEquals("the $id alias fell back to the application icon", appIcon, info.icon)
            assertTrue("the $id alias declares no icon", info.icon != 0)
        }
    }

    /**
     * Adaptive, with an opaque plate, and both halves of that matter.
     *
     * `IconDrawableFactory`, which is how SystemUI reaches this icon, sends an
     * adaptive drawable through `LauncherIcons.wrapIconDrawableWithShadow`,
     * which draws a blurred shadow of the icon *mask*. That is the correct
     * look for an icon that has a plate and the whole fault when it does not:
     * the plate-less version shipped in 0.3.2 was deliberately NOT adaptive
     * for exactly that reason, and this test asserted the opposite of what it
     * asserts now. Being adaptive is also what lets a launcher mask this icon
     * the way it masks every other one.
     */
    @Test
    fun the_application_icon_is_adaptive() {
        val icon = context.getDrawable(R.mipmap.ic_app_mark)

        assertTrue(
            "an adaptive icon is what gives the plate a mask and a shadow like every other app's",
            icon is AdaptiveIconDrawable,
        )
    }

    /**
     * The plate is opaque, checked by rendering rather than by reading the
     * XML: a corner well inside the adaptive canvas is fully opaque, and so is
     * the centre where the mark itself sits.
     *
     * An adaptive icon's outer edge is masked by the launcher, so the very
     * corner pixel proves nothing either way. What matters is that there is no
     * hole behind the mark, which is what let the ground underneath decide
     * whether the mark could be seen.
     */
    @Test
    fun the_application_icon_has_an_opaque_plate_behind_the_mark() {
        val size = 48
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val icon = requireNotNull(context.getDrawable(R.mipmap.ic_app_mark))
        icon.setBounds(0, 0, size, size)
        icon.draw(Canvas(bitmap))

        val inset = size / 4
        listOf(inset to inset, size - inset to inset, inset to size - inset).forEach { (x, y) ->
            assertEquals(
                "the plate has a hole in it at ($x, $y)",
                255,
                Color.alpha(bitmap.getPixel(x, y)),
            )
        }
        // The stem of the T runs through the middle of the board, so the
        // centre pixel is the one place the mark is guaranteed to be.
        assertEquals(255, Color.alpha(bitmap.getPixel(size / 2, size / 2)))
    }

    /**
     * The plate's colour is a literal in `values/colors.xml` and the same
     * literal again in `Tone.Neutral90`, the way `ic_launcher_background`
     * already is. `AppMarkContrastTest` checks the contrast of the Kotlin
     * one; only a device can read the resource, so this is where the two are
     * held together.
     *
     * One value, with no night branch to choose between. A `values-night`
     * pair was shipped once and taken back out: the launcher badges a pinned
     * shortcut with this icon on a white plate in either theme, so the white
     * half of the pair was invisible in dark mode. See
     * `mipmap/ic_app_mark.xml`.
     */
    @Test
    fun the_plate_colour_is_the_neutral_from_the_palette() {
        assertEquals(
            Tone.Neutral90.toArgb(),
            context.getColor(R.color.ic_app_mark_background),
        )
    }
}
