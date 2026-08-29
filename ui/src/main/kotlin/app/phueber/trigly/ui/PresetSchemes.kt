package app.phueber.trigly.ui

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color

/*
 * ─────────────────────────────────────────────────────────────────────────────
 *  THE COLOUR SCHEME PICKER: NINE PRESETS, PLUS THE SWITCH BETWEEN
 *  DEFAULT / A PRESET / THE SYSTEM WALLPAPER PALETTE.
 * ─────────────────────────────────────────────────────────────────────────────
 *
 *  `Palette.kt` still holds every colour used until someone opens Settings:
 *  the fixed Default scheme, `Tone`, and the roles Material 3 has no slot
 *  for. This file holds the eight other presets a person can switch *to* -
 *  six brand hues and two neutral greys - and the logic that turns a stored
 *  choice into the colours `TriglyTheme` and `EngineService`'s notification
 *  actually render. "Follow the system" is not a colour at all here - it is
 *  resolved on the device, from the wallpaper, through
 *  `dynamicLightColorScheme`/`dynamicDarkColorScheme`.
 *
 *  ── HOW THE FIVE NEW RAMPS WERE MADE ─────────────────────────────────────
 *
 *  These five ramps replace two earlier versions. The first rotated hue in
 *  HSL at fixed saturation and fixed lightness - HSL lightness is not
 *  perceptual, so the old Lime read as neon and the old Azure and Violet read
 *  as muddy next to a good Orange, at matching numbers. The second held
 *  lightness level in OKLCH but let each hue's chroma run to its own gamut
 *  limit - Magenta's hue can hold far more chroma than Azure's or Lime's can,
 *  so that set spanned chroma 0.15 to 0.28 and did not read as one family either:
 *  a more chromatic colour looks lighter than a less chromatic one at the same
 *  measured `L`, so the uneven chroma read as an uneven lightness ladder even
 *  though the ladder itself was level.
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
 *   3. For each of the eight tones, and for each of the five hues, hold `L`
 *      to step 1's value for that tone and `H` to step 2's value for that
 *      hue, and solve for the largest `C` whose OKLab-to-linear-sRGB
 *      conversion still lands every channel in [0, 1] - bisection on `C`
 *      between 0 and 0.5, 60 iterations, which settles the boundary to far
 *      better precision than 8-bit sRGB can show. This gives five candidate
 *      chromas per tone, one per hue, and they are not equal: the most
 *      colourful colour sRGB can hold at one `L, H` is a different number at
 *      every hue.
 *   4. Take the **minimum** of the five candidate chromas at each tone, then
 *      subtract a safety margin of 0.004 so the result does not sit exactly
 *      on the gamut boundary, where rounding to 8-bit sRGB could push a
 *      channel out of range and clip it. Use that one chroma for all five
 *      presets at that tone - this is the step that must not be skipped, and
 *      the one the earlier per-hue-maximised set skipped. Chroma differs
 *      between tones, because the gamut genuinely narrows at very light and
 *      very dark tones; chroma does not differ between presets at the same
 *      tone, because that is the property that makes five different hues
 *      read as one family instead of five swatches of different punch.
 *      Measured, tone: `L`, shared `C`, and which hue held the ceiling:
 *      tone 10: `L` 0.2016, `C` 0.0422 (Azure ceiling 0.0462); tone 30: `L`
 *      0.3586, `C` 0.0781 (Azure 0.0821); tone 40: `L` 0.4378, `C` 0.0963
 *      (Azure 0.1003); tone 50: `L` 0.5280, `C` 0.1169 (Azure 0.1209); tone
 *      60: `L` 0.6618, `C` 0.1476 (Azure 0.1516); tone 70: `L` 0.7500, `C`
 *      0.1396 (Azure 0.1436); tone 80: `L` 0.8069, `C` 0.1046 (Azure 0.1086);
 *      tone 90: `L` 0.9041, `C` 0.0478 (Violet 0.0518). Azure's hue holds the
 *      weakest chroma at every tone but the lightest, where Violet's does.
 *   5. Round-trip every result hex back through step 1's conversion. Every
 *      preset's `L` at every one of the eight tones lands within 0.0019 of
 *      Orange's own `L` at that tone, and every preset's `C` at every tone
 *      lands within 0.0014 of the shared value step 4 chose for that tone -
 *      see the commit message for the full 8-tone-by-5-preset table. That is
 *      the check the earlier two ramps would have failed had anyone run it,
 *      the first on `L`, the second on `C`.
 *   6. Map the eight tones onto the roles Orange's own ramp already uses:
 *      tone 60 is `primary` in both schemes; tone 90 is light's
 *      `primaryContainer` and dark's `onPrimaryContainer`; tone 10 is light's
 *      `onPrimaryContainer`; tone 30 is dark's `primaryContainer`; tone 80 is
 *      light's `inversePrimary`; tone 40 is dark's `inversePrimary`; tone 50
 *      is [TriglyExtraColors.accent] in light; tone 70 is the same in dark.
 *   7. `onPrimary`: every preset's tone 60 clears WCAG AA against `Tone.Ink`
 *      (5.65:1 to 6.51:1, against Orange's own 5.66:1), so every preset uses
 *      `Tone.Ink`, the same as Default.
 *
 *  Orange itself is untouched by all of this - it is the default, not one of
 *  the five hues step 3 to step 4 run over, and the owner asked for it to
 *  stay byte for byte what it already was. Its own chroma at tone 60 (0.1889)
 *  is therefore higher than the shared 0.1476 the other five hold to, because
 *  Orange's hue can carry more than Azure's, the hue that set that ceiling.
 *  Orange is the reference the five-hue family sits beside, not a sixth
 *  member of it, and it reads slightly more vivid than the other five for
 *  exactly that reason.
 *
 *  Checked against the fixed caution amber (`Tone.Amber40`/`Amber80`, hue
 *  76.06/80.44 degrees in the same OKLCH space): every preset's tone-60 hue
 *  sits further from amber than Orange's own 31.49 degrees does. Lime is
 *  closest at 37.7 (against Amber80) to 42.1 (against Amber40) degrees; Green
 *  71.6 to 76.0; Magenta 91.0 to 95.4; Violet 141.3 to 145.6; Azure 159.7 to
 *  164.1. None reads as the caution colour.
 *
 *  ── ADDING RED: A SIXTH HUE IN THE SHARED-CHROMA FAMILY ──────────────────
 *
 *  Red joined the shared-chroma family the same way the first five did: hold
 *  `L` and the shared per-tone `C` from step 1 and step 4 above, and solve
 *  for hue 27 degrees instead. That hue was picked the same way the other
 *  five were, by matching known red swatches through this file's own OKLCH
 *  math: pure sRGB red `#FF0000` measures hue 29.23, Material Red 500
 *  measures hue 28.81. 27 sits close to both.
 *
 *  Red's own gamut ceiling stays above the shared floor at every one of the
 *  eight tones, so it needed no exception, the same as Lime, Green, Magenta
 *  and (mostly) Violet before it. The result:
 *
 *  tone 10 `#270E0C`, tone 30 `#5F2A26`, tone 40 `#7F3A34`, tone 50 `#A44D45`,
 *  tone 60 (primary) `#DF6A60`, tone 70 `#FA897D`, tone 80 `#FCA69B`, tone 90
 *  `#FED4CE`. Round-tripped at the primary: `L` 0.6622, `C` 0.1482, `H`
 *  26.80, within the same tolerance the other five hold to. `onPrimary`
 *  clears AA at 5.71:1, in range with the rest of the family (5.65:1 to
 *  6.51:1). Distance from the caution amber (hue 76.06/80.44): 49.3 and
 *  53.6 degrees, further than Orange's own 31.5, so it does not read as the
 *  caution colour.
 *
 *  The cost, disclosed rather than fixed silently. This family's shared
 *  chroma at tone 60 is 0.1476. Pure red's own ceiling at that lightness is
 *  0.2248, about one and a half times higher, so the shared floor lets this
 *  hue use only about two thirds of the saturation it could otherwise
 *  carry. `#DF6A60` reads as a warm coral or salmon red, not a fire-engine
 *  red. It keeps the name "Red" anyway, the same way Lime kept its id under
 *  the label "Olive": raising red's own chroma above the shared floor would
 *  single it out from the other five and undo the point of a shared
 *  ceiling.
 *
 *  ── THE TWO GREYS: STONE AND SLATE, A DELIBERATE EXCEPTION ───────────────
 *
 *  Every preset above holds two things in common: the eight-tone lightness
 *  ladder, and the shared chroma at each tone. A grey cannot hold the second
 *  one and still read as grey, so Stone and Slate hold only the ladder. Each
 *  picks its own small chroma instead, held constant across all eight tones
 *  the way the shared value is held constant for the other presets, so each
 *  grey is internally consistent even though it sits outside the family
 *  rule. This is a deliberate exception, not an oversight: a grey preset is
 *  not a ninth hue, it is the one preset that is mostly not a hue at all.
 *
 *  **Hue.** This app's own neutrals already carry a warm cast: `Tone.Paper`
 *  (`#FFFBF7`) measures hue 67.75 at chroma 0.0068, `Tone.Ink` (`#17100C`)
 *  measures hue 51.24 at chroma 0.0143, and the `Warm20`-`Warm80` ramp sits
 *  between hue 45.4 and 57.3. A perfectly neutral grey (chroma 0) would read
 *  as faintly cold next to all of that. So Stone, the warm grey, sits at hue
 *  50 degrees, inside that same existing band, not a hue invented for this
 *  preset. Slate, the cool grey, sits at hue 230 degrees, the far side of
 *  the wheel from Stone and close to this app's own cool hues (Azure's 240,
 *  the `Blue` secondary ramp's 258 to 266), so "cool" also means something
 *  the app already has a hue for, not an arbitrary opposite.
 *
 *  **Chroma.** 0.025, held constant across all eight tones for both greys.
 *  That is far below the shared family floor at every tone (0.0422 to
 *  0.1476), and sits close to this app's own existing neutral precedent:
 *  `Tone.Neutral90` measures chroma 0.0205, `Tone.Warm50` measures 0.0437.
 *  0.025 sits between those two - enough to carry a visible warm or cool
 *  cast, not enough to compete with any hued preset's own chroma. Each
 *  hue's own gamut ceiling stays far above 0.025 at every tone (worst case
 *  0.0402, at Slate's hue at tone 10), so neither grey ever needs a per-tone
 *  exception the way Indigo would have. The low chroma itself is the
 *  exception here, not a gamut limit.
 *
 *  **Lightness, the second deliberate exception.** Two greys at the same
 *  lightness, 180 degrees of hue apart, at chroma 0.025, still read as close
 *  to the same swatch at picker size - hue alone is a weak signal when
 *  there is almost no chroma to carry it. So Stone's `primary` and
 *  `onPrimary` point at tone 70 of its own ramp, one rung lighter than every
 *  other preset's tone 60, while Slate keeps the normal tone 60. This is
 *  the one instance in this file where a preset's primary is not tone 60 -
 *  it reuses a rung the shared ladder already measures, not a lightness
 *  invented for this preset, so it costs no new number and no second
 *  ladder. Tone 70 is also where the normal role mapping already puts dark
 *  mode's `accent`, so for Stone alone `primary` and `accentDark` land on
 *  the identical hex. That is a coincidence of reusing an already-occupied
 *  rung, not a special case in the code; Stone's own tone-60 rung is still
 *  computed below for completeness, it is simply unused by any role.
 *
 *  Measured, both ramps (hue held to 50/230, chroma to 0.025, `L` from the
 *  same eight-tone ladder every preset uses):
 *
 *  Stone: tone 10 `#20130C`, tone 30 `#483931`, tone 40 `#5E4E46`, tone 50
 *  `#78675F`, tone 60 `#A08F85` (unused, see above), tone 70 (primary)
 *  `#BCAAA0`, tone 80 `#CEBCB2`, tone 90 `#EEDBD1`.
 *
 *  Slate: tone 10 `#091920`, tone 30 `#2F3F47`, tone 40 `#44555D`, tone 50
 *  `#5D6E77`, tone 60 (primary) `#84969F`, tone 70 `#9FB1BB`, tone 80
 *  `#B0C3CD`, tone 90 `#CFE3ED`.
 *
 *  **Separation, checked rather than assumed.** Stone's primary (`L`
 *  0.7506, `C` 0.0253, `H` 51.06) against Slate's primary (`L` 0.6619, `C`
 *  0.0246, `H` 229.29): lightness differs by 0.089 (one full ladder rung),
 *  the hue-driven distance in the `a`/`b` plane is 0.050, and the combined
 *  OKLab distance is 0.102. Both axes separate the pair, not just one, so
 *  the two read as a deliberate light warm swatch and a deliberate mid cool
 *  swatch side by side, not as one grey someone forgot to finish.
 *
 *  **Contrast.** `onPrimary`/`primary`: Stone 8.42:1 (up from 6.06:1 before
 *  the lightness move - ink on a lighter fill only gains contrast), Slate
 *  6.13:1. Both clear AA with room to spare, and Stone's is the highest in
 *  this whole file, again the direction a lighter fill moves it.
 *  `onPrimaryContainer`/`primaryContainer`: Stone 13.53:1 light, 8.23:1
 *  dark; Slate 13.54:1 light, 8.25:1 dark. `accent` against the page it
 *  writes on: Stone 5.22:1 light, 8.42:1 dark; Slate 5.15:1 light, 8.50:1
 *  dark. Every pairing clears the 4.5:1 floor [ColorPresetContrastTest]
 *  checks.
 *
 *  **The launcher icon.** Every preset's launcher icon draws the same fixed
 *  ink foreground (`#17100C`) over that preset's own primary. Moving
 *  Stone's primary to a lighter tone only widens that gap - 8.42:1 against
 *  the icon background, higher than any other preset's - so the mark stays
 *  legible, more so than before the lightness move.
 *
 *  **The caution amber, in a scheme with almost no hue at all.** Amber's own
 *  chroma (0.108 at `Amber40`, 0.147 at `Amber80`) is four to six times
 *  Stone's and Slate's 0.025. Stone's hue (measured 51.06 at its primary) is
 *  the closest of any preset to amber's (76.06/80.44): 25.0 and 29.4
 *  degrees away, versus Orange's own 31.5. Closer in hue, yes, but at this
 *  chroma ratio the two do not compete: amber stays the only saturated
 *  colour anywhere on a Stone or Slate screen, which if anything makes it
 *  read as the deliberate warning it is meant to be, not a colour that
 *  happens to match the brand. There is nothing else on screen to mistake
 *  it for.
 *
 *  **Names.** "Stone" and "Slate" are both plain, real English colour
 *  names, and both still describe what renders after the lightness move: a
 *  light warm greige reads as stone as well as, if not better than, a
 *  mid-tone one would, and a mid cool blue-grey is exactly what "slate"
 *  already names. Neither needed the Lime-to-Olive or Magenta-to-Rose kind
 *  of correction, so `id` and `displayName` match for both, the same as
 *  Red.
 *
 *  ── ADDING MORE PRESETS ──────────────────────────────────────────────────
 *
 *  One more [ColorPreset] entry in [ColorPresets]. The picker, the switcher
 *  and the contrast test all walk this list, so no existing entry changes.
 *  A hue that can share the family's chroma follows the method above; a
 *  grey-style exception follows Stone and Slate's method instead. Either
 *  way, the one file that cannot follow this rule is the manifest: an
 *  `activity-alias` cannot be declared at runtime, so stage 2's launcher
 *  icon still costs one alias and one colour resource per preset.
 */

