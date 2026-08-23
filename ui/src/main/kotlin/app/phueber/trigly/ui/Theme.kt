package app.phueber.trigly.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
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
 * `RoundedCornerShape` rather than `RectangleShape` even at 0dp, because the
 * roles are typed as `CornerBasedShape`: components that adjust a corner
 * themselves (a menu opening upward, a split button) still work.
 *
 * **What actually reads from here.** Dialogs, menus, text fields and snackbars,
 * because Material's own components resolve their shape from the theme — plus
 * every block, but only via `Blocks.kt`'s `BlockShape`, which passes
 * `shapes.medium` explicitly. That indirection is not decoration: `Surface`
 * defaults to `RectangleShape`, so a block that omits `shape` is square no
 * matter what this file says. An earlier version of this comment claimed cards
 * read from here and they did not.
 */
private val BlockCorner = 0.dp

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
 * The app's theme. Colours live in `Palette.kt` and nowhere else.
 *
 * **No dynamic colour.** Material You would replace the orange with tones pulled
 * from the user's wallpaper, which is a good default for an app with no colour
 * of its own and the wrong one here — the orange *is* the identity, and an app
 * that renders differently on every phone makes its own screenshots and
 * documentation lie.
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
            shapes = BlockShapes,
            typography = BlockTypography,
            content = content,
        )
    }
}
