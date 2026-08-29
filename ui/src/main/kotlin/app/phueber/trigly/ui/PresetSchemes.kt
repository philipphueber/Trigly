package app.phueber.trigly.ui

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color

/*
 * ─────────────────────────────────────────────────────────────────────────────
 *  THE COLOUR SCHEME PICKER: SIX PRESETS, PLUS THE SWITCH BETWEEN
 *  DEFAULT / A PRESET / THE SYSTEM WALLPAPER PALETTE.
 * ─────────────────────────────────────────────────────────────────────────────
 *
 *  `Palette.kt` still holds every colour used until someone opens Settings:
 *  the fixed Default scheme, `Tone`, and the roles Material 3 has no slot
 *  for. This file holds the five brand hues a person can switch *to*, and the
 *  logic that turns a stored choice into the colours `TriglyTheme` and
 *  `EngineService`'s notification actually render. "Follow the system" is not
 *  a colour at all here - it is resolved on the device, from the wallpaper,
 *  through `dynamicLightColorScheme`/`dynamicDarkColorScheme`.
 *
 *  ── HOW THE FIVE NEW RAMPS WERE MADE ─────────────────────────────────────
 *
 *  No colour maths runs in the app. `material-color-utilities` is not a
 *  dependency, and Material 3's own copy of it is not public API. So every
 *  ramp below is a hand-written literal, produced once by a throwaway script
 *  and pasted in. The script no longer exists; this paragraph is how to
 *  reproduce it.
 *
 *   1. Read `Tone.Orange10/30/40/50/60/70/80/90` as HSL.
 *   2. For each of the five other presets, add a fixed hue offset - 60, 120,
 *      180, 240 or 300 degrees - to all eight hues, and convert each back to
 *      sRGB hex. Saturation and lightness carry over unchanged, so a preset
 *      is the orange ramp turned to a different angle, not a different shape.
 *   3. One exception: Magenta's plain +300 rotation put tone 60 (its
 *      `primary`) where neither ink nor white text clears WCAG AA against it
 *      (4.48:1 and 4.20:1). Its lightness was nudged up by 1.4 percentage
 *      points across the whole ramp - the smallest step that cleared 4.5:1
 *      with headroom (4.72:1) - before step 2's rotation, so the ramp stayed
 *      one coherent shape instead of one patched tone.
 *   4. `onPrimary` was picked per preset: whichever of ink (`Tone.Ink`) or
 *      white clears WCAG AA against that preset's tone-60 fill. Five presets
 *      keep ink, the same as Default; Violet's fill is dark enough that only
 *      white clears it (7.62:1 against ink's 2.47:1).
 *   5. `TriglyExtraColors.accent`, the ink-only role Palette.kt explains, is a
 *      ramp step picked the same way: the first of tone 50/40/30 (light) or
 *      70/80/90 (dark) that clears WCAG AA against the page. Most presets
 *      keep the Default scheme's own 50/70; Lime and Green needed 30 for
 *      light, Violet needed 80 for dark.
 *
 *  Checked by eye against the fixed caution amber (`Tone.Amber40`/`Amber80`,
 *  hue ~40 degrees, unrelated to any of these): Lime, the preset next to
 *  Orange one way round the wheel, sits at hue 84 - 44 degrees off amber,
 *  further than Orange's own default hue already sits (16 degrees). Magenta,
 *  the preset next to Orange the other way, sits 76 degrees off. Neither
 *  reads as the caution colour, so nothing was reshuffled to dodge it.
 *
 *  ── ADDING A SEVENTH PRESET ──────────────────────────────────────────────
 *
 *  One more [ColorPreset] entry in [ColorPresets]. The picker, the switcher
 *  and the contrast test all walk this list, so none of the six existing
 *  entries change. The one file that cannot follow this rule is the
 *  manifest: an `activity-alias` cannot be declared at runtime, so stage 2's
 *  launcher icon still costs one alias and one colour resource per preset.
 */

/**
 * One colour-scheme choice offered on the picker's grid.
 *
 * @property id Stored in `ColorSchemeSettings`, and (from stage 2) the suffix
 *   naming this preset's launcher-icon alias and background colour resource.
 *   Lower-case, one word, and never renamed once shipped - a rename would
 *   silently orphan anyone's stored choice back to Default.
 * @property displayName What the picker's swatch and the settings row show.
 * @property light,[dark] The two schemes this preset resolves to. Everything
 *   but the primary group and the two accents is inherited from
 *   [LightScheme]/[DarkScheme] unchanged, because a preset is a new brand
 *   hue, not a new design.
 * @property accentLight,[accentDark] [TriglyExtraColors.accent] for this
 *   preset, light and dark. Not derived from `light`/`dark` above because
 *   [TriglyExtraColors] is one shared shape for every scheme; see this file's
 *   banner for how each value was chosen.
 */
