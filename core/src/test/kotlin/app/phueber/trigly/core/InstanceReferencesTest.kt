package app.phueber.trigly.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [componentInstanceNames], and the rewrites that make its positional
 * numbering safe to ship: [instanceRenames], [rewriteInstanceReferences] and
 * [shortFormRenames].
 */
class InstanceReferencesTest {

    // --- componentInstanceNames -------------------------------------------------------

    @Test
    fun `the first of a type is the bare type string`() {
        assertEquals(
            listOf("notification_posted", "sms_received"),
            componentInstanceNames(listOf("notification_posted", "sms_received")),
        )
    }

    /**
     * The bare type for the first instance is what makes every rule saved
     * before instances existed keep its meaning: `{{bluetooth_connected.name}}`
     * named that trigger then and names it now.
     */
    @Test
    fun `a repeated type is numbered from two`() {
        assertEquals(
            listOf("notification_posted", "notification_posted_2", "notification_posted_3"),
            componentInstanceNames(List(3) { "notification_posted" }),
        )
    }

    @Test
    fun `each type is numbered independently of the others`() {
        assertEquals(
            listOf("toast", "speak", "toast_2", "speak_2"),
            componentInstanceNames(listOf("toast", "speak", "toast", "speak")),
        )
    }

    // --- instanceRenames --------------------------------------------------------------

    @Test
    fun `an edit that moves nothing renames nothing`() {
        val types = listOf("toast", "speak")

        assertEquals(emptyMap<String, String>(), instanceRenames(types, listOf(0, 1)))
    }

    /**
     * The case positional numbering exists to be caught in. Three leaves of one
     * type, the first deleted: the old third becomes the second, so a saved
     * `_3` has to become `_2` and a saved `_2` has to become the bare type. Both
     * still resolve either way, which is why nothing downstream can catch this
     * and the rewrite has to happen here.
     */
    @Test
    fun `deleting the first of three shifts the two behind it`() {
        val types = List(3) { "notification_posted" }

        assertEquals(
            mapOf(
                "notification_posted_2" to "notification_posted",
                "notification_posted_3" to "notification_posted_2",
            ),
            instanceRenames(types, listOf(1, 2)),
        )
    }

    @Test
    fun `deleting the last of three renames nothing that survives`() {
        val types = List(3) { "notification_posted" }

        assertEquals(emptyMap<String, String>(), instanceRenames(types, listOf(0, 1)))
    }

    @Test
    fun `a reorder swaps the two namespaces`() {
        val types = List(2) { "toast" }

        assertEquals(
            mapOf("toast" to "toast_2", "toast_2" to "toast"),
            instanceRenames(types, listOf(1, 0)),
        )
    }

    @Test
    fun `deleting one type does not renumber another`() {
        val types = listOf("toast", "speak", "toast", "speak")

        // The first speak goes. Only the speak numbering moves.
        assertEquals(
            mapOf("speak_2" to "speak"),
            instanceRenames(types, listOf(0, 2, 3)),
        )
    }

    /**
     * A deleted namespace is left to dangle on purpose. Pointing it anywhere
     * would repoint a reference at a component the person never named, and a
     * dangling name is the one case save-time validation does see.
     */
    @Test
    fun `a deleted namespace is not given a target`() {
        val types = List(2) { "toast" }

        val renames = instanceRenames(types, listOf(0))

        assertEquals(emptyMap<String, String>(), renames)
    }

    // --- rewriteInstanceReferences ----------------------------------------------------

    @Test
    fun `an empty rename map leaves the text untouched`() {
        val text = "Chat: {{notification_posted_2.title}}"

        assertEquals(text, rewriteInstanceReferences(text, emptyMap()))
    }

    @Test
    fun `only the namespace is rewritten`() {
        assertEquals(
            "Chat: {{notification_posted.title}} at {{event.time}}",
            rewriteInstanceReferences(
                "Chat: {{notification_posted_2.title}} at {{event.time}}",
                mapOf("notification_posted_2" to "notification_posted"),
            ),
        )
    }

    @Test
    fun `a fallback survives the rewrite exactly`() {
        assertEquals(
            "{{toast.text | nothing said}}",
            rewriteInstanceReferences(
                "{{toast_2.text | nothing said}}",
                mapOf("toast_2" to "toast"),
            ),
        )
    }

    /**
     * The reason this is one pass. Applied in sequence, `_3 -> _2 -> bare`
     * would collapse two references onto one component, which is a worse bug
     * than the one being repaired.
     */
    @Test
    fun `a shifting rename does not chain`() {
        val renames = mapOf(
            "notification_posted_2" to "notification_posted",
            "notification_posted_3" to "notification_posted_2",
        )

        assertEquals(
            "{{notification_posted.title}} {{notification_posted_2.title}}",
            rewriteInstanceReferences(
                "{{notification_posted_2.title}} {{notification_posted_3.title}}",
                renames,
            ),
        )
    }

    @Test
    fun `a swap does not collapse onto one namespace`() {
        val renames = mapOf("toast" to "toast_2", "toast_2" to "toast")

        assertEquals(
            "{{toast_2.text}} and {{toast.text}}",
            rewriteInstanceReferences("{{toast.text}} and {{toast_2.text}}", renames),
        )
    }

    @Test
    fun `text with no reference is returned as it was`() {
        val text = "just words, and a { brace"

        assertEquals(text, rewriteInstanceReferences(text, mapOf("toast" to "toast_2")))
    }

    @Test
    fun `an unbalanced opening is left alone`() {
        val text = "{{toast.text"

        // No closing braces, so parseTemplate keeps it literal. The rewrite
        // matches only the head, so it rewrites the namespace and still leaves
        // the text as literal as it found it.
        assertEquals("{{toast_2.text", rewriteInstanceReferences(text, mapOf("toast" to "toast_2")))
    }

    // --- shortFormRenames -------------------------------------------------------------

    @Test
    fun `a second leaf turns the short form into the first leaf's own name`() {
        assertEquals(
            "Chat: {{notification_posted.title}}",
            rewriteInstanceReferences(
                "Chat: {{trigger.title}}",
                shortFormRenames("notification_posted"),
            ),
        )
    }

    @Test
    fun `no existing leaf means nothing to preserve`() {
        assertEquals(emptyMap<String, String>(), shortFormRenames(null))
    }
}
