package app.phueber.trigly.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The same property [RegexHighlightTest] tests, for the same reason: this reads
 * expressions that are usually *invalid*, because it runs on every keystroke on
 * the way to a valid one. So what is under test is not "does it colour a
 * correct expression correctly". It is that no half-typed input throws, and
 * that every character is accounted for exactly once whatever the input is.
 */
class ExpressionHighlightTest {

    /** The token per character position, as a string, one letter per kind. */
    private fun shape(source: String): String {
        val kinds = CharArray(source.length) { '?' }
        tokenizeExpression(source).forEach { (range, token) ->
            range.forEach { i ->
                if (i in kinds.indices) {
                    kinds[i] = when (token) {
                        ExpressionToken.REFERENCE -> 'R'
                        ExpressionToken.TEXT -> 'T'
                        ExpressionToken.NUMBER -> 'N'
                        ExpressionToken.KEYWORD -> 'K'
                        ExpressionToken.FUNCTION -> 'F'
                        ExpressionToken.OPERATOR -> 'O'
                        ExpressionToken.PLAIN -> 'P'
                    }
                }
            }
        }
        return String(kinds)
    }

    @Test
    fun `each kind of thing gets its own colour`() {
        assertEquals("FFFFFOTTTOPOOPTTT", shape("upper(\"a\") == \"A\""))
    }

    @Test
    fun `a reference is one run`() {
        assertEquals("RRRRRRRRRRRRRPOPN", shape("{{app.count}} + 1"))
    }

    /**
     * A number and a piece of text are told apart, which is the one distinction
     * this language cannot survive without: `5` never equals `"5"`.
     */
    @Test
    fun `a number and a quoted number are different kinds`() {
        assertEquals("NPOOPTTT", shape("5 == \"5\""))
    }

    @Test
    fun `a decimal number is one run, and a dot with no digit after it is not`() {
        assertEquals("NNN", shape("1.5"))
        assertEquals("NPP", shape("1.x"))
    }

    @Test
    fun `keywords are marked and an unknown word is left plain`() {
        // `nope` is not one of the six functions, and a bare word does nothing
        // in this language. Plain is what "does nothing" looks like.
        assertEquals("KKKKPKKKPPPPP", shape("true and nope"))
    }

    @Test
    fun `a function name is marked before its bracket is typed`() {
        assertEquals("PP", shape("up"))
        assertEquals("FFFFF", shape("upper"))
    }

    @Test
    fun `an escaped quote does not end the text`() {
        // "a\"b" + 1
        assertEquals("TTTTTTPOPN", shape("\"a\\\"b\" + 1"))
    }

    /**
     * Substitution does not respect quotes, so a reference inside a string is
     * still live and is still marked. That is the point: quoting a reference is
     * a mistake, and a reference that lost its colour in there would read as if
     * the quotes had made it safe.
     */
    @Test
    fun `a reference inside quotes is still marked`() {
        assertEquals("TRRRRRRRRRRRRRT", shape("\"{{app.state}}\""))
    }

    @Test
    fun `an unclosed reference is marked to the end`() {
        assertEquals("RRRRRR", shape("{{app."))
    }

    @Test
    fun `an unclosed string is marked to the end`() {
        assertEquals("NPOPTTTT", shape("1 + \"abc"))
    }

    @Test
    fun `every prefix of an expression is tokenized without throwing`() {
        val source =
            "{{app.count | 0}} > 10 and contains(lower(\"Buds\"), \"b\") ? 1.5 : \"no\""

        // Typing it one character at a time: no prefix may throw, and every
        // character must be covered. An uncovered one shows up as '?'.
        source.indices.forEach { end ->
            val prefix = source.substring(0, end + 1)
            val shape = shape(prefix)
            assertEquals("uncovered character in '$prefix'", -1, shape.indexOf('?'))
            assertEquals(prefix.length, shape.length)
        }
    }

    @Test
    fun `an empty expression tokenizes to nothing`() {
        assertEquals(0, tokenizeExpression("").size)
    }
}
