package app.phueber.trigly.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The highlighter reads patterns that are usually *invalid*, because it runs on
 * every keystroke on the way to a valid one. So the property under test is not
 * "does it colour a correct pattern correctly" — it is that no half-typed input
 * throws, and that every character is accounted for exactly once whatever the
 * input looks like.
 */
class RegexHighlightTest {

    /** The token per character position, as a string, one letter per token kind. */
    private fun shape(pattern: String): String {
        val kinds = CharArray(pattern.length) { '?' }
        tokenize(pattern).forEach { (range, token) ->
            range.forEach { i ->
                if (i in kinds.indices) {
                    kinds[i] = when (token) {
                        RegexToken.LITERAL -> 'L'
                        RegexToken.ESCAPE -> 'E'
                        RegexToken.CLASS -> 'C'
                        RegexToken.QUANTIFIER -> 'Q'
                        RegexToken.GROUP -> 'G'
                        RegexToken.ANCHOR -> 'A'
                    }
                }
            }
        }
        return String(kinds)
    }

    @Test
    fun `plain text is all literal`() {
        assertEquals("LLLLL", shape("Alice"))
    }

    @Test
    fun `each construct gets its own kind`() {
        assertEquals("AEELLGLLGQA", shape("^\\dab(cd)+$"))
    }

    @Test
    fun `an escape is coloured with the character it protects`() {
        // Both positions, so the backslash is never left looking like a literal.
        assertEquals("EE", shape("\\d"))
        assertEquals("EELL", shape("\\.ab"))
    }

    @Test
    fun `an escaped bracket does not open a character class`() {
        // If it did, everything after would be swallowed as class contents.
        assertEquals("EEQ", shape("\\[+"))
    }

    @Test
    fun `metacharacters inside a class are literal`() {
        assertEquals("CLLLC", shape("[a.+]"))
    }

    @Test
    fun `a braced quantifier is one run, including its digits`() {
        assertEquals("EEQQQQQ", shape("\\d{2,4}"))
    }

    @Test
    fun `an unclosed brace colours only the brace`() {
        assertEquals("QLL", shape("{2,"))
    }

    @Test
    fun `a trailing backslash is still an escape`() {
        assertEquals("LE", shape("a\\"))
    }

    @Test
    fun `every prefix of a pattern is tokenized without throwing`() {
        val pattern = "^(Alice|Bob)\\s+[0-9]{2,4}.*$"

        // Typing it one character at a time: no prefix may throw, and every
        // character must be covered — an uncovered one shows up as '?'.
        pattern.indices.forEach { end ->
            val prefix = pattern.substring(0, end + 1)
            val shape = shape(prefix)
            assertEquals("uncovered character in '$prefix'", -1, shape.indexOf('?'))
            assertEquals(prefix.length, shape.length)
        }
    }

    @Test
    fun `an empty pattern tokenizes to nothing`() {
        assertEquals(0, tokenize("").size)
    }
}
