package app.phueber.trigly.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/*
 * ─────────────────────────────────────────────────────────────────────────────
 *  EVERY COLOUR IN THE APP IS IN THIS FILE. THIS IS THE ONLY FILE TO EDIT.
 * ─────────────────────────────────────────────────────────────────────────────
 *
 *  Three sections, in the order you would want them:
 *
 *    1. TONES     the raw hexes, as a tonal ramp per hue. Change the app's
 *                 colour here and everything downstream follows.
 *    2. SCHEMES   which tone plays which Material role, light and dark.
 *                 Change these to move a colour to a different *use*.
 *    3. EXTRAS    the handful of roles Material 3 has no slot for.
 *
 *  Two things live outside this file, and both are unavoidable:
 *
 *    · `res/values/colors.xml` and `res/values-night/colors.xml` hold the window
 *      background. The framework paints the window before any Compose code
 *      runs, so it cannot read a value from here — without it a dark-mode launch
 *      flashes white. Keep those two hexes equal to `Tone.Paper` and
 *      `Tone.Ink`, and nothing else needs to match.
 *    · Shapes and type are in `Theme.kt`. They are not colours.
 */

// ─────────────────────────────────────────────────────────────────────────────
// 1. TONES
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The raw palette, as tonal ramps.
 *
 * Numbers are Material tone values: 0 is black, 100 is white. 40 and 80 are the
 * anchors the light and dark schemes are built around, which is why every ramp
 * has those two even when it skips others.
 *
 * To re-brand the app: rotate the hue of the `Orange*` ramp, keeping the
 * lightness order intact, and leave everything else alone. The neutrals carry a
 * trace of the same hue on purpose — a pure grey surface under a coloured accent
 * looks accidental — so they are worth nudging too, but nothing breaks if you
 * don't.
 */
internal object Tone {

    // ── Brand. This is the colour the app *is*; chroma stays high.
    val Orange10 = Color(0xFF351000)
    val Orange20 = Color(0xFF561D00)
    val Orange30 = Color(0xFF7A2C00)
    val Orange40 = Color(0xFF9F3D00)
    val Orange50 = Color(0xFFC64C00)
    val Orange60 = Color(0xFFEC6206)
    val Orange80 = Color(0xFFFFB68F)
    val Orange90 = Color(0xFFFFDBC8)
    val Orange95 = Color(0xFFFFEDE4)

    // ── Secondary: the brand hue with the chroma pulled out, for anything that
    // must sit beside the orange without competing with it.
    val Warm10 = Color(0xFF2C1608)
    val Warm20 = Color(0xFF45291A)
    val Warm30 = Color(0xFF5E3F2F)
    val Warm40 = Color(0xFF785645)
    val Warm80 = Color(0xFFE7BEA9)
    val Warm90 = Color(0xFFFFDBC8)

    // ── Tertiary: an olive, roughly complementary. Used sparingly — it is what
    // stops an all-orange screen reading as a single wash.
    val Olive10 = Color(0xFF1A1D00)
    val Olive20 = Color(0xFF2E3300)
    val Olive30 = Color(0xFF444A00)
    val Olive40 = Color(0xFF5C6300)
    val Olive80 = Color(0xFFC5CD73)
    val Olive90 = Color(0xFFE1E98C)

    // ── The two extremes, named for what they are rather than numbered. These
    // are the ones `res/values*/colors.xml` must mirror.
    /** Lightest surface — the light theme's page. */
    val Paper = Color(0xFFFFF8F5)

    /** Darkest surface — the dark theme's page. */
    val Ink = Color(0xFF17100C)

    // ── Warm neutrals for everything structural.
    val Neutral10 = Color(0xFF211A16)
    val Neutral14 = Color(0xFF2A211B)
    val Neutral20 = Color(0xFF382E29)
    val Neutral90 = Color(0xFFF1DFD7)
    val Neutral96 = Color(0xFFFFF2EB)
    val White = Color(0xFFFFFFFF)

    // ── Blocks. The brutalist look is made of these: a flat tinted slab with a
    // hard border, no elevation, no gradient.
    val BlockLight = Color(0xFFFFEFE6)
    val BlockLightAlt = Color(0xFFFFE4D5)
    val BlockDark = Color(0xFF2A1A11)
    val BlockDarkAlt = Color(0xFF33210F)

    // ── Error stays red. The brand colour is orange, so a failure cannot also
    // be orange — it would be indistinguishable from ordinary emphasis.
    val Red10 = Color(0xFF410002)
    val Red20 = Color(0xFF690005)
    val Red30 = Color(0xFF93000A)
    val Red40 = Color(0xFFBA1A1A)
    val Red80 = Color(0xFFFFB4AB)
    val Red90 = Color(0xFFFFDAD6)

