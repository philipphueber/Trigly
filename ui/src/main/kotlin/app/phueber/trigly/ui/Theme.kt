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
 * Nothing is rounded.
 *
 * Every Material shape role is square, which is most of what makes this look
 * like a tool rather than a consumer app — and it is one declaration rather than
 * a `shape =` argument on every call site. Dialogs, cards, buttons, text fields,
 * menus and snackbars all read from here.
 *
 * `RoundedCornerShape(0.dp)` rather than `RectangleShape` because the roles are
 * typed as `CornerBasedShape`: components that adjust a corner themselves (a
 * menu opening upward, a split button) still work.
 */
private val SquareShapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp),
    small = RoundedCornerShape(0.dp),
    medium = RoundedCornerShape(0.dp),
    large = RoundedCornerShape(0.dp),
    extraLarge = RoundedCornerShape(0.dp),
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
            shapes = SquareShapes,
            typography = BlockTypography,
            content = content,
        )
    }
}
