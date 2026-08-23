package app.phueber.trigly.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TextFilter] is where five triggers' worth of "does this text match" now lives,
 * so these tests are the whole safety net for a behaviour change that reaches all
 * of them at once.
 *
 * Two properties matter more than the rest and are asserted from several angles:
 * an empty pattern matches everything (that is what every `blankMeaning` on these
 * fields promises), and a broken regex fails when the rule is *built*, not when an
 * event arrives.
 */
class TextFilterTest {

    @Test
    fun `an empty pattern has no opinion`() {
        listOf(TextFilter.of(null), TextFilter.of(""), TextFilter.Any).forEach { filter ->
            assertTrue(filter.isEmpty)
            assertTrue(filter.matches("anything at all"))
            assertTrue(filter.matches(""))
            // Including a null candidate: "no filter" cannot be the reason a
            // trigger declines to fire.
            assertTrue(filter.matches(null))
        }
    }

    @Test
    fun `an empty regex is still just no filter`() {
        val filter = TextFilter.of("", TextMatchMode.REGEX)

        assertTrue(filter.isEmpty)
        assertTrue(filter.matches(null))
    }

    @Test
    fun `contains is a case-insensitive substring`() {
        val filter = TextFilter.of("code", TextMatchMode.CONTAINS)

        assertTrue(filter.matches("Your CODE is 4321"))
        assertTrue(filter.matches("decoded"))
        assertFalse(filter.matches("nothing here"))
    }

    @Test
    fun `contains treats a pattern's metacharacters as literal text`() {
        val filter = TextFilter.of("a.c", TextMatchMode.CONTAINS)

        assertTrue(filter.matches("xxa.cxx"))
        assertFalse(filter.matches("abc"))
    }

    @Test
    fun `regex is searched anywhere, like grep`() {
        val filter = TextFilter.of("""\d{4}""", TextMatchMode.REGEX)

        assertTrue(filter.matches("Your code is 4321"))
        assertFalse(filter.matches("Your code is 43"))
    }

    @Test
    fun `regex anchors are available to whoever wants the whole string`() {
        val anchored = TextFilter.of("^Alice$", TextMatchMode.REGEX)

        assertTrue(anchored.matches("Alice"))
        assertFalse(anchored.matches("Alice B"))
    }

    @Test
    fun `regex ignores case, so both modes agree about it`() {
        assertTrue(TextFilter.of("alice", TextMatchMode.REGEX).matches("ALICE"))
    }

    @Test
    fun `no filter of either mode matches a null candidate`() {
        assertFalse(TextFilter.of("x", TextMatchMode.CONTAINS).matches(null))
        assertFalse(TextFilter.of("x", TextMatchMode.REGEX).matches(null))
    }

    @Test
    fun `a broken regex fails when the filter is built`() {
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            TextFilter.of("a(b", TextMatchMode.REGEX)
        }

        // The message has to name the pattern: the editor shows it next to a form
        // that may hold several text filters.
        assertTrue(thrown.message.orEmpty().contains("a(b"))
    }

    @Test
    fun `the same broken pattern is fine as a substring`() {
        assertTrue(TextFilter.of("a(b", TextMatchMode.CONTAINS).matches("xa(bx"))
    }

    @Test
    fun `an unknown or absent mode reads as contains`() {
        // The compatibility path: every rule saved before the mode key existed.
        assertEquals(TextMatchMode.CONTAINS, TextMatchMode.parse(null))
        assertEquals(TextMatchMode.CONTAINS, TextMatchMode.parse(""))
        assertEquals(TextMatchMode.CONTAINS, TextMatchMode.parse("glob"))
        assertEquals(TextMatchMode.CONTAINS, TextMatchMode.parse("contains"))
        assertEquals(TextMatchMode.REGEX, TextMatchMode.parse("regex"))
        assertEquals(TextMatchMode.REGEX, TextMatchMode.parse("REGEX"))
    }

    @Test
    fun `a rule stored before regex existed still matches as a substring`() {
        val filter = TextFilter.fromConfig("a(b", rawMode = null)

        assertEquals(TextMatchMode.CONTAINS, filter.mode)
        assertTrue(filter.matches("xa(bx"))
    }

    @Test
    fun `fromConfig reads the mode key`() {
        val filter = TextFilter.fromConfig("""\d+""", rawMode = "regex")

        assertEquals(TextMatchMode.REGEX, filter.mode)
        assertTrue(filter.matches("code 4321"))
    }

    @Test
    fun `equality is the pattern and the mode, not the compiled predicate`() {
        assertEquals(TextFilter.of("x"), TextFilter.of("x"))
        assertEquals(TextFilter.of("x").hashCode(), TextFilter.of("x").hashCode())
        assertFalse(TextFilter.of("x") == TextFilter.of("x", TextMatchMode.REGEX))
        assertFalse(TextFilter.of("x") == TextFilter.of("y"))
    }

    @Test
    fun `regexErrorOrNull is silent about valid and blank patterns`() {
        assertNull(regexErrorOrNull(""))
        assertNull(regexErrorOrNull("""^\d{4}$"""))
    }

    @Test
    fun `regexErrorOrNull explains a broken pattern`() {
        // What the editor shows while someone is typing; the wording is the
        // regex engine's own, so this only asserts that there is some.
        assertNotNull(regexErrorOrNull("a(b"))
        assertNotNull(regexErrorOrNull("*"))
    }

    @Test
    fun `an unterminated pattern is an error rather than a crash while typing`() {
        // Every prefix of a plausible pattern gets typed on the way to it, and
        // the editor calls this on each keystroke.
        listOf("[", "[a-", "(", "(a|", """\""", "a{2", "a{2,").forEach { prefix ->
            regexErrorOrNull(prefix)
        }
    }
}
