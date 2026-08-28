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
import app.phueber.trigly.core.FUNCTION_CONTAINS
import app.phueber.trigly.core.FUNCTION_LENGTH
import app.phueber.trigly.core.FUNCTION_LOWER
import app.phueber.trigly.core.FUNCTION_ROUND
import app.phueber.trigly.core.FUNCTION_TRIM
import app.phueber.trigly.core.FUNCTION_UPPER

/**
 * The kinds of thing an expression is made of, as far as reading one goes.
 *
 * Coarse on purpose, the same choice [RegexToken] makes: the useful question
 * while typing is "is this doing something, and what kind of something", and a
 * colour per grammar production is a rainbow nobody can read.
 *
 * [NUMBER] and [TEXT] are separate for a reason specific to this
 * language rather than cosmetic. It has no casts, and `5` never equals `"5"`,
 * so which of the two a piece of an expression *is* decides the answer. The
 * colour is the only place a person can see that at a glance, and a substituted
 * `{{app.count}}` arriving bare where its author expected quotes is the mistake
 * this catches.
 */
internal enum class ExpressionToken { REFERENCE, TEXT, NUMBER, KEYWORD, FUNCTION, OPERATOR, PLAIN }

/** The words the grammar reserves. Not functions: `and` and `or` short-circuit. */
private val KEYWORDS = setOf("and", "or", "not", "true", "false")

/** The six, from `:core`, so a seventh cannot appear here without appearing there. */
private val FUNCTIONS = setOf(
    FUNCTION_UPPER,
    FUNCTION_LOWER,
    FUNCTION_TRIM,
    FUNCTION_LENGTH,
    FUNCTION_CONTAINS,
    FUNCTION_ROUND,
)

/**
 * The symbols the lexer knows, duplicated from `Expression.kt` because they are
 * private to it.
 *
 * The duplication is deliberate and the drift is survivable: an operator missing
 * from here is drawn as [ExpressionToken.PLAIN], which reads as "this does
 * nothing" rather than as something wrong. Adding an operator to the language
 * means adding it here too, and forgetting costs a colour, not a meaning.
 */
private val TWO_CHAR_OPERATORS = setOf("<=", ">=", "==", "!=")
private val ONE_CHAR_OPERATORS =
    setOf('+', '-', '*', '/', '%', '<', '>', '(', ')', ',', '?', ':')

/** The palette for [ExpressionToken], read from the theme so dark mode works. */
private class ExpressionColors(
    val reference: Color,
    val text: Color,
    val number: Color,
    val keyword: Color,
    val function: Color,
    val operator: Color,
    val plain: Color,
)

@Composable
@ReadOnlyComposable
private fun expressionColors(): ExpressionColors = ExpressionColors(
    // The brand accent for the piece that carries the most meaning. Before it
    // runs, a reference is the only part of an expression whose value nobody
    // reading the box knows.
    reference = MaterialTheme.extra.accent,
    text = MaterialTheme.colorScheme.tertiary,
    number = MaterialTheme.colorScheme.primary,
    // The same role RegexHighlight gives a quantifier: valid, and quietly
    // changing what the rest means. `and` and `or` decide whether the other
    // side runs at all.
    keyword = MaterialTheme.extra.caution,
    function = MaterialTheme.colorScheme.secondary,
    // Dimmer than what they operate on. An expression is read for its values;
    // the punctuation between them is structure, and colouring it as loudly as
    // an operand would bury the operands.
    operator = MaterialTheme.colorScheme.onSurfaceVariant,
    plain = MaterialTheme.colorScheme.onSurface,
)

/**
 * Splits an expression into coloured runs.
 *
 * A hand-rolled scan rather than the real lexer, for the reason [tokenize] is
 * one for patterns: this runs on every keystroke, so most of what it reads is
 * *invalid*: half a string, half a reference, a name nobody has finished
 * typing. The lexer throws on that, which is useless in exactly the moments
 * highlighting helps most. This never fails, and anything it cannot account for
 * comes out plain.
 *
 * It also reads the text *before* substitution, which the lexer never sees at
 * all: `{{...}}` is not in the grammar, and it is the first thing a person
 * wants to see marked.
 *
 * A word that is neither a keyword nor one of the six functions comes out as
 * [ExpressionToken.PLAIN] rather than marked as wrong. That is honest: a bare
 * word does nothing in this language, and plain is what "does nothing" looks
 * like everywhere else here. Saying more than that is the job of a message
 * under the field, not of a colour.
 */
