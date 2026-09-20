package app.phueber.trigly.ui

import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/**
 * WCAG contrast, in one place because two test classes now ask for it.
 *
 * [ColorPresetContrastTest] asks it of every preset against the 4.5:1 text
 * floor, and [AppMarkContrastTest] asks it of the plateless app mark against
 * the 3:1 floor for a graphic. The formula is the same one either way, and a
 * second copy of it would be a second place for a wrong exponent to hide.
 *
 * Test source rather than production source on purpose: nothing the app ships
 * computes a contrast ratio. Every colour in `Palette.kt` and
 * `PresetSchemes.kt` is a literal that was checked once, and this is what
 * checks them.
 */
private fun linearChannel(c: Float): Double {
    val cs = c.toDouble()
    return if (cs <= 0.03928) cs / 12.92 else ((cs + 0.055) / 1.055).pow(2.4)
}

internal fun relativeLuminance(color: Color): Double =
    0.2126 * linearChannel(color.red) + 0.7152 * linearChannel(color.green) + 0.0722 * linearChannel(color.blue)

internal fun contrastRatio(a: Color, b: Color): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    val lighter = maxOf(la, lb)
    val darker = minOf(la, lb)
    return (lighter + 0.05) / (darker + 0.05)
}
