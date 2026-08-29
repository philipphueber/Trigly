package app.phueber.trigly.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Walks every [ColorPreset], in both modes, and asserts every key
 * foreground/background pairing clears WCAG AA (4.5:1) - `onPrimary` on
 * `primary` first, because that is the one role `PresetSchemes.kt` hand-picks
 * per preset between ink and white.
 *
 * Written before the five new ramps were pasted into `PresetSchemes.kt`, on
 * purpose. The literals are machine-produced - see that file's own banner for
 * how - so the realistic failure is a wrong paste, not a wrong formula, and
 * this is what catches one before it ships.
 */
class ColorPresetContrastTest {

    private fun linearChannel(c: Float): Double {
        val cs = c.toDouble()
        return if (cs <= 0.03928) cs / 12.92 else ((cs + 0.055) / 1.055).pow(2.4)
    }

    private fun relativeLuminance(color: Color): Double =
        0.2126 * linearChannel(color.red) + 0.7152 * linearChannel(color.green) + 0.0722 * linearChannel(color.blue)

    private fun contrastRatio(a: Color, b: Color): Double {
        val (lighter, darker) = relativeLuminance(a).let { la ->
            val lb = relativeLuminance(b)
            if (la >= lb) la to lb else lb to la
        }
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun assertAA(label: String, foreground: Color, background: Color) {
        val ratio = contrastRatio(foreground, background)
        assertTrue("$label is $ratio:1, under the 4.5:1 WCAG AA floor", ratio >= 4.5)
    }

    private fun assertSchemeClearsAA(label: String, scheme: ColorScheme) {
        assertAA("$label onPrimary/primary", scheme.onPrimary, scheme.primary)
        assertAA("$label onPrimaryContainer/primaryContainer", scheme.onPrimaryContainer, scheme.primaryContainer)
        assertAA("$label onSecondary/secondary", scheme.onSecondary, scheme.secondary)
        assertAA(
            "$label onSecondaryContainer/secondaryContainer",
            scheme.onSecondaryContainer,
            scheme.secondaryContainer,
        )
        assertAA("$label onTertiary/tertiary", scheme.onTertiary, scheme.tertiary)
        assertAA(
            "$label onTertiaryContainer/tertiaryContainer",
            scheme.onTertiaryContainer,
            scheme.tertiaryContainer,
        )
        assertAA("$label onError/error", scheme.onError, scheme.error)
        assertAA("$label onErrorContainer/errorContainer", scheme.onErrorContainer, scheme.errorContainer)
        assertAA("$label onBackground/background", scheme.onBackground, scheme.background)
        assertAA("$label onSurface/surface", scheme.onSurface, scheme.surface)
        assertAA("$label onSurfaceVariant/surfaceVariant", scheme.onSurfaceVariant, scheme.surfaceVariant)
    }

    @Test
    fun `every preset clears WCAG AA in both modes`() {
        ColorPresets.forEach { preset ->
            assertSchemeClearsAA("${preset.id} light", preset.light)
            assertSchemeClearsAA("${preset.id} dark", preset.dark)
        }
    }

    @Test
    fun `every preset's accent clears WCAG AA against the page it writes on`() {
        ColorPresets.forEach { preset ->
            assertAA("${preset.id} accentLight/Paper", preset.accentLight, Tone.Paper)
            assertAA("${preset.id} accentDark/Ink", preset.accentDark, Tone.Ink)
        }
    }

    /**
     * Not a WCAG check: a preset that quietly shares a hue with another one
     * would defeat the picker's whole point of six distinguishable swatches.
     */
    @Test
    fun `every preset's primary is a distinct colour`() {
        val primaries = ColorPresets.map { it.light.primary }
        assertTrue("two presets share a primary: $primaries", primaries.toSet().size == primaries.size)
    }
}
