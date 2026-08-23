package app.phueber.trigly.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * The four kinds of thing a regex is made of, as far as reading one goes.
 *
 * Deliberately coarse. A full parser would colour twenty token types and the
 * result is a rainbow nobody can read; the useful question while typing is only
 * ever "is this character *doing* something, and what kind of something".
 */
internal enum class RegexToken { LITERAL, ESCAPE, CLASS, QUANTIFIER, GROUP, ANCHOR }

/** The palette for [RegexToken], read from the theme so dark mode works. */
private class RegexColors(
    val literal: Color,
    val escape: Color,
    val charClass: Color,
    val quantifier: Color,
    val group: Color,
    val anchor: Color,
)

@Composable
@ReadOnlyComposable
private fun regexColors(): RegexColors = RegexColors(
    literal = MaterialTheme.colorScheme.onSurface,
    // The brand accent for the pieces that carry the most meaning.
    escape = MaterialTheme.colorScheme.primary,
    charClass = MaterialTheme.colorScheme.tertiary,
    quantifier = MaterialTheme.extra.caution,
    group = MaterialTheme.colorScheme.secondary,
    anchor = MaterialTheme.colorScheme.error,
)

/**
 * Splits a pattern into coloured runs.
 *
 * A hand-rolled scan rather than a regex, which would be both circular and
 * wrong: the thing being read is frequently *invalid* — someone is halfway
 * through typing it — and a parser that throws on bad input is useless for the
 * exact moments highlighting helps most. This never fails; unparseable input
 * simply comes out as literals.
 *
 * Escapes are consumed as a pair so the backslash and the character it protects
 * are coloured together, and so a `\[` is not mistaken for the start of a class.
 */
internal fun tokenize(pattern: String): List<Pair<IntRange, RegexToken>> {
    val tokens = mutableListOf<Pair<IntRange, RegexToken>>()
    var i = 0
    var inClass = false

    while (i < pattern.length) {
        val c = pattern[i]
        when {
            c == '\\' && i + 1 < pattern.length -> {
                tokens += (i..i + 1) to RegexToken.ESCAPE
                i += 2
                continue
            }

            // A lone trailing backslash: incomplete, but still an escape in
            // intent, and colouring it as one shows why the pattern is invalid.
            c == '\\' -> tokens += (i..i) to RegexToken.ESCAPE

            c == '[' -> { inClass = true; tokens += (i..i) to RegexToken.CLASS }
            c == ']' -> { inClass = false; tokens += (i..i) to RegexToken.CLASS }

            // Inside [...] the metacharacters are literal, so they stay plain.
            inClass -> tokens += (i..i) to RegexToken.LITERAL

            c == '(' || c == ')' || c == '|' -> tokens += (i..i) to RegexToken.GROUP
            c == '^' || c == '$' -> tokens += (i..i) to RegexToken.ANCHOR
            c == '*' || c == '+' || c == '?' -> tokens += (i..i) to RegexToken.QUANTIFIER

            c == '{' -> {
                val close = pattern.indexOf('}', i)
                val range = if (close == -1) i..i else i..close
                tokens += range to RegexToken.QUANTIFIER
                i = range.last + 1
                continue
            }

            c == '.' -> tokens += (i..i) to RegexToken.CLASS

            else -> tokens += (i..i) to RegexToken.LITERAL
        }
        i++
    }
    return tokens
}

/**
 * Colours a regular expression as it is typed.
 *
 * Offsets are untouched — nothing is inserted or removed — so the cursor and
 * selection map straight through and [OffsetMapping.Identity] is honest rather
 * than merely convenient.
 */
@Composable
fun rememberRegexHighlight(enabled: Boolean): VisualTransformation {
    val colors = regexColors()

    return if (!enabled) {
        VisualTransformation.None
    } else {
        VisualTransformation { text ->
            TransformedText(highlightRegex(text.text, colors), OffsetMapping.Identity)
        }
    }
}

private fun highlightRegex(pattern: String, colors: RegexColors): AnnotatedString =
    buildAnnotatedString {
        append(pattern)
        // Monospaced throughout: a pattern is code, and proportional spacing
        // makes it very hard to see that `[ ]` contains a space.
        addStyle(SpanStyle(fontFamily = FontFamily.Monospace), 0, pattern.length)

        tokenize(pattern).forEach { (range, token) ->
            val color = when (token) {
                RegexToken.LITERAL -> colors.literal
                RegexToken.ESCAPE -> colors.escape
                RegexToken.CLASS -> colors.charClass
                RegexToken.QUANTIFIER -> colors.quantifier
                RegexToken.GROUP -> colors.group
                RegexToken.ANCHOR -> colors.anchor
            }
            addStyle(
                SpanStyle(
                    color = color,
                    fontWeight = if (token == RegexToken.LITERAL) {
                        FontWeight.Normal
                    } else {
                        FontWeight.Bold
                    },
                ),
                range.first,
                minOf(range.last + 1, pattern.length),
            )
        }
    }
