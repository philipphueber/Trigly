package app.phueber.trigly.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `Rule.imported()` is the decision behind treating a rule file as somebody
 * else's program rather than a trusted document: switch it off, and mint a
 * fresh id for anything the file could otherwise be used to replay.
 *
 * A JVM test can reach it because it takes a [Registry] built from plain fakes
 * rather than a live one wired to a `Context`. `DuplicateTest` in `:ui` covers
 * the same walk, [withFreshGeneratedIds], against the real component schemas
 * this module cannot see; this file is only the mechanism.
 */
class RuleImportTest {

    private val registry = Registry(
        triggerFactories = listOf(HasGeneratedIdFactory()),
        actionFactories = emptyList(),
    )

    private fun rule(enabled: Boolean, config: Map<String, String>) = Rule(
        id = "original",
        name = "Sample",
        trigger = ComponentSpec("has_generated_id", config),
        actions = emptyList(),
        enabled = enabled,
    )

    @Test
    fun `an enabled imported rule is disabled`() {
        val imported = rule(enabled = true, config = mapOf("id" to "abc")).imported(registry)

        assertFalse(imported.enabled)
    }

    @Test
    fun `a rule already disabled in the file stays disabled`() {
        val imported = rule(enabled = false, config = mapOf("id" to "abc")).imported(registry)

        assertFalse(imported.enabled)
    }

    @Test
    fun `a generated id changes`() {
        val imported = rule(enabled = true, config = mapOf("id" to "abc")).imported(registry)

        val spec = (imported.trigger as TriggerNode.One).spec
        assertNotEquals("abc", spec.config["id"])
        assertTrue(spec.config["id"]!!.isNotBlank())
    }

    @Test
    fun `a non-generated config value does not change`() {
        val imported = rule(enabled = true, config = mapOf("id" to "abc", "label" to "Go"))
            .imported(registry)

        val spec = (imported.trigger as TriggerNode.One).spec
        assertEquals("Go", spec.config["label"])
    }

    /**
     * With no schema there is no way to tell which key of an unknown
     * component is an identity, and inventing a value for one would corrupt a
     * rule that a build with the component installed could still run.
     */
    @Test
    fun `a component the registry does not know keeps its config`() {
        val original = Rule(
            id = "original",
            name = "Sample",
            trigger = ComponentSpec("from_a_newer_build", mapOf("someId" to "keep-me")),
            actions = emptyList(),
            enabled = true,
        )

        val imported = original.imported(registry)

        assertEquals("keep-me", (imported.trigger as TriggerNode.One).spec.config["someId"])
    }

    @Test
    fun `the rule id itself still changes`() {
        val imported = rule(enabled = true, config = mapOf("id" to "abc")).imported(registry)

        assertNotEquals("original", imported.id)
    }

    /** Not a copy, so a name somebody else chose is not this function's to edit. */
    @Test
    fun `the name is unchanged`() {
        val imported = rule(enabled = true, config = mapOf("id" to "abc")).imported(registry)

        assertEquals("Sample", imported.name)
    }
}

private class HasGeneratedIdFactory : TriggerFactory {
    override val type = "has_generated_id"
    override val configFields = listOf(
        ConfigField.GeneratedId(key = "id", label = "Id"),
        ConfigField.Text(key = "label", label = "Label"),
    )

    override fun create(config: Map<String, String>): Trigger =
        error("not needed: importing never builds the component")
}
