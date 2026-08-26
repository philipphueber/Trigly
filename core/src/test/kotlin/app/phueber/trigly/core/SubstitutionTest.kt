package app.phueber.trigly.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Template.substitute] and [Substitution.encode].
 *
 * The invariant pinned hardest here is the one the whole feature rests on: an
 * absent value with no fallback must fail the field, never quietly become an
 * empty string. An empty notification is cosmetic; an empty webhook URL or SMS
 * is a wrong action taken in silence.
 */
class SubstitutionTest {

    // --- Template.substitute ---------------------------------------------------------

    @Test
    fun `a present value is inserted`() {
        val template = parseTemplate("Hello {{trigger.name}}!")
        val lookup = VariableLookup { VariableValue.Present("Bob") }

        val result = template.substitute(lookup, Substitution.TEXT)

        assertEquals(Substituted.Ok("Hello Bob!"), result)
    }

    @Test
    fun `an absent value with a fallback uses the fallback`() {
        val template = parseTemplate("{{trigger.name | Unknown device}}")
        val lookup = VariableLookup { VariableValue.Absent("gone") }

        val result = template.substitute(lookup, Substitution.TEXT)

        assertEquals(Substituted.Ok("Unknown device"), result)
    }

    @Test
    fun `an absent value with no fallback fails, naming the reference`() {
        val template = parseTemplate("{{trigger.name}}")
        val lookup = VariableLookup { VariableValue.Absent("the leaf did not fire") }

        val result = template.substitute(lookup, Substitution.TEXT) as Substituted.Failed

        assertTrue(result.reason.contains("{{trigger.name}}"))
        assertTrue(result.reason.contains("the leaf did not fire"))
    }

    @Test
    fun `a malformed segment fails substitution`() {
        val template = parseTemplate("{{no dot}}")
        val lookup = VariableLookup { VariableValue.Present("x") }

        val result = template.substitute(lookup, Substitution.TEXT)

        assertTrue(result is Substituted.Failed)
    }

    @Test
    fun `TEXT substitution copies a value through unchanged`() {
        val template = parseTemplate("value: {{trigger.x}}")
        val lookup = VariableLookup { VariableValue.Present("A & B \"quoted\"") }

        val result = template.substitute(lookup, Substitution.TEXT)

        assertEquals(Substituted.Ok("value: A & B \"quoted\""), result)
    }

    // --- Substitution.URL --------------------------------------------------------------

    @Test
    fun `URL encoding turns a space into percent-20, not a plus`() {
        assertEquals("a%20b", Substitution.URL.encode("a b"))
    }

    @Test
    fun `URL encoding turns an ampersand into percent-26`() {
        assertEquals("a%26b", Substitution.URL.encode("a&b"))
    }

    @Test
    fun `URL encoding leaves the unreserved set alone`() {
        val unreserved = "AZaz09-_.~"

        assertEquals(unreserved, Substitution.URL.encode(unreserved))
    }

    @Test
    fun `URL encoding of a non-ascii character is its UTF-8 bytes`() {
        // e-acute is 0xC3 0xA9 in UTF-8.
        assertEquals("%C3%A9", Substitution.URL.encode("é"))
    }

    @Test
    fun `URL encoding applies to a value embedded in a field`() {
        val template = parseTemplate("https://example.com/?q={{trigger.q}}")
        val lookup = VariableLookup { VariableValue.Present("a b&c") }

        val result = template.substitute(lookup, Substitution.URL)

        assertEquals(Substituted.Ok("https://example.com/?q=a%20b%26c"), result)
    }

    // --- Substitution.JSON_STRING -------------------------------------------------------

    @Test
    fun `JSON_STRING escaping escapes a quotation mark`() {
        assertEquals("\\\"", Substitution.JSON_STRING.encode("\""))
    }

    @Test
    fun `JSON_STRING escaping escapes a backslash`() {
        assertEquals("\\\\", Substitution.JSON_STRING.encode("\\"))
    }

    @Test
    fun `JSON_STRING escaping escapes a newline`() {
        assertEquals("\\n", Substitution.JSON_STRING.encode("\n"))
    }

    @Test
    fun `JSON_STRING escaping escapes a tab`() {
        assertEquals("\\t", Substitution.JSON_STRING.encode("\t"))
    }

    @Test
    fun `JSON_STRING escaping escapes a control character`() {
        assertEquals("\\u0001", Substitution.JSON_STRING.encode("\u0001"))
    }

    @Test
    fun `JSON_STRING escaping does not add surrounding quotes`() {
        assertEquals("plain text", Substitution.JSON_STRING.encode("plain text"))
    }

    // --- the single-reference exemption: the rule most likely to break later -------

    @Test
    fun `a field that is exactly one reference is not URL-encoded`() {
        val template = parseTemplate("{{trigger.endpoint}}")
        val lookup = VariableLookup { VariableValue.Present("https://x.test/a b&c") }

        val result = template.substitute(lookup, Substitution.URL)

        assertEquals(Substituted.Ok("https://x.test/a b&c"), result)
    }

    @Test
    fun `a field that is exactly one reference is not JSON-escaped`() {
        val template = parseTemplate("{{trigger.body}}")
        val lookup = VariableLookup { VariableValue.Present("he said \"no\"") }

        val result = template.substitute(lookup, Substitution.JSON_STRING)

        assertEquals(Substituted.Ok("he said \"no\""), result)
    }

    // --- the fallback is encoded on the same terms as a value -----------------------

    @Test
    fun `an embedded fallback is encoded like a value`() {
        val template = parseTemplate("q={{trigger.missing | a b}}")
        val lookup = VariableLookup { VariableValue.Absent("gone") }

        val result = template.substitute(lookup, Substitution.URL)

        assertEquals(Substituted.Ok("q=a%20b"), result)
    }
}
