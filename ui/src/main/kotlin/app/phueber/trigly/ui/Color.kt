package app.phueber.trigly.ui

import androidx.compose.ui.graphics.Color

/**
 * The palette, as tonal steps rather than named colours.
 *
 * Trigly's brand colour is a warm orange (source hue ~27°). Material 3 wants a
 * *tonal palette* per role, not one hex per role: the same hue at a dozen
 * lightness steps, from which both the light and the dark scheme are assembled.
 * Naming the tones instead of the uses is what makes that reuse visible — light
 * `primary` and dark `onPrimaryContainer` are the same tone, and that is a fact
 * about the palette rather than a coincidence to be maintained in two places.
 *
 * Numbers are Material tone values: 0 is black, 100 is white, and 40 and 80 are
 * the two anchors the light and dark schemes are built around. To re-brand the
 * app, change the hue of these tones and nothing else.
 */
internal object Tone {

    // Primary — the orange. Chroma stays high; this is the colour the app is.
    val Orange10 = Color(0xFF351000)
    val Orange20 = Color(0xFF561D00)
    val Orange30 = Color(0xFF7A2C00)
    val Orange40 = Color(0xFF9F3D00)
    val Orange60 = Color(0xFFEC6206)
    val Orange80 = Color(0xFFFFB68F)
    val Orange90 = Color(0xFFFFDBC8)

    // Secondary — the same hue with the chroma pulled out, for surfaces and
    // chips that must sit beside the orange without competing with it.
    val Warm10 = Color(0xFF2C1608)
    val Warm20 = Color(0xFF45291A)
    val Warm30 = Color(0xFF5E3F2F)
    val Warm40 = Color(0xFF785645)
    val Warm80 = Color(0xFFE7BEA9)
    val Warm90 = Color(0xFFFFDBC8)

    // Tertiary — an olive, roughly complementary. Used sparingly: it is what
    // keeps an all-orange screen from reading as a single wash.
    val Olive10 = Color(0xFF1A1D00)
    val Olive20 = Color(0xFF2E3300)
    val Olive30 = Color(0xFF444A00)
    val Olive40 = Color(0xFF5C6300)
    val Olive80 = Color(0xFFC5CD73)
    val Olive90 = Color(0xFFE1E98C)

    // Neutrals, deliberately warm-tinted. A pure grey surface under an orange
    // accent looks accidental; these carry a trace of the same hue.
    val Neutral6 = Color(0xFF17100C)
    val Neutral10 = Color(0xFF211A16)
    val Neutral12 = Color(0xFF261E19)
    val Neutral17 = Color(0xFF322922)
    val Neutral20 = Color(0xFF382E29)
    val Neutral22 = Color(0xFF3D332D)
    val Neutral24 = Color(0xFF423831)
    val Neutral90 = Color(0xFFF1DFD7)
    val Neutral94 = Color(0xFFFBEDE6)
    val Neutral96 = Color(0xFFFFF2EB)
    val Neutral98 = Color(0xFFFFF8F5)
    val Neutral100 = Color(0xFFFFFFFF)

    val NeutralVariant30 = Color(0xFF53433C)
    val NeutralVariant50 = Color(0xFF85736B)
    val NeutralVariant60 = Color(0xFFA08D85)
    val NeutralVariant80 = Color(0xFFD8C2B9)
    val NeutralVariant90 = Color(0xFFF5DED4)

    // Error stays red. Orange is the brand colour, so a failure cannot also be
    // orange — it would be indistinguishable from ordinary emphasis.
    val Red10 = Color(0xFF410002)
    val Red20 = Color(0xFF690005)
    val Red30 = Color(0xFF93000A)
    val Red40 = Color(0xFFBA1A1A)
    val Red80 = Color(0xFFFFB4AB)
    val Red90 = Color(0xFFFFDAD6)

    // Caution — amber, for "this will work, but you should know something".
    // Deliberately distinct from both the brand orange and the error red; see
    // TriglyExtraColors for why warnings are not errors.
    val Amber20 = Color(0xFF422C00)
    val Amber30 = Color(0xFF5E4000)
    val Amber40 = Color(0xFF8A5D00)
    val Amber80 = Color(0xFFFFC46B)
    val Amber90 = Color(0xFFFFDFA8)
    val Amber95 = Color(0xFFFFEEDC)
}
