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
 *
 *  Colours are written `#RRGGBB`, the way a browser, a design tool or a palette
 *  generator writes them — see [hex]. That is the notation every source you
 *  might copy one *from* uses, including the two XML files above, so a value
 *  moves between here and there by copy and paste rather than by translation.
 *
 *  ── THE THREE RULES THIS PALETTE IS BUILT ON ────────────────────────────────
 *
 *  1. **The fill is the logo.** `primary` is `Tone.Orange60`, `#EC6206`, which
 *     is not "an orange from the brand ramp" but the literal background of
 *     `docs/branding/trigly-mark.svg`. It is the same value in light and dark:
 *     the header slab, the section labels, the buttons and the toggles are the
 *     app icon's own colour, so the thing you tapped on the home screen and the
 *     thing that fills the top of the screen are one colour and not two.
 *
 *  2. **The grid is ink, and so is the text.** `outline` and `onSurface` are the
 *     same value — near-black on the light theme, near-white on the dark one.
 *     Borders used to be orange, which cost the fills their punch: orange on
 *     orange has nowhere to land. A saturated fill needs a hard achromatic edge
 *     to snap against, which is the whole reason hazard markings are orange and
 *     black rather than orange and beige.
 *
 *  3. **Ink on the orange, not white.** `onPrimary` is `Tone.Ink`. White on
 *     `#EC6206` measures 3.32:1 and fails AA for body text; ink on it measures
 *     5.66:1 and passes. The accessible choice and the punchy one are the same
 *     choice here, which is the only reason the vivid orange can carry a slab
 *     at all — the previous burnt `#9F3D00` existed to make white text work.
 *
 *  The corollary of rule 1 is [TriglyExtraColors.accent]: read its comment
 *  before reaching for `primary` in a `color =` on a text or an icon.
 */

/**
 * A colour from a web-style hex string.
 *
 * The Compose literal and the web string below are the same colour, but only
 * one of them is what a design tool puts on your clipboard. The literal needs an
 * `0x` prefix, an alpha pair prepended, and — the part that actually bites — it
 * silently means *transparent black* if the alpha is left off, because
 * `Color(0xEC6206)` is a valid call with an alpha of zero. A wrong colour is
 * visible; an invisible one looks like a layout bug.
 *
 * Accepts what CSS accepts: `#RGB`, `#RRGGBB`, `#RRGGBBAA`. Note that the
 * eight-digit form puts **alpha last**, as the web does, and not first as
 * Android's packed ints do — the whole point is that a string copied from
 * outside means here what it meant there. Omitted alpha is opaque.
 *
 * Parsing happens once, when [Tone] is first touched, for a few dozen constants;
 * a malformed string is a loud failure at startup rather than a wrong colour
 * shipped quietly, and [PaletteHexTest] keeps the parser honest.
 */
internal fun hex(value: String): Color {
    val digits = value.removePrefix("#")
    require(digits.length == value.length - 1) { "a colour must start with '#', got '$value'" }
    require(digits.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
        "'$value' has a non-hex digit in it"
    }

    // #RGB is shorthand for #RRGGBB: each digit doubles.
    val full = if (digits.length == 3) digits.map { "$it$it" }.joinToString("") else digits
    require(full.length == 6 || full.length == 8) {
        "a colour must be #RGB, #RRGGBB or #RRGGBBAA, got '$value'"
    }

    val rgb = full.substring(0, 6).toLong(16)
    val alpha = if (full.length == 8) full.substring(6, 8).toLong(16) else 0xFF
    return Color((alpha shl 24 or rgb).toInt())
}

// ─────────────────────────────────────────────────────────────────────────────
// 1. TONES
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The raw palette, as tonal ramps.
 *
 * Numbers are Material tone values: 0 is black, 100 is white. Only the steps a
 * role actually asks for are declared — a ramp with an unused rung on it is a
 * rung that drifts out of tune with its neighbours, because nothing on screen
 * ever shows it going wrong.
 *
 * To re-brand the app: put the new colour in [Orange60] — it is the one hex the
 * mark and the UI share — then walk the rest of the ramp to the same hue,
 * keeping the lightness order intact. The neutrals carry a trace of the same hue
 * on purpose, so they are worth nudging too, but nothing breaks if you don't.
 * Check [Orange50] afterwards: it is the ink accent, and it is the one step with
 * a contrast floor to clear.
 */
internal object Tone {

    // ── Brand. High chroma, and no apology for it: this is the colour the app
    // *is*. The ramp exists to support Orange60, not to average it out.

    val Orange10 = hex("#2A0C00")
    val Orange30 = hex("#6B2000")
    val Orange40 = hex("#8E2C00")

    /**
     * The brand orange dark enough to be *text*. 5.60:1 on [Paper], 4.89:1 on
     * the deepest block fill. See [TriglyExtraColors.accent].
     */
    val Orange50 = hex("#B83A00")

    /**
     * **The logo.** Byte-for-byte the background of `docs/branding/trigly-mark.svg`
     * and of `ic_launcher_background` in `res/values/colors.xml`. Every filled
     * orange surface in the app is this value, in both themes. If you change it,
     * change those two as well — they are the same colour claiming to be one.
     */
    val Orange60 = hex("#EC6206")

