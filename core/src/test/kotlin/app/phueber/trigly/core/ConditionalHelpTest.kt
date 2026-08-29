package app.phueber.trigly.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [ConfigField.Text.helpWhen] and the [effectiveHelp] that reads it.
 *
 * `set_variable`'s value field is the case this exists for: the same box means
 * three different things depending on the sibling `mode` field, and printing
 * every mode's sentence regardless of `mode` is what grew its help to four
 * topics. [helpWhen] lets a factory declare which sentence goes with which
 * value instead, the same move [FieldCondition] already made for whether a
 * whole field shows at all — see `FieldVisibilityTest`.
 */
class ConditionalHelpTest {

    private val field = ConfigField.Text(
        key = "value",
        label = "Value",
        help = "This can include another variable.",
        helpWhen = listOf(
            ConditionalHelp(
                condition = FieldCondition(key = "mode", value = "add"),
                help = "Adding needs a plain number.",
            ),
            ConditionalHelp(
                condition = FieldCondition(key = "mode", value = "evaluate"),
                help = "Evaluating runs this as an expression.",
            ),
        ),
    )

    @Test
    fun `with no matching sibling only the base help shows`() {
        assertEquals(
            "This can include another variable.",
            field.effectiveHelp(companions = emptyMap()),
        )
    }

    @Test
    fun `a matching sibling appends its sentence`() {
        assertEquals(
            "This can include another variable. Adding needs a plain number.",
            field.effectiveHelp(companions = mapOf("mode" to "add")),
        )
    }

    @Test
    fun `a different value matches a different sentence`() {
        assertEquals(
            "This can include another variable. Evaluating runs this as an expression.",
            field.effectiveHelp(companions = mapOf("mode" to "evaluate")),
        )
    }

    @Test
    fun `a sibling value that matches neither condition shows only the base help`() {
        assertEquals(
            "This can include another variable.",
            field.effectiveHelp(companions = mapOf("mode" to "set")),
        )
    }

    @Test
    fun `unlike shownWith, a missing sibling is not read as its default`() {
        // A rule nobody has touched yet has nothing stored for "mode", and the
        // editor is showing "set" as its default — but the value field must not
        // print an evaluate-only sentence for a mode nobody chose. Nothing
        // stored reads as "no condition matches", not as the sibling's default.
        assertEquals(
            "This can include another variable.",
            field.effectiveHelp(companions = emptyMap()),
        )
    }

    @Test
    fun `a field with no helpWhen answers with its own help unchanged`() {
        val plain = ConfigField.Text(key = "name", label = "Name", help = "Pick a name.")

        assertEquals("Pick a name.", plain.effectiveHelp(companions = mapOf("mode" to "add")))
    }

    @Test
    fun `a field with no help and no helpWhen answers null`() {
        val plain = ConfigField.Text(key = "name", label = "Name")

        assertNull(plain.effectiveHelp(companions = emptyMap()))
    }

    @Test
    fun `a non-Text field always answers with its own help`() {
        val choice = ConfigField.Choice(
            key = "scope",
            label = "Scope",
            options = listOf(ConfigField.Option("app", "Every rule")),
            help = "Where the value lives.",
        )

        assertEquals("Where the value lives.", choice.effectiveHelp(companions = mapOf("mode" to "add")))
    }

    @Test
    fun `companionKeys names the sibling a helpWhen condition reads`() {
        assertEquals(listOf("mode"), field.companionKeys())
    }

    @Test
    fun `a field with no helpWhen declares no companion keys for it`() {
        val plain = ConfigField.Text(key = "name", label = "Name")

        assertEquals(emptyList<String>(), plain.companionKeys())
    }
}
