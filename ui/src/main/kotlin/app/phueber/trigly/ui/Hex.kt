package app.phueber.trigly.ui

import androidx.compose.ui.graphics.Color

/*
 * ONE FUNCTION, AND IT HAS A FILE TO ITSELF ON PURPOSE.
 *
 * This lived in `Palette.kt` until it was found to poison every colour in the
 * app under one class loading order. Kotlin compiles a top level function into
 * a class named after its file, and calling a static method initialises that
 * class. `Tone`'s constants are all `hex("#...")` calls, so with `hex` in
 * `Palette.kt` the first touch of `Tone` ran `PaletteKt`'s own initialiser,
 * which builds `LightScheme`, `DarkScheme` and the two extra palettes out of
 * `Tone` fields that were still unset. Every one of them came out as
 * transparent black, for the life of the process, and nothing threw.
 *
 * Whether it happened at all was decided by which of the two was touched
 * first, so the app was correct or completely unreadable depending on class
 * loading order. `ColorPresetContrastTest` caught it as "orange light
 * onPrimary/primary is 1.0:1" once a test read `Tone` before any scheme.
 *
 * Here there is no cycle to have: this file names nothing in `Palette.kt`, so
 * `Tone` can initialise on its own. Keep it that way. Anything this function
 * comes to need belongs in this file beside it, not in the file that holds the
 * schemes.
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