data class ColorPreset(
    val id: String,
    val displayName: String,
    val light: ColorScheme,
    val dark: ColorScheme,
    val accentLight: Color,
    val accentDark: Color,
)

/**
 * The six presets, Orange included - see this file's banner for how the
 * other five were produced. Order is the order the picker's grid renders
 * them in.
 */
internal val ColorPresets: List<ColorPreset> = listOf(
    ColorPreset(
        id = "orange",
        displayName = "Orange",
        // Byte for byte today's app: the orange is one of the six, not a
        // special case standing outside the list.
        light = LightScheme,
        dark = DarkScheme,
        accentLight = Tone.Orange50,
        accentDark = Tone.Orange70,
    ),
    ColorPreset(
        id = "lime",
        displayName = "Lime",
        light = LightScheme.copy(
            primary = hex("#90EC06"),
            onPrimary = Tone.Ink,
            primaryContainer = hex("#E4FFBB"),
            onPrimaryContainer = hex("#1E2A00"),
            inversePrimary = hex("#C1FF6B"),
        ),
        dark = DarkScheme.copy(
            primary = hex("#90EC06"),
            onPrimary = Tone.Ink,
            primaryContainer = hex("#4B6B00"),
            onPrimaryContainer = hex("#E4FFBB"),
            inversePrimary = hex("#628E00"),
        ),
        accentLight = hex("#4B6B00"),
        accentDark = hex("#B2FF3D"),
    ),
    ColorPreset(
        id = "green",
        displayName = "Green",
        light = LightScheme.copy(
            primary = hex("#06EC62"),
            onPrimary = Tone.Ink,
            primaryContainer = hex("#BBFFD6"),
            onPrimaryContainer = hex("#002A0C"),
            inversePrimary = hex("#6BFFA9"),
        ),
        dark = DarkScheme.copy(
            primary = hex("#06EC62"),
            onPrimary = Tone.Ink,
            primaryContainer = hex("#006B20"),
            onPrimaryContainer = hex("#BBFFD6"),
            inversePrimary = hex("#008E2C"),
        ),
        accentLight = hex("#006B20"),
        accentDark = hex("#3DFF8A"),
    ),
    ColorPreset(
        id = "azure",
        displayName = "Azure",
        light = LightScheme.copy(
            primary = hex("#0690EC"),
            onPrimary = Tone.Ink,
            primaryContainer = hex("#BBE4FF"),
            onPrimaryContainer = hex("#001E2A"),
            inversePrimary = hex("#6BC1FF"),
        ),
        dark = DarkScheme.copy(
            primary = hex("#0690EC"),
            onPrimary = Tone.Ink,
            primaryContainer = hex("#004B6B"),
            onPrimaryContainer = hex("#BBE4FF"),
            inversePrimary = hex("#00628E"),
        ),
        accentLight = hex("#00628E"),
        accentDark = hex("#3DB2FF"),
    ),
    ColorPreset(
        id = "violet",
        displayName = "Violet",
        light = LightScheme.copy(
            primary = hex("#6206EC"),
            onPrimary = Tone.White,
            primaryContainer = hex("#D6BBFF"),
            onPrimaryContainer = hex("#0C002A"),
            inversePrimary = hex("#A96BFF"),
        ),
        dark = DarkScheme.copy(
            primary = hex("#6206EC"),
            onPrimary = Tone.White,
            primaryContainer = hex("#20006B"),
            onPrimaryContainer = hex("#D6BBFF"),
            inversePrimary = hex("#2C008E"),
        ),
        accentLight = hex("#3A00B8"),
        accentDark = hex("#A96BFF"),
    ),
    ColorPreset(
        id = "magenta",
        displayName = "Magenta",
        light = LightScheme.copy(
            primary = hex("#F30694"),
            onPrimary = Tone.Ink,
            primaryContainer = hex("#FFC2E7"),
            onPrimaryContainer = hex("#310023"),
            inversePrimary = hex("#FF72C4"),
        ),
        dark = DarkScheme.copy(
            primary = hex("#F30694"),
            onPrimary = Tone.Ink,
            primaryContainer = hex("#720050"),
            onPrimaryContainer = hex("#FFC2E7"),
            inversePrimary = hex("#950067"),
        ),
        accentLight = hex("#BF0083"),
        accentDark = hex("#FF44B5"),
    ),
)

