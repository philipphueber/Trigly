package app.phueber.trigly.ui

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule as JUnitRule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `resolvedColors` is a plain function with its own JVM tests in
 * `ColorSchemeChoiceTest`; what only a device can show is that [TriglyTheme]
 * actually renders what it returns, and that "Follow the system" only ever
 * takes effect where the platform can honour it. See `CLAUDE.md` for why this
 * needs at least two API levels: [switching_to_a_preset_changes_the_rendered_primary]
 * runs everywhere, the other two are each gated to one side of API 31 with
 * [assumeTrue] and skip rather than fail on the wrong device.
 */
@RunWith(AndroidJUnit4::class)
class TriglyThemeColorSchemeTest {

    @get:JUnitRule
    val composeRule = createComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private var observedPrimary: Color = Color.Unspecified

    @Composable
    private fun Probe(colorScheme: ColorScheme, extraColors: TriglyExtraColors) {
        TriglyTheme(colorScheme = colorScheme, extraColors = extraColors) {
            observedPrimary = MaterialTheme.colorScheme.primary
        }
    }

    @Test
    fun switching_to_a_preset_changes_the_rendered_primary() {
        val default = resolvedColors(context, ColorSchemeChoice.Default, darkTheme = false)
        val preset = resolvedColors(context, ColorSchemeChoice.Preset(ColorPresets[1].id), darkTheme = false)

        composeRule.setContent { Probe(default.colorScheme, default.extra) }
        val defaultPrimary = observedPrimary

        composeRule.setContent { Probe(preset.colorScheme, preset.extra) }
        val presetPrimary = observedPrimary

        assertNotEquals(defaultPrimary, presetPrimary)
        assertEquals(ColorPresets[1].light.primary, presetPrimary)
    }

    @Test
    fun switching_to_follow_the_system_renders_the_dynamic_scheme_from_api_31() {
        assumeTrue("dynamic colour needs API 31", Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)

        val system = resolvedColors(context, ColorSchemeChoice.System, darkTheme = false)
        composeRule.setContent { Probe(system.colorScheme, system.extra) }

        // Equal to what dynamicLightColorScheme itself produced, not to
        // Default's fixed orange - the two coinciding on this particular
        // wallpaper is not the failure this guards against, but resolving
        // through the Default branch instead of the System one is.
        assertEquals(system.colorScheme.primary, observedPrimary)
    }

    @Test
    fun below_api_31_the_system_choice_renders_default_and_is_not_offered() {
        assumeTrue("this is the below-31 fallback path", Build.VERSION.SDK_INT < Build.VERSION_CODES.S)

        val system = resolvedColors(context, ColorSchemeChoice.System, darkTheme = false)
        val default = resolvedColors(context, ColorSchemeChoice.Default, darkTheme = false)
        assertEquals(default.colorScheme.primary, system.colorScheme.primary)

        // Absent, not merely disabled - see ColorSchemePickerDialog's own KDoc.
        composeRule.setContent {
            ColorSchemePickerDialog(current = ColorSchemeChoice.Default, onPick = {}, onDismiss = {})
        }
        composeRule.onNodeWithText("Follow the system".uppercase()).assertDoesNotExist()
    }
}