/**
 * One colour-scheme choice offered on the picker's grid.
 *
 * @property id Stored in `ColorSchemeSettings`, and (from stage 2) the suffix
 *   naming this preset's launcher-icon alias and background colour resource.
 *   Lower-case, one word, and never renamed once shipped - a rename would
 *   silently orphan anyone's stored choice back to Default.
 * @property displayName What the picker's swatch and the settings row show.
 *  **This is deliberately allowed to differ from [id].** Two of them do:
 *  `lime` shows as "Olive" and `magenta` shows as "Rose", because that is what
 *  the colours are once they are held to the shared lightness and chroma. A
 *  real lime is much lighter than a mid tone, and a real magenta is far more
 *  colourful than the shared ceiling allows, so those two names promised
 *  something the swatches do not deliver. `red`, `stone` and `slate` do not
 *  diverge - see this file's banner for why each of those three names was
 *  checked and kept as its own id.
 *
 *  The ids stay as they were on purpose. [id] is written into a person's
 *  stored choice and it is what `launcherAliasName` turns into the manifest's
 *  `activity-alias` name, so renaming one would drop a saved preference and
 *  point the icon switch at a component that does not exist. A label is free
 *  to change; an identifier is not.
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
 * The nine presets, Orange included - see this file's banner for how the
 * other eight were produced. Order is the order the picker's grid renders
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
        displayName = "Olive",
        light = LightScheme.copy(
            primary = hex("#8B9E18"),
            onPrimary = Tone.Ink,
            primaryContainer = hex("#DCE4C1"),
            onPrimaryContainer = hex("#151902"),
            inversePrimary = hex("#BAC97A"),
        ),
        dark = DarkScheme.copy(
            primary = hex("#8B9E18"),
            onPrimary = Tone.Ink,
            primaryContainer = hex("#394207"),
            onPrimaryContainer = hex("#DCE4C1"),
            inversePrimary = hex("#4E590B"),
        ),
        accentLight = hex("#667410"),
        accentDark = hex("#A6B948"),
    ),
    ColorPreset(
        id = "green",
        displayName = "Green",
        light = LightScheme.copy(
            primary = hex("#3AAC64"),
            onPrimary = Tone.Ink,
            primaryContainer = hex("#C9E9D0"),
            onPrimaryContainer = hex("#051C0C"),
            inversePrimary = hex("#8BD49F"),
        ),
        dark = DarkScheme.copy(
            primary = hex("#3AAC64"),
            onPrimary = Tone.Ink,
            primaryContainer = hex("#154827"),
            onPrimaryContainer = hex("#C9E9D0"),
            inversePrimary = hex("#1E6136"),
        ),
        accentLight = hex("#297E48"),
        accentDark = hex("#60C781"),
    ),
    ColorPreset(
        id = "azure",
        displayName = "Azure",
        light = LightScheme.copy(
            primary = hex("#139CE3"),
            onPrimary = Tone.Ink,
            primaryContainer = hex("#C4E4FD"),
            onPrimaryContainer = hex("#021827"),
            inversePrimary = hex("#7EC9FD"),
        ),
        dark = DarkScheme.copy(
            primary = hex("#139CE3"),
            onPrimary = Tone.Ink,
            primaryContainer = hex("#064161"),
            onPrimaryContainer = hex("#C4E4FD"),
            inversePrimary = hex("#0A5881"),
        ),
        accentLight = hex("#0D72A7"),
        accentDark = hex("#4BB8FD"),
    ),
    ColorPreset(
        id = "violet",
        displayName = "Violet",
        light = LightScheme.copy(
            primary = hex("#9A7EE3"),
            onPrimary = Tone.Ink,
            primaryContainer = hex("#E1DAFD"),
            onPrimaryContainer = hex("#181227"),
            inversePrimary = hex("#C4B3FD"),
        ),
        dark = DarkScheme.copy(
            primary = hex("#9A7EE3"),
            onPrimary = Tone.Ink,
            primaryContainer = hex("#403361"),
            onPrimaryContainer = hex("#E1DAFD"),
            inversePrimary = hex("#564681"),
        ),
        accentLight = hex("#715CA7"),
        accentDark = hex("#B59BFD"),
    ),
    ColorPreset(
        id = "magenta",
        displayName = "Rose",
        light = LightScheme.copy(
            primary = hex("#D06AA7"),
            onPrimary = Tone.Ink,
            primaryContainer = hex("#F8D3E7"),
            onPrimaryContainer = hex("#240E1B"),
            inversePrimary = hex("#F1A5CF"),
        ),
        dark = DarkScheme.copy(
            primary = hex("#D06AA7"),
            onPrimary = Tone.Ink,
            primaryContainer = hex("#592A46"),
            onPrimaryContainer = hex("#F8D3E7"),
            inversePrimary = hex("#763A5E"),
        ),
        accentLight = hex("#994D7B"),
        accentDark = hex("#EC88C2"),
    ),
    ColorPreset(
        id = "red",
        displayName = "Red",
        light = LightScheme.copy(
            primary = hex("#DF6A60"),
            onPrimary = Tone.Ink,
            primaryContainer = hex("#FED4CE"),
            onPrimaryContainer = hex("#270E0C"),
            inversePrimary = hex("#FCA69B"),
        ),
        dark = DarkScheme.copy(
            primary = hex("#DF6A60"),
            onPrimary = Tone.Ink,
            primaryContainer = hex("#5F2A26"),
            onPrimaryContainer = hex("#FED4CE"),
            inversePrimary = hex("#7F3A34"),
        ),
        accentLight = hex("#A44D45"),
        accentDark = hex("#FA897D"),
    ),
    ColorPreset(
        id = "stone",
        displayName = "Stone",
        // Warm grey: a deliberate exception to the shared-chroma family, not
        // a seventh hue. Primary points at tone 70 of its own ramp, one
        // rung lighter than every other preset's tone 60 - see this file's
        // banner for why.
        light = LightScheme.copy(
            primary = hex("#BCAAA0"),
            onPrimary = Tone.Ink,
            primaryContainer = hex("#EEDBD1"),
            onPrimaryContainer = hex("#20130C"),
            inversePrimary = hex("#CEBCB2"),
        ),
        dark = DarkScheme.copy(
            primary = hex("#BCAAA0"),
            onPrimary = Tone.Ink,
            primaryContainer = hex("#483931"),
            onPrimaryContainer = hex("#EEDBD1"),
            inversePrimary = hex("#5E4E46"),
        ),
        accentLight = hex("#78675F"),
        accentDark = hex("#BCAAA0"),
    ),
    ColorPreset(
        id = "slate",
        displayName = "Slate",
        // Cool grey: the other half of the deliberate exception above.
        // Keeps the normal tone 60 as primary, on purpose - see this file's
        // banner for why the two greys differ in lightness as well as hue.
        light = LightScheme.copy(
            primary = hex("#84969F"),
            onPrimary = Tone.Ink,
            primaryContainer = hex("#CFE3ED"),
            onPrimaryContainer = hex("#091920"),
            inversePrimary = hex("#B0C3CD"),
        ),
        dark = DarkScheme.copy(
            primary = hex("#84969F"),
            onPrimary = Tone.Ink,
            primaryContainer = hex("#2F3F47"),
            onPrimaryContainer = hex("#CFE3ED"),
            inversePrimary = hex("#44555D"),
        ),
        accentLight = hex("#5D6E77"),
        accentDark = hex("#9FB1BB"),
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
