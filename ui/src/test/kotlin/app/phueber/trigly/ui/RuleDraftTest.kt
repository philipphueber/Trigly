package app.phueber.trigly.ui

import app.phueber.trigly.core.ComponentSpec
import app.phueber.trigly.core.NO_TRIGGER
import app.phueber.trigly.core.Registry
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerFactory
import app.phueber.trigly.core.TriggerNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RuleDraft.toRuleOrNull] (the save decision) and `enableRefusal` (the enable
 * decision), both pure functions over a draft and neither needing a `Context`
 * or a `ViewModel`, which is what lets them run on the JVM rather than joining
 * `RuleEditorViewModelTest` as an instrumented spec.
 */
class RuleDraftTest {

    /** Produces events, never a state: a component `canStart` can start a
     * rule with alone, but never share an `ALL` with another one of these. */
    private class EdgeTrigger(override val type: String) : TriggerFactory {
        override fun create(config: Map<String, String>): Trigger =
            error("not built in this test")
    }

    private fun registryOf(vararg types: String) = Registry(
        triggerFactories = types.map { EdgeTrigger(it) },
        actionFactories = emptyList(),
    )

    private fun draftOf(
        name: String = "Rule",
        trigger: TriggerDraft? = null,
        actions: List<ComponentDraft> = emptyList(),
        enabled: Boolean = true,
    ) = RuleDraft(id = null, name = name, trigger = trigger, actions = actions, enabled = enabled)

    private fun leaf(type: String) = TriggerDraft.One(ComponentDraft(type))

    // --- the save decision: RuleDraft.toRuleOrNull ----------------------------

    @Test
    fun `a blank name refuses the save`() {
        assertNull(draftOf(name = "  ").toRuleOrNull())
    }

    @Test
    fun `a name is the only thing toRuleOrNull needs`() {
        val rule = draftOf(name = "Bare").toRuleOrNull()

        assertEquals(NO_TRIGGER, rule?.trigger)
        assertTrue(rule!!.actions.isEmpty())
    }

    @Test
    fun `no trigger chosen becomes NO_TRIGGER`() {
        val rule = draftOf(trigger = null).toRuleOrNull()!!

        assertEquals(NO_TRIGGER, rule.trigger)
    }

    @Test
    fun `an empty group nested in the tree is kept, not pruned or refused`() {
        val draft = draftOf(
            trigger = TriggerDraft.Group(
                TriggerNode.Op.ALL,
                listOf(leaf("screen"), TriggerDraft.Group(TriggerNode.Op.ANY, emptyList())),
            ),
        )

        val rule = draft.toRuleOrNull()

        val root = rule!!.trigger as TriggerNode.Group
        assertEquals(TriggerNode.Op.ALL, root.op)
        assertEquals(
            TriggerNode.Group(TriggerNode.Op.ANY, emptyList()),
            root.children[1],
        )
    }

    @Test
    fun `a group of one child is kept, not unwrapped`() {
        val draft = draftOf(trigger = TriggerDraft.Group(TriggerNode.Op.ANY, listOf(leaf("screen"))))

        val rule = draft.toRuleOrNull()!!

        assertEquals(TriggerNode.Group(TriggerNode.Op.ANY, listOf(TriggerNode.One(ComponentSpec("screen")))), rule.trigger)
    }

    // --- the enable decision: enableRefusal -----------------------------------

    @Test
    fun `no trigger and no actions names both`() {
        val message = draftOf(trigger = null, actions = emptyList()).enableRefusal(registryOf())

        assertEquals("Add a trigger and an action before switching this on.", message)
    }

    @Test
    fun `no trigger alone names only the trigger`() {
        val draft = draftOf(trigger = null, actions = listOf(ComponentDraft("toast")))

        assertEquals(
            "Add a trigger before switching this on.",
            draft.enableRefusal(registryOf("edge")),
        )
    }

    @Test
    fun `no actions alone names only the action`() {
        val draft = draftOf(trigger = leaf("edge"), actions = emptyList())

        assertEquals(
            "Add an action before switching this on.",
            draft.enableRefusal(registryOf("edge")),
        )
    }

    @Test
    fun `a trigger and an action together can be switched on`() {
        val draft = draftOf(trigger = leaf("edge"), actions = listOf(ComponentDraft("toast")))

        assertNull(draft.enableRefusal(registryOf("edge")))
    }

    @Test
    fun `two edge-only triggers under ALL cannot start, and the reason names the mistake`() {
        // Reachable through the picker before the change this covers, and
        // still reachable through an import: two components that only ever
        // produce events can never be true at the same instant.
        val draft = draftOf(
            trigger = TriggerDraft.Group(TriggerNode.Op.ALL, listOf(leaf("edge_a"), leaf("edge_b"))),
            actions = listOf(ComponentDraft("toast")),
        )

        val message = draft.enableRefusal(registryOf("edge_a", "edge_b"))

        assertTrue(
            "was: $message",
            message!!.startsWith("This rule can never start."),
        )
    }

    @Test
    fun `Rule enableRefusal answers the same question for a saved rule`() {
        val rule = draftOf(trigger = null, actions = emptyList()).toRuleOrNull()!!

        assertEquals(
            "Add a trigger and an action before switching this on.",
            rule.enableRefusal(registryOf()),
        )
    }
}
