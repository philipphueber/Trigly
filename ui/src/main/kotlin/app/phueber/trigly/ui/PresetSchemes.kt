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
 *  These five ramps replace an earlier version. The earlier version rotated
 *  hue in HSL at fixed saturation and fixed lightness. HSL lightness is not
 *  perceptual: the same number lands at a different apparent brightness and
 *  a different apparent colourfulness at every hue. That is why the old Lime
 *  read as neon and the old Azure and Violet read as muddy next to a good
 *  Orange - the numbers matched, the eye did not agree.
 *
 *  No colour maths runs in the app. `material-color-utilities` is not a
 *  dependency, and Material 3's own copy of it is not public API. So every
 *  ramp below is a hand-written literal, produced once by a throwaway script
 *  and pasted in. The script no longer exists; this paragraph is how to
 *  reproduce it.
 *
 *   1. Convert each of `Tone.Orange10/30/40/50/60/70/80/90` from sRGB to
 *      OKLCH (Björn Ottosson's OKLab, turned to cylindrical L/C/H the usual
 *      way: `C = hypot(a, b)`, `H = atan2(b, a)`). Keep only each tone's `L`.
 *      This is the lightness ladder every preset below holds to; it is not
 *      eight equal steps; it is whatever Orange's own hand-picked ramp
 *      already uses. Measured: tone 10 = 0.2016, 30 = 0.3586, 40 = 0.4378,
 *      50 = 0.5280, 60 = 0.6618, 70 = 0.7500, 80 = 0.8069, 90 = 0.9041.
 *   2. Give each of the five other presets one fixed hue, in OKLCH degrees:
 *      Lime 118, Green 152, Azure 240, Violet 295, Magenta 345. These are not
 *      60-degree steps. Each was checked against known swatches at that name
 *      (Material's own tonal colours, and the pure sRGB primaries/secondaries)
 *      converted through the same OKLCH math, so the hue picked for "Lime"
 *      sits where a lime-coloured swatch actually sits, not at a mechanical
 *      offset from Orange's 44.57 degrees.
 *   3. For each of the eight tones, hold `L` to step 1's value for that tone
 *      and `H` to step 2's value for that preset, and solve for the largest
 *      `C` whose OKLab-to-linear-sRGB conversion still lands every channel in
 *      [0, 1] - bisection on `C` between 0 and 0.5, 60 iterations, which
 *      settles the boundary to far better precision than 8-bit sRGB can show.
 *      This is the step that must not be skipped: the most colourful colour
 *      sRGB can hold at one `L, H` is a different number at every hue, so
 *      solving for it per hue is what step 4 checks and what a single shared
 *      saturation would have gotten wrong again.
 *   4. Round-trip every result hex back through step 1's conversion. Every
 *      preset's `L` at every one of the eight tones lands within 0.0014 of
 *      Orange's own `L` at that tone - see the commit message for the full
 *      8-tone-by-5-preset table. That is the check the earlier ramps would
 *      have failed had anyone run it.
 *   5. Map the eight tones onto the roles Orange's own ramp already uses:
 *      tone 60 is `primary` in both schemes; tone 90 is light's
 *      `primaryContainer` and dark's `onPrimaryContainer`; tone 10 is light's
 *      `onPrimaryContainer`; tone 30 is dark's `primaryContainer`; tone 80 is
 *      light's `inversePrimary`; tone 40 is dark's `inversePrimary`; tone 50
 *      is [TriglyExtraColors.accent] in light; tone 70 is the same in dark.
 *   6. `onPrimary`: every preset's tone 60 clears WCAG AA against `Tone.Ink`
 *      (5.27:1 to 6.59:1, against Orange's own 5.66:1), so every preset uses
 *      `Tone.Ink`, the same as Default. The earlier ramps needed white for
 *      Violet only because its HSL-rotated tone 60 was accidentally darker
 *      than the other five at the same nominal lightness; holding `L` level
 *      in step 1 removes that case rather than working around it.
 *
 *  Checked against the fixed caution amber (`Tone.Amber40`/`Amber80`, hue
 *  76.06/80.44 degrees in the same OKLCH space): every preset's tone-60 hue
 *  sits further from amber than Orange's own 31.49 degrees does. Lime is
 *  closest at 37.5 (against Amber80) to 41.9 (against Amber40) degrees; Green
 *  71.4 to 75.8; Magenta 91.0 to 95.4; Violet 140.8 to 145.2; Azure 159.7 to
 *  164.1. None reads as the caution colour.
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
            primary = hex("#8B9E00"),
            onPrimary = Tone.Ink,
            primaryContainer = hex("#D4F000"),
            onPrimaryContainer = hex("#151900"),
            inversePrimary = hex("#B6CF00"),
        ),
        dark = DarkScheme.copy(
            primary = hex("#8B9E00"),
            onPrimary = Tone.Ink,
            primaryContainer = hex("#394200"),
            onPrimaryContainer = hex("#D4F000"),
            inversePrimary = hex("#4D5900"),
        ),
        accentLight = hex("#657400"),
        accentDark = hex("#A5BB00"),
    ),
    ColorPreset(
        id = "green",
        displayName = "Green",
        light = LightScheme.copy(
            primary = hex("#00B059"),
            onPrimary = Tone.Ink,
            primaryContainer = hex("#7DFFA6"),
            onPrimaryContainer = hex("#001D09"),
            inversePrimary = hex("#00E577"),
        ),
        dark = DarkScheme.copy(
            primary = hex("#00B059"),
            onPrimary = Tone.Ink,
            primaryContainer = hex("#004A22"),
            onPrimaryContainer = hex("#7DFFA6"),
            inversePrimary = hex("#006330"),
        ),
        accentLight = hex("#008140"),
        accentDark = hex("#00D06B"),
    ),
    ColorPreset(
        id = "azure",
        displayName = "Azure",
        light = LightScheme.copy(
            primary = hex("#009CE5"),
            onPrimary = Tone.Ink,
            primaryContainer = hex("#C1E5FF"),
            onPrimaryContainer = hex("#001929"),
            inversePrimary = hex("#7BC9FF"),
        ),
        dark = DarkScheme.copy(
            primary = hex("#009CE5"),
            onPrimary = Tone.Ink,
            primaryContainer = hex("#004163"),
            onPrimaryContainer = hex("#C1E5FF"),
            inversePrimary = hex("#005883"),
        ),
        accentLight = hex("#0072A9"),
        accentDark = hex("#46B9FF"),
    ),
    ColorPreset(
        id = "violet",
        displayName = "Violet",
        light = LightScheme.copy(
            primary = hex("#9F71FF"),
            onPrimary = Tone.Ink,
            primaryContainer = hex("#E1D9FF"),
            onPrimaryContainer = hex("#1D0042"),
            inversePrimary = hex("#C5B2FF"),
        ),
        dark = DarkScheme.copy(
            primary = hex("#9F71FF"),
            onPrimary = Tone.Ink,
            primaryContainer = hex("#4A0096"),
            onPrimaryContainer = hex("#E1D9FF"),
            inversePrimary = hex("#6300C4"),
        ),
        accentLight = hex("#8100FB"),
        accentDark = hex("#B59AFF"),
    ),
    ColorPreset(
        id = "magenta",
        displayName = "Magenta",
        light = LightScheme.copy(
            primary = hex("#FD00B9"),
            onPrimary = Tone.Ink,
            primaryContainer = hex("#FFCFE9"),
            onPrimaryContainer = hex("#2E001F"),
            inversePrimary = hex("#FF9AD4"),
        ),
        dark = DarkScheme.copy(
            primary = hex("#FD00B9"),
            onPrimary = Tone.Ink,
            primaryContainer = hex("#6E004E"),
            onPrimaryContainer = hex("#FFCFE9"),
            inversePrimary = hex("#910069"),
        ),
        accentLight = hex("#BB0088"),
        accentDark = hex("#FF74C9"),
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
