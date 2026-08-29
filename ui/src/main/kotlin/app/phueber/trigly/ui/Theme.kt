package app.phueber.trigly.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LocalExtraColors = staticCompositionLocalOf { LightExtraColors }

/** `MaterialTheme.extra.caution`, alongside `MaterialTheme.colorScheme.error`. */
val MaterialTheme.extra: TriglyExtraColors
    @Composable @ReadOnlyComposable get() = LocalExtraColors.current

/**
 * One corner radius, for every role.
 *
 * All five roles are the same value on purpose. A Material shape scale exists so
 * a chip can be rounder than a dialog; nothing here wants that — the design is a
 * grid of blocks, and a grid whose cells disagree about their corners is not a
 * grid. Setting them all makes [BlockCorner] the single number that decides the
 * app's geometry, the way `Palette.kt` owns its colour.
 *
 * **Why 3dp and not 0, and not 12.** At 0dp a 2dp border meets itself at a point,
 * and a point is what makes a flat rectangle read as unfinished rather than as
 * chosen — it is the difference between brutalism and a missing stylesheet. 3dp
 * is under the threshold where anyone would call the app rounded, and over the
 * one where a corner looks like an accident. It also stays clear of the two
 * things a larger radius would break: `BlockDivider` runs a 2dp line the full
 * width of a card, and past roughly 4dp that line stops meeting the border and
 * starts leaving a notch at each end. And radius is coupled to stroke weight — a
 * 2dp border around a generous curve is the one combination that looks neither
 * brutalist nor modern, so anything above ~4dp here is a request to thin the
 * border too, which is a different design rather than a shape change.
 *
 * `RoundedCornerShape` throughout, including if this ever goes back to 0dp: the
 * roles are typed as `CornerBasedShape`, so components that adjust a corner
 * themselves (a menu opening upward, a split button) keep working, which
 * `RectangleShape` would not allow.
 *
 * **What actually reads from here.** Dialogs, menus, text fields and snackbars,
 * because Material's own components resolve their shape from the theme — plus
 * every block, but only via `Blocks.kt`'s `BlockShape`, which passes
 * `shapes.medium` explicitly. That indirection is not decoration: `Surface`
 * defaults to `RectangleShape`, so a block that omits `shape` is square no
 * matter what this file says. An earlier version of this comment claimed cards
 * read from here and they did not.
 */
private val BlockCorner = 3.dp

private val BlockShapes = Shapes(
    extraSmall = RoundedCornerShape(BlockCorner),
    small = RoundedCornerShape(BlockCorner),
    medium = RoundedCornerShape(BlockCorner),
    large = RoundedCornerShape(BlockCorner),
    extraLarge = RoundedCornerShape(BlockCorner),
)

/**
 * Type with the weight turned up.
 *
 * Two deliberate choices. Titles and labels are heavy and letter-spaced, because
 * the design leans on uppercase chrome and uppercase text at normal tracking
 * reads as a wall. And `bodyMedium` is monospaced: it carries the rule summary
 * ("Screen on or off → Toast"), where a fixed advance makes a list of rules line
 * up into a column you can scan instead of ragged prose.
 *
 * The families are the platform's own. A bundled font would be the next step for
 * a real brand, and would cost an asset in the APK plus a licence to check.
 */
private val BlockTypography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontWeight = FontWeight.Black),
        headlineMedium = headlineMedium.copy(
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp,
        ),
        headlineSmall = headlineSmall.copy(
            fontWeight = FontWeight.Black,
            letterSpacing = 0.8.sp,
        ),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp),
        bodyMedium = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        ),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
        labelMedium = labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
        labelSmall = labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
    )
}

/**
 * The app's theme. Colours live in `Palette.kt` and `PresetSchemes.kt`, and
 * nowhere else.
 *
 * **Takes the resolved scheme; does not pick one.** This used to reach for
 * `LightScheme`/`DarkScheme` itself from a plain `darkTheme: Boolean`. Now
 * that a person can choose Default, a preset, or the system wallpaper
 * palette, "which scheme" is a bigger decision than this file should own -
 * `PresetSchemes.kt`'s `resolvedColors` makes it, `MainActivity` calls that
 * once per recomposition and hands the answer down, and this file goes back
 * to being only about applying one.
 *
 * **No dynamic colour by default.** Material You would replace the orange
 * with tones pulled from the user's wallpaper, which is a good default for
 * an app with no colour of its own and the wrong one as *the* default here -
 * the orange is the identity, and an app that renders differently on every
 * phone out of the box makes its own screenshots and documentation lie. It
 * is offered as an explicit opt-in instead, in Settings, so nobody gets it
 * without asking for it.
 *
 * The default parameters keep a bare `TriglyTheme { … }` - every instrumented
 * test that only needs *a* theme, not this one's settings - behaving exactly
 * as before: the fixed Default scheme, following the system's light/dark
 * switch.
 */
@Composable
fun TriglyTheme(
    colorScheme: ColorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
    extraColors: TriglyExtraColors = if (isSystemInDarkTheme()) DarkExtraColors else LightExtraColors,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalExtraColors provides extraColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = BlockShapes,
            typography = BlockTypography,
            content = content,
        )
    }
}