    /** [Orange50]'s job on a dark page: the ink accent, inverted. 8.03:1 on [Ink]. */
    val Orange70 = hex("#FF8A3D")

    val Orange80 = hex("#FFA96B")
    val Orange90 = hex("#FFD6BB")

    // ── Secondary: an electric blue, near enough the brand's true complement.
    // It replaced a desaturated brown, which was the single biggest reason the
    // old palette read as muted — a "secondary" with the chroma pulled out of it
    // is not a second colour, it is a tinted grey with ambitions. Used almost
    // nowhere as a surface; its real job is `RegexHighlight`, where a cool hue
    // is the only thing that separates a group from the four warm ones.
    val Blue10 = hex("#001A45")
    val Blue20 = hex("#002A6B")
    val Blue30 = hex("#003C96")
    val Blue40 = hex("#1240B8")
    val Blue80 = hex("#A9C7FF")
    val Blue90 = hex("#D6E3FF")

    // ── Tertiary: acid lime. Loud on purpose, and rationed — it is what stops
    // an orange screen reading as a single wash, which the previous olive was
    // too quiet to do.
    val Lime10 = hex("#152000")
    val Lime20 = hex("#253600")
    val Lime30 = hex("#374F00")
    val Lime40 = hex("#496A00")
    val Lime80 = hex("#B4E836")
    val Lime90 = hex("#D2F76B")

    // ── The two extremes, named for what they are rather than numbered. These
    // are the ones `res/values*/colors.xml` must mirror.

    /**
     * Lightest surface — the light theme's page. Crisper than a cream: the
     * orange has to look saturated against it, and every point of paper
     * lightness is a point of fill contrast.
     */
    val Paper = hex("#FFFBF7")

    /** Darkest surface — the dark theme's page, and the light theme's grid. */
    val Ink = hex("#17100C")

    /** Below [Ink]: the one surface in the dark theme that recedes past the page. */
    val InkDeep = hex("#120C08")

    // ── Warm neutral-variant. Structural, not decorative: secondary text and
    // the muted "this control is off" border. Warm rather than grey because a
    // pure grey beside a saturated orange looks like a rendering mistake.
    val Warm20 = hex("#422619")
    val Warm30 = hex("#4E382B")
    val Warm50 = hex("#86695A")
    val Warm60 = hex("#A08877")
    val Warm80 = hex("#E3C4B2")

    val Neutral14 = hex("#271F19")
    val Neutral20 = hex("#362C26")
    val Neutral90 = hex("#F2E3DA")
    val Neutral96 = hex("#FFF3EC")
    val White = hex("#FFFFFF")

    // ── Blocks. The brutalist look is made of these: a flat tinted slab with a
    // hard border, no elevation, no gradient. Kept close to the page so that the
    // border, not the fill, is what makes a block a block.
    val BlockLight = hex("#FFF4EC")
    val BlockLightAlt = hex("#FFE8DA")
    val BlockDark = hex("#241610")
    val BlockDarkAlt = hex("#2F1D12")

    // ── Error stays red, and got hotter along with everything else. The brand
    // colour is orange, so a failure cannot also be orange — it would be
    // indistinguishable from ordinary emphasis. [Red40] is the hottest red that
    // still clears 4.5:1 on [Paper] (5.29:1), which is the only constraint that
    // matters: error is text far more often than it is a fill.
    val Red10 = hex("#3F0008")
    val Red20 = hex("#6B000F")
    val Red30 = hex("#93000F")
    val Red40 = hex("#D50024")
    val Red80 = hex("#FFADB4")
    val Red90 = hex("#FFD9DC")