    // ── Caution: amber, for "this works, but you should know something".
    // Deliberately distinct from both the brand orange and the error red.
    val Amber20 = Color(0xFF422C00)
    val Amber30 = Color(0xFF5E4000)
    val Amber40 = Color(0xFF8A5D00)
    val Amber80 = Color(0xFFFFC46B)
    val Amber90 = Color(0xFFFFDFA8)
    val Amber95 = Color(0xFFFFEEDC)
}

// ─────────────────────────────────────────────────────────────────────────────
// 2. SCHEMES
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Light scheme.
 *
 * Worth knowing which roles are load-bearing in this design, because they are
 * not the usual ones:
 *
 *  · `primary` fills the header slabs and the primary buttons outright, so it
 *    must be dark enough for white text.
 *  · `outline` is every block's 2dp border. It is the brand orange rather than a
 *    grey, which is most of what makes the screens read as orange.
 *  · `surfaceContainer` / `surfaceContainerLow` are the block fills.
 */
internal val LightScheme = lightColorScheme(
    primary = Tone.Orange40,
    onPrimary = Tone.White,
    primaryContainer = Tone.Orange90,
    onPrimaryContainer = Tone.Orange10,
    inversePrimary = Tone.Orange80,

    secondary = Tone.Warm40,
    onSecondary = Tone.White,
    secondaryContainer = Tone.Warm90,
    onSecondaryContainer = Tone.Warm10,

    tertiary = Tone.Olive40,
    onTertiary = Tone.White,
    tertiaryContainer = Tone.Olive90,
    onTertiaryContainer = Tone.Olive10,

    error = Tone.Red40,
    onError = Tone.White,
    errorContainer = Tone.Red90,
    onErrorContainer = Tone.Red10,

    background = Tone.Paper,
    onBackground = Tone.Neutral10,
    surface = Tone.Paper,
    onSurface = Tone.Neutral10,
    surfaceVariant = Tone.BlockLightAlt,
    onSurfaceVariant = Tone.Warm30,

    surfaceContainerLowest = Tone.White,
    surfaceContainerLow = Tone.BlockLight,
    surfaceContainer = Tone.BlockLightAlt,
    surfaceContainerHigh = Tone.Orange90,
    surfaceContainerHighest = Tone.Orange90,

    // The block borders and the divider inside a block.
    outline = Tone.Orange40,
    outlineVariant = Tone.Orange80,

    inverseSurface = Tone.Neutral20,
    inverseOnSurface = Tone.Neutral96,
)

/** Dark scheme. Same roles, same reasoning, inverted anchors. */
internal val DarkScheme = darkColorScheme(
    primary = Tone.Orange80,
    onPrimary = Tone.Orange20,
    primaryContainer = Tone.Orange30,
    onPrimaryContainer = Tone.Orange90,
    inversePrimary = Tone.Orange40,

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

    background = Tone.Ink,
    onBackground = Tone.Neutral90,
    surface = Tone.Ink,
    onSurface = Tone.Neutral90,
    surfaceVariant = Tone.BlockDarkAlt,
    onSurfaceVariant = Tone.Warm80,

    surfaceContainerLowest = Color(0xFF120C08),
    surfaceContainerLow = Tone.BlockDark,
    surfaceContainer = Tone.BlockDarkAlt,
    surfaceContainerHigh = Tone.Warm30,
    surfaceContainerHighest = Tone.Warm30,

    outline = Tone.Orange80,
    outlineVariant = Tone.Orange30,

    inverseSurface = Tone.Neutral90,
    inverseOnSurface = Tone.Neutral14,
)

// ─────────────────────────────────────────────────────────────────────────────
// 3. EXTRAS
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Colours Material 3 has no role for.
 *
 * A component *warning* — "this polls, so it costs battery", "Android 12
 * suppresses these in the background" — is not an error. The rule is valid and
 * will save; the user is being told something they need to know. Drawing it in
 * `colorScheme.error` claims they did something wrong, and once every second
 * trigger carries one, red becomes noise people learn to skip. So caution gets
 * its own amber, and red stays for things that actually failed.
 */
@Immutable
data class TriglyExtraColors(
    val caution: Color,
    val cautionContainer: Color,
    val onCautionContainer: Color,
)

internal val LightExtraColors = TriglyExtraColors(
    caution = Tone.Amber40,
    cautionContainer = Tone.Amber95,
    onCautionContainer = Tone.Amber20,
)

internal val DarkExtraColors = TriglyExtraColors(
    caution = Tone.Amber80,
    cautionContainer = Tone.Amber30,
    onCautionContainer = Tone.Amber90,
)
