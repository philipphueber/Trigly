package app.phueber.trigly.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a component says it offers on its own block.
 *
 * The rule editor used to carry this knowledge itself: testing an action was
 * written into the action section, and pinning a shortcut was keyed off a config
 * key that happened to be unique to one trigger. Both are now answers a factory
 * gives, which is what these tests hold in place — a screen that recognises
 * component names is the thing being prevented.
 */
class ComponentToolsTest {

    private val registry = Registry(
        triggerFactories = listOf(
            ToollessTriggerFactory("plain"),
            SetupTriggerFactory("needs-setup"),
        ),
        actionFactories = listOf(ActionWithNoTools("act")),
    )

    @Test
    fun `a trigger offers nothing unless it says otherwise`() {
        // The default matters more than it looks: every trigger that has no
        // tool must render as a plain block, and a default of "Test" here
        // would offer to run something that cannot be run.
        assertTrue(registry.toolsFor(ComponentSpec("plain")).isEmpty())
    }

    @Test
    fun `an action offers Test without saying anything`() {
        assertEquals(
            listOf(ComponentTool.Test),
            registry.toolsFor(ComponentSpec("act")),
        )
    }

    @Test
    fun `tools follow the configuration, not just the type`() {
        // The shortcut trigger's real shape: nothing to pin until it has an id,
        // and the same type offers the tool once it does.
        assertTrue(registry.toolsFor(ComponentSpec("needs-setup", mapOf("id" to ""))).isEmpty())
        assertEquals(
            listOf(ComponentTool.PinShortcut),
            registry.toolsFor(ComponentSpec("needs-setup", mapOf("id" to "abc"))),
        )
    }

    @Test
    fun `an unknown type reports empty rather than throwing`() {
        // Same reason as requirements: the editor asks about a type before
        // anything has validated it.
        assertTrue(registry.toolsFor(ComponentSpec("no-such-type")).isEmpty())
    }
}

private class ToollessTriggerFactory(override val type: String) : TriggerFactory {
    override fun create(config: Map<String, String>): Trigger =
        error("not needed: tools are read without creating the trigger")
}

private class SetupTriggerFactory(override val type: String) : TriggerFactory {
    override fun create(config: Map<String, String>): Trigger =
        error("not needed: tools are read without creating the trigger")

    override fun toolsFor(config: Map<String, String>): List<ComponentTool> =
        if (config["id"].isNullOrBlank()) emptyList() else listOf(ComponentTool.PinShortcut)
}

private class ActionWithNoTools(override val type: String) : ActionFactory {
    override fun create(config: Map<String, String>): Action =
        error("not needed: tools are read without creating the action")
}
