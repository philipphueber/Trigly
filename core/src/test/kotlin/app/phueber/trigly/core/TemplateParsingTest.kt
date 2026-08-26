package app.phueber.trigly.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [parseTemplate]: turning a stored field value into [TemplateSegment]s.
 *
 * The grammar is one production, `{{ scope . name [ | fallback ] }}`, and it
 * never throws. Bad input becomes [TemplateSegment.Malformed] rather than an
 * exception, so the whole class is worth pinning: a parser mistake here is
 * invisible until someone's field silently does the wrong thing.
 */
class TemplateParsingTest {

    @Test
    fun `a plain string with no braces has no references`() {
        val template = parseTemplate("hello world")

        assertFalse(template.hasReferences)
        assertTrue(template.references.isEmpty())
        assertTrue(template.malformed.isEmpty())
    }

    @Test
    fun `a trigger reference parses to its scope and name`() {
        val ref = parseTemplate("{{trigger.text}}").references.single()

        assertEquals("trigger", ref.scope)
        assertEquals("text", ref.name)
        assertNull(ref.fallback)
    }

    @Test
    fun `literal text around a reference is kept in order`() {
        val segments = parseTemplate("Battery: {{trigger.level}}%").segments

        assertEquals(3, segments.size)
        assertEquals(TemplateSegment.Literal("Battery: "), segments[0])
        assertTrue(segments[1] is TemplateSegment.Reference)
        assertEquals(TemplateSegment.Literal("%"), segments[2])
    }

    @Test
    fun `two references in one string both parse`() {
        val refs = parseTemplate("{{trigger.a}}-{{trigger.b}}").references

        assertEquals(listOf("a", "b"), refs.map { it.name })
    }

    @Test
    fun `a fallback after a pipe is captured and trimmed`() {
        val ref = parseTemplate("{{trigger.name | Unknown device}}").references.single()

        assertEquals("Unknown device", ref.fallback)
    }

    @Test
    fun `an unbalanced opening brace is literal text`() {
        // Deliberate: this is what means no escape character is needed for a
        // literal '{{' in a field.
        val template = parseTemplate("hello {{world")

        assertFalse(template.hasReferences)
        assertEquals(listOf(TemplateSegment.Literal("hello {{world")), template.segments)
    }

    // --- a balanced reference that names nothing is Malformed, not literal ---------

    @Test
    fun `a balanced reference with no dot is malformed`() {
        val template = parseTemplate("{{trigger}}")

        assertTrue(template.hasReferences)
        assertTrue(template.references.isEmpty())
        val malformed = template.malformed.single()
        assertEquals("{{trigger}}", malformed.raw)
        assertEquals(
            "'trigger' names no group. Write group.name, as in trigger.text.",
            malformed.reason,
        )
    }

    @Test
    fun `a reference with an empty scope is malformed`() {
        val malformed = parseTemplate("{{.name}}").malformed.single()

        assertEquals("The group before the dot is empty.", malformed.reason)
    }

    @Test
    fun `a reference with an empty name is malformed`() {
        val malformed = parseTemplate("{{trigger.}}").malformed.single()

        assertEquals("The name after the dot is empty.", malformed.reason)
    }

    @Test
    fun `a reference whose name contains a space is malformed`() {
        val malformed = parseTemplate("{{trigger.na me}}").malformed.single()

        assertEquals("A variable name cannot contain a space.", malformed.reason)
    }

    // --- isSingleReference: what decides whether encoding applies ------------------

    @Test
    fun `isSingleReference is true for a value that is exactly one reference`() {
        assertTrue(parseTemplate("{{trigger.text}}").isSingleReference)
    }

    @Test
    fun `isSingleReference is false when literal text sits beside the reference`() {
        assertFalse(parseTemplate("say {{trigger.text}}").isSingleReference)
        assertFalse(parseTemplate("{{trigger.text}} said").isSingleReference)
    }
}
