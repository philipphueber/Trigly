package app.phueber.trigly.ui

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ColorSchemeChoice.fromStoredName] is what makes the store a name rather
 * than a colour safe to restore from an older or a newer build; [effectiveChoice]
 * is what keeps "Follow the system" from being offered on a phone that cannot
 * honour it. Both are plain functions for exactly this reason: neither needs
 * a device or Robolectric to test.
 */
class ColorSchemeChoiceTest {

    @Test
    fun `a null name is Default`() {
        assertEquals(ColorSchemeChoice.Default, ColorSchemeChoice.fromStoredName(null))
    }

    @Test
    fun `the default name round trips`() {
        assertEquals(ColorSchemeChoice.Default, ColorSchemeChoice.fromStoredName("default"))
        assertEquals("default", ColorSchemeChoice.Default.storedName)
    }

    @Test
    fun `the system name round trips`() {
        assertEquals(ColorSchemeChoice.System, ColorSchemeChoice.fromStoredName("system"))
        assertEquals("system", ColorSchemeChoice.System.storedName)
    }

    @Test
    fun `a known preset id round trips`() {
        val id = ColorPresets.first().id
        assertEquals(ColorSchemeChoice.Preset(id), ColorSchemeChoice.fromStoredName(id))
        assertEquals(id, ColorSchemeChoice.Preset(id).storedName)
    }

    @Test
    fun `an unknown name falls back to Default`() {
        // The case a restore from a newer build, or an older build's stray
        // value, actually produces: a name nothing here recognises.
        assertEquals(ColorSchemeChoice.Default, ColorSchemeChoice.fromStoredName("plaid"))
    }

    @Test
    fun `blank is not treated as a known name`() {
        assertEquals(ColorSchemeChoice.Default, ColorSchemeChoice.fromStoredName(""))
    }

    @Test
    fun `System survives at and above API 31`() {
        assertEquals(
            ColorSchemeChoice.System,
            effectiveChoice(ColorSchemeChoice.System, Build.VERSION_CODES.S),
        )
        assertEquals(
            ColorSchemeChoice.System,
            effectiveChoice(ColorSchemeChoice.System, Build.VERSION_CODES.S + 1),
        )
    }

    @Test
    fun `System falls back to Default below API 31`() {
        assertEquals(
            ColorSchemeChoice.Default,
            effectiveChoice(ColorSchemeChoice.System, Build.VERSION_CODES.S - 1),
        )
        assertEquals(
            ColorSchemeChoice.Default,
            effectiveChoice(ColorSchemeChoice.System, Build.VERSION_CODES.R),
        )
    }

    @Test
    fun `Default and a preset are unaffected by the SDK level`() {
        assertEquals(
            ColorSchemeChoice.Default,
            effectiveChoice(ColorSchemeChoice.Default, Build.VERSION_CODES.BASE),
        )
        val preset = ColorSchemeChoice.Preset(ColorPresets.first().id)
        assertEquals(preset, effectiveChoice(preset, Build.VERSION_CODES.BASE))
        assertEquals(preset, effectiveChoice(preset, Build.VERSION_CODES.S + 5))
    }
}