internal fun tokenizeExpression(source: String): List<Pair<IntRange, ExpressionToken>> {
    val tokens = mutableListOf<Pair<IntRange, ExpressionToken>>()
    var i = 0

    // References first, over the whole box, and deliberately *including* the
    // ones inside quotes. Substitution does not respect quotes: `"{{app.state}}"`
    // still resolves, and inserts a value that already carries its own quotes,
    // which is the mistake `docs/expressions.md` warns about. A reference that
    // kept its colour inside a string is the warning; one hidden in the string
    // colour would suggest the quotes protect it.
    while (i < source.length) {
        val start = source.indexOf("{{", startIndex = i)
        if (start == -1) {
            tokens += scanCode(source, i, source.length)
            break
        }
        if (start > i) tokens += scanCode(source, i, start)
        val close = source.indexOf("}}", startIndex = start + 2)
        // An unclosed reference runs to the end of the box, which is what one
        // looks like the moment after somebody types the second brace.
        val end = if (close == -1) source.length - 1 else close + 1
        tokens += (start..end) to ExpressionToken.REFERENCE
        i = end + 1
    }
    return tokens
}

/**
 * Everything that is not a reference, over `source[from until until]`.
 *
 * Bounded by [until] rather than by the end of the string, because a reference
 * ends whatever was being read: a string that opens before one and closes after
 * it is already broken, and the pieces on either side are what a person needs
 * to see.
 */
private fun scanCode(
    source: String,
    from: Int,
    until: Int,
): List<Pair<IntRange, ExpressionToken>> {
    val tokens = mutableListOf<Pair<IntRange, ExpressionToken>>()
    var i = from

    while (i < until) {
        val char = source[i]
        when {
            char == '"' -> {
                // An escaped quote does not close the string, so the pair is
                // stepped over. An unclosed one runs to the end of the region,
                // which is what it looks like while it is being typed.
                var at = i + 1
                while (at < until && source[at] != '"') {
                    at += if (source[at] == '\\' && at + 1 < until) 2 else 1
                }
                val end = if (at < until) at else until - 1
                tokens += (i..end) to ExpressionToken.TEXT
                i = end + 1
            }

            char.isDigit() -> {
                var at = i
                while (at < until && source[at].isDigit()) at++
                // A '.' is part of the number only with a digit after it, the
                // same rule the lexer's readNumber follows.
                if (at + 1 < until && source[at] == '.' && source[at + 1].isDigit()) {
                    at++
                    while (at < until && source[at].isDigit()) at++
                }
                tokens += (i until at) to ExpressionToken.NUMBER
                i = at
            }

            char.isLetter() || char == '_' -> {
                var at = i
                while (at < until && (source[at].isLetterOrDigit() || source[at] == '_')) at++
                tokens += (i until at) to when (source.substring(i, at)) {
                    in KEYWORDS -> ExpressionToken.KEYWORD
                    // By name alone, without waiting for the '('. The point of
                    // the colour is to confirm the spelling while it is being
                    // typed, which is before the bracket exists.
                    in FUNCTIONS -> ExpressionToken.FUNCTION
                    else -> ExpressionToken.PLAIN
                }
                i = at
            }

            else -> {
                val two = source.substring(i, minOf(i + 2, until))
                if (two in TWO_CHAR_OPERATORS) {
                    tokens += (i..i + 1) to ExpressionToken.OPERATOR
                    i += 2
                } else {
                    // Whitespace and anything unknown land here as plain, so
                    // every character in the box is accounted for exactly once.
                    tokens += (i..i) to if (char in ONE_CHAR_OPERATORS) {
                        ExpressionToken.OPERATOR
                    } else {
                        ExpressionToken.PLAIN
                    }
                    i++
                }
            }
        }
    }
    return tokens
}

/**
 * Colours an expression as it is typed.
 *
 * Offsets are untouched, so the cursor and the selection map straight through
 * and [OffsetMapping.Identity] is honest rather than merely convenient. That is
 * what makes this safe to put on a field somebody is editing: nothing is
 * inserted, removed or reordered.
 */
@Composable
fun rememberExpressionHighlight(enabled: Boolean): VisualTransformation {
    val colors = expressionColors()

    return if (!enabled) {
        VisualTransformation.None
    } else {
        VisualTransformation { text ->
            TransformedText(highlightExpression(text.text, colors), OffsetMapping.Identity)
        }
    }
}

private fun highlightExpression(source: String, colors: ExpressionColors): AnnotatedString =
    buildAnnotatedString {
        append(source)
        // Monospaced throughout. An expression is code, and proportional
        // spacing hides the things that matter most in it: how many spaces are
        // inside a pair of quotes, and whether two brackets are two brackets.
        addStyle(SpanStyle(fontFamily = FontFamily.Monospace), 0, source.length)

        tokenizeExpression(source).forEach { (range, token) ->
            val color = when (token) {
                ExpressionToken.REFERENCE -> colors.reference
                ExpressionToken.TEXT -> colors.text
                ExpressionToken.NUMBER -> colors.number
                ExpressionToken.KEYWORD -> colors.keyword
                ExpressionToken.FUNCTION -> colors.function
                ExpressionToken.OPERATOR -> colors.operator
                ExpressionToken.PLAIN -> colors.plain
            }
            val bold = token == ExpressionToken.REFERENCE ||
                token == ExpressionToken.KEYWORD ||
                token == ExpressionToken.FUNCTION
            addStyle(
                SpanStyle(
                    color = color,
                    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                ),
                range.first,
                minOf(range.last + 1, source.length),
            )
        }
    }
