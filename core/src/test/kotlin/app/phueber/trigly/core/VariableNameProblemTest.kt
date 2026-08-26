package app.phueber.trigly.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [variableNameProblem]: the one gate on what a person may call an app
 * variable.
 *
 * The function's own kdoc says what it is actually testing for: a name is
 * only worth anything if a rule can refer to it, so a name is legal exactly
 * when `{{app.<name>}}` parses back to a reference to that same name. Every
 * case below is chosen to exercise that property rather than to match a
 * pattern written out a second time here.
 */
class VariableNameProblemTest {

    @Test
    fun `a blank name is rejected`() {
        assertNotNull(variableNameProblem(""))
        assertNotNull(variableNameProblem("   "))
    }

    @Test
    fun `a name with a space is rejected`() {
        assertNotNull(variableNameProblem("trip count"))
    }

    @Test
    fun `a name containing a pipe is rejected`() {
        // A bare '|' would be read as the start of a fallback, so
        // '{{app.trip|count}}' would not read back as a reference to
        // 'trip|count'.
        assertNotNull(variableNameProblem("trip|count"))
    }

    @Test
    fun `a name that would close the reference early is rejected`() {
        // '}}' inside the name reads back as the reference's own closing
        // delimiter, so the parser splits the field before the name ends and
        // what comes back is a shorter reference plus stray literal text, not
        // a reference to this name.
        assertNotNull(variableNameProblem("trip}}count"))
        // A single trailing '}' merges with the delimiter's own '}}' the same
        // way, so the name that reads back is one character short.
        assertNotNull(variableNameProblem("trip}"))
        // A name that is itself shaped like a reference reads back the same
        // way: one character short, for the same reason.
        assertNotNull(variableNameProblem("{{trip}}"))
    }

    @Test
    fun `an ordinary name is fine`() {
        assertNull(variableNameProblem("trip_count"))
    }

    @Test
    fun `a fine name round-trips through the parser as a reference to itself`() {
        val name = "trip_count"

        assertNull(variableNameProblem(name))

        val template = parseTemplate("{{${VariableScope.APP}.$name}}")
        val reference = template.references.single()
        assertEquals(1, template.segments.size)
        assertEquals(VariableScope.APP, reference.scope)
        assertEquals(name, reference.name)
        assertNull(reference.fallback)
    }

    @Test
    fun `a name is normalized by trimming before it is judged`() {
        // Leading and trailing space is trimmed, same as normalizeVariableName,
        // so a name typed with stray whitespace around it is not rejected for
        // whitespace it will never actually be stored with.
        assertNull(variableNameProblem("  trip_count  "))
    }

    @Test
    fun `the rejection names the offending name`() {
        val problem = variableNameProblem("trip count")

        assertNotNull(problem)
        assertEquals(true, problem!!.contains("trip count"))
    }
}
