package app.phueber.trigly.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Colours Material 3 has no role for.
 *
 * A component *warning* — "this polls, so it costs battery", "Android 12
 * suppresses these in the background" — is not an error. The rule is valid and
 * will save; the user is being told something they need to know. Drawing it in
 * `colorScheme.error` says "you have done something wrong", which is both untrue
 * and, once every second trigger carries one, noise that trains people to ignore
 * red. So warnings get their own amber, and red is kept for things that actually
 * failed: a refused save, a permission that is missing.
 */
@Immutable
data class TriglyExtraColors(
    /** Text and icon colour for a caution. */
    val caution: Color,
    /** Background for a caution that needs to be a block rather than a line. */
    val cautionContainer: Color,
    val onCautionContainer: Color,
)

private val LightExtraColors = TriglyExtraColors(
    caution = Tone.Amber40,
    cautionContainer = Tone.Amber95,
    onCautionContainer = Tone.Amber20,
)

private val DarkExtraColors = TriglyExtraColors(
    caution = Tone.Amber80,
    cautionContainer = Tone.Amber30,
    onCautionContainer = Tone.Amber90,
)

private val LocalExtraColors = staticCompositionLocalOf { LightExtraColors }

/** `MaterialTheme.extra.caution`, alongside `MaterialTheme.colorScheme.error`. */
val MaterialTheme.extra: TriglyExtraColors
    @Composable @ReadOnlyComposable get() = LocalExtraColors.current

private val LightScheme = lightColorScheme(
    primary = Tone.Orange40,
    onPrimary = Tone.Neutral100,
    primaryContainer = Tone.Orange90,
    onPrimaryContainer = Tone.Orange10,
    secondary = Tone.Warm40,
    onSecondary = Tone.Neutral100,
    secondaryContainer = Tone.Warm90,
    onSecondaryContainer = Tone.Warm10,
    tertiary = Tone.Olive40,
    onTertiary = Tone.Neutral100,
    tertiaryContainer = Tone.Olive90,
    onTertiaryContainer = Tone.Olive10,
    error = Tone.Red40,
    onError = Tone.Neutral100,
    errorContainer = Tone.Red90,
    onErrorContainer = Tone.Red10,
    background = Tone.Neutral98,
    onBackground = Tone.Neutral10,
    surface = Tone.Neutral98,
    onSurface = Tone.Neutral10,
    surfaceVariant = Tone.NeutralVariant90,
    onSurfaceVariant = Tone.NeutralVariant30,
    surfaceContainerLowest = Tone.Neutral100,
    surfaceContainerLow = Tone.Neutral96,
    surfaceContainer = Tone.Neutral94,
    surfaceContainerHigh = Color(0xFFF6E7DF),
    surfaceContainerHighest = Color(0xFFF0E1D9),
    outline = Tone.NeutralVariant50,
    outlineVariant = Tone.NeutralVariant80,
    inverseSurface = Tone.Neutral20,
    inverseOnSurface = Tone.Neutral96,
    inversePrimary = Tone.Orange80,
)

private val DarkScheme = darkColorScheme(
    primary = Tone.Orange80,
    onPrimary = Tone.Orange20,
    primaryContainer = Tone.Orange30,
    onPrimaryContainer = Tone.Orange90,
    secondary = Tone.Warm80,
    onSecondary = Tone.Warm20,
    secondaryContainer = Tone.Warm30,
    onSecondaryContainer = Tone.Warm90,
    tertiary = Tone.Olive80,
    onTertiary = Tone.Olive20,
    tertiaryContainer = Tone.Olive30,
    onTertiaryContainer = Tone.Olive90,
    error = Tone.Red80,
    onError = Tone.Red20,
    errorContainer = Tone.Red30,
    onErrorContainer = Tone.Red90,
    background = Tone.Neutral6,
    onBackground = Tone.Neutral90,
    surface = Tone.Neutral6,
    onSurface = Tone.Neutral90,
    surfaceVariant = Tone.NeutralVariant30,
    onSurfaceVariant = Tone.NeutralVariant80,
    surfaceContainerLowest = Color(0xFF120C08),
    surfaceContainerLow = Tone.Neutral10,
    surfaceContainer = Tone.Neutral12,
    surfaceContainerHigh = Tone.Neutral17,
    surfaceContainerHighest = Tone.Neutral22,
    outline = Tone.NeutralVariant60,
    outlineVariant = Tone.NeutralVariant30,
    inverseSurface = Tone.Neutral90,
    inverseOnSurface = Tone.Neutral20,
    inversePrimary = Tone.Orange40,
)

/**
 * The app's theme.
 *
 * **No dynamic colour.** Material You would replace the orange with tones pulled
 * from the user's wallpaper, which is a good default for an app with no colour
 * of its own and the wrong one here — the orange *is* the identity, and a rule
 * automation app that renders differently on every phone is harder to write
 * documentation and screenshots for, not friendlier.
 *
 * Dark mode follows the system rather than offering a setting: there is nothing
 * to configure yet, and one more preference to persist is not worth it until
 * there is a settings screen to hold it.
 */
@Composable
fun TriglyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalExtraColors provides if (darkTheme) DarkExtraColors else LightExtraColors,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            content = content,
        )
    }
}