/**
 * What is actually stored, and what a restore or an older build might hand
 * back. A name, never an ARGB integer - see `ColorSchemeSettings`'s own
 * KDoc for why.
 */
sealed interface ColorSchemeChoice {
    val storedName: String

    /** Today's fixed orange, whatever `ColorPresets` does or does not contain. */
    data object Default : ColorSchemeChoice {
        override val storedName: String = "default"
    }

    /** The wallpaper palette. See [effectiveChoice] for the API 31 floor. */
    data object System : ColorSchemeChoice {
        override val storedName: String = "system"
    }

    /** One entry of [ColorPresets], by [id]. */
    data class Preset(val id: String) : ColorSchemeChoice {
        override val storedName: String get() = id
    }

    companion object {
        /**
         * A stored name back to a choice, falling back to [Default] for
         * anything unrecognised: a name from a newer build, a preset dropped
         * since, or a plain restore of nothing at all. Falling back rather
         * than crashing is the whole reason the store is a name and not a
         * colour - see [ColorSchemeSettings].
         */
        fun fromStoredName(name: String?): ColorSchemeChoice = when {
            name == null || name == Default.storedName -> Default
            name == System.storedName -> System
            ColorPresets.any { it.id == name } -> Preset(name)
            else -> Default
        }
    }
}

/**
 * Collapses [ColorSchemeChoice.System] to [ColorSchemeChoice.Default] below
 * API 31, where `dynamicLightColorScheme`/`dynamicDarkColorScheme` cannot run
 * at all - the option a phone cannot honour is not offered as a choice.
 *
 * Takes the SDK level as a parameter instead of reading `Build.VERSION.SDK_INT`
 * itself, so a JVM test can exercise both sides of the boundary without
 * Robolectric or a device.
 */
fun effectiveChoice(choice: ColorSchemeChoice, sdkInt: Int): ColorSchemeChoice =
    if (choice is ColorSchemeChoice.System && sdkInt < Build.VERSION_CODES.S) {
        ColorSchemeChoice.Default
    } else {
        choice
    }

/** What [resolvedColors] hands back: a scheme for `MaterialTheme`, and the [TriglyExtraColors] beside it. */
data class ResolvedColors(val colorScheme: ColorScheme, val extra: TriglyExtraColors)

/**
 * Turns a stored choice into the colours [TriglyTheme] renders and
 * [EngineService]'s notification tints.
 *
 * Plain, not `@Composable`. `dynamicLightColorScheme`/`dynamicDarkColorScheme`
 * are themselves plain functions, so `EngineService` - a `Service`, with no
 * Compose runtime anywhere near it - reaches exactly the same colours the UI
 * does by calling this and nothing else.
 */
fun resolvedColors(context: Context, choice: ColorSchemeChoice, darkTheme: Boolean): ResolvedColors =
    when (val effective = effectiveChoice(choice, Build.VERSION.SDK_INT)) {
        ColorSchemeChoice.Default -> ResolvedColors(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            extra = if (darkTheme) DarkExtraColors else LightExtraColors,
        )

        ColorSchemeChoice.System -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // One tonal step past whatever dynamicLightColorScheme/
            // dynamicDarkColorScheme already use for `primary` (600 light,
            // 200 dark), in the direction that keeps it readable as text:
            // darker in light mode, lighter in dark. The wallpaper palette is
            // the one case nobody can hand-pick a literal for.
            val accentResId = if (darkTheme) {
                android.R.color.system_accent1_100
            } else {
                android.R.color.system_accent1_700
            }
            ResolvedColors(
                colorScheme = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context),
                extra = (if (darkTheme) DarkExtraColors else LightExtraColors)
                    .copy(accent = Color(context.getColor(accentResId))),
            )
        } else {
            // effectiveChoice already rules this branch out; kept because
            // lint's NewApi check cannot see that guarantee across functions.
            resolvedColors(context, ColorSchemeChoice.Default, darkTheme)
        }

        is ColorSchemeChoice.Preset -> {
            val preset = ColorPresets.find { it.id == effective.id }
            if (preset == null) {
                // A stored id that no longer names a preset. ColorSchemeChoice
                // .fromStoredName already guards against this on read, so the
                // only way here is a preset removed after being stored; same
                // answer either way.
                resolvedColors(context, ColorSchemeChoice.Default, darkTheme)
            } else {
                ResolvedColors(
                    colorScheme = if (darkTheme) preset.dark else preset.light,
                    extra = (if (darkTheme) DarkExtraColors else LightExtraColors)
                        .copy(accent = if (darkTheme) preset.accentDark else preset.accentLight),
                )
            }
        }
    }