    // ── Caution: amber, for "this works, but you should know something".
    // Deliberately distinct from both the brand orange and the error red.
    //
    // [Amber40] is the one tone in this file that is *not* punchier than it was,
    // and it cannot be: caution is drawn as small text on a block fill, and a
    // gold bright enough to look punchy measures 4.17:1 there. The punch went
    // into [Amber80] instead, where the dark theme has the headroom for it.
    val Amber20 = hex("#3E2A00")
    val Amber30 = hex("#5A3D00")
    val Amber40 = hex("#8A5D00")
    val Amber80 = hex("#FFC24D")
    val Amber90 = hex("#FFDF9E")
    val Amber95 = hex("#FFF0D6")
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
 *  · `primary` fills the header slabs, the section labels, the buttons and the
 *    on-state of every toggle. It is the logo orange, and it is the same value
 *    in the dark scheme — the one role here that does not invert.
 *  · `onPrimary` is ink, not white. Rule 3 at the top of this file.
 *  · `outline` is every block's 2dp border and every divider inside one, and it
 *    is the same ink as `onSurface`. Nothing structural is orange.
 *  · `outlineVariant` is the *off* border — a muted warm neutral that clears
 *    3:1 against the page (3.25:1) without competing with the on-state.
 *  · `surfaceContainer` / `surfaceContainerLow` are the block fills.
 */
internal val LightScheme = lightColorScheme(
    primary = Tone.Orange60,
    onPrimary = Tone.Ink,
    primaryContainer = Tone.Orange90,
    onPrimaryContainer = Tone.Orange10,
    inversePrimary = Tone.Orange80,

    secondary = Tone.Blue40,
    onSecondary = Tone.White,
    secondaryContainer = Tone.Blue90,
    onSecondaryContainer = Tone.Blue10,

    tertiary = Tone.Lime40,
    onTertiary = Tone.White,
    tertiaryContainer = Tone.Lime90,
    onTertiaryContainer = Tone.Lime10,

    error = Tone.Red40,
    onError = Tone.White,
    errorContainer = Tone.Red90,
    onErrorContainer = Tone.Red10,

    background = Tone.Paper,
    onBackground = Tone.Ink,
    surface = Tone.Paper,
    onSurface = Tone.Ink,
    surfaceVariant = Tone.BlockLightAlt,
    onSurfaceVariant = Tone.Warm30,

    surfaceContainerLowest = Tone.White,
    surfaceContainerLow = Tone.BlockLight,
    surfaceContainer = Tone.BlockLightAlt,
    surfaceContainerHigh = Tone.Orange90,
    surfaceContainerHighest = Tone.Orange90,

    // The block borders and the divider inside a block: the same ink as the text.
    outline = Tone.Ink,
    outlineVariant = Tone.Warm60,

    inverseSurface = Tone.Neutral20,
    inverseOnSurface = Tone.Neutral96,
)

/**
 * Dark scheme. Same roles, same reasoning, inverted anchors — except `primary`
 * and `onPrimary`, which are deliberately identical to the light scheme's. The
 * orange slab is the logo swatch on both themes, so it is the one thing in the
 * app that does not change when the sun goes down.
 */
internal val DarkScheme = darkColorScheme(
    primary = Tone.Orange60,
    onPrimary = Tone.Ink,
    primaryContainer = Tone.Orange30,
    onPrimaryContainer = Tone.Orange90,
    inversePrimary = Tone.Orange40,

    secondary = Tone.Blue80,
    onSecondary = Tone.Blue20,
    secondaryContainer = Tone.Blue30,
    onSecondaryContainer = Tone.Blue90,

    tertiary = Tone.Lime80,
    onTertiary = Tone.Lime20,
    tertiaryContainer = Tone.Lime30,
    onTertiaryContainer = Tone.Lime90,

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

    surfaceContainerLowest = Tone.InkDeep,
    surfaceContainerLow = Tone.BlockDark,
    surfaceContainer = Tone.BlockDarkAlt,
    surfaceContainerHigh = Tone.Warm20,
    surfaceContainerHighest = Tone.Warm20,

    outline = Tone.Neutral90,
    outlineVariant = Tone.Warm50,

    inverseSurface = Tone.Neutral90,
    inverseOnSurface = Tone.Neutral14,
)

// ─────────────────────────────────────────────────────────────────────────────
// 3. EXTRAS
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Colours Material 3 has no role for.
 *
 * @property caution A component *warning* — "this polls, so it costs battery",
 *   "Android 12 suppresses these in the background" — is not an error. The rule
 *   is valid and will save; the user is being told something they need to know.
 *   Drawing it in `colorScheme.error` claims they did something wrong, and once
 *   every second trigger carries one, red becomes noise people learn to skip. So
 *   caution gets its own amber, and red stays for things that actually failed.
 * @property cautionContainer The fill behind a caution, for the rare case that
 *   wants a slab rather than a line of text.
 * @property onCautionContainer Text on [cautionContainer].
 * @property accent The brand orange when it has to be **ink instead of a fill**:
 *   an outlined button's label and border, a value readout, the escapes in a
 *   highlighted regex.
 *
 *   Material has one `primary` and this design needs two oranges, because
 *   `primary` is the logo's `#EC6206` and `#EC6206` on the page measures 3.23:1
 *   — fine as a slab you land ink on, not fine as 12sp text. So the loud one
 *   fills and this one writes. They are the same hue and one is simply darker,
 *   which is the part that makes it read as one colour used two ways rather than
 *   as two oranges that failed to agree.
 *
 *   **If you are about to write `color = MaterialTheme.colorScheme.primary`, you
 *   want this instead.** `primary` belongs in `Surface(color = …)` and
 *   `containerColor`, never in a `Text` or an `Icon` on the page.
 */
@Immutable
data class TriglyExtraColors(
    val caution: Color,
    val cautionContainer: Color,
    val onCautionContainer: Color,
    val accent: Color,
)

internal val LightExtraColors = TriglyExtraColors(
    caution = Tone.Amber40,
    cautionContainer = Tone.Amber95,
    onCautionContainer = Tone.Amber20,
    accent = Tone.Orange50,
)

internal val DarkExtraColors = TriglyExtraColors(
    caution = Tone.Amber80,
    cautionContainer = Tone.Amber30,
    onCautionContainer = Tone.Amber90,
    accent = Tone.Orange70,
)
