package app.phueber.trigly.ui

import app.phueber.trigly.core.Action
import app.phueber.trigly.core.ActionFactory
import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.ComponentSpec
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.NO_TRIGGER
import app.phueber.trigly.core.Registry
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerEvent
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

    /** An edge trigger with one required field, for the "picked but not
     * filled in" half of `enableRefusal`. */
    private class RequiredFieldTrigger(override val type: String, override val displayName: String) :
        TriggerFactory {
        override val configFields = listOf(ConfigField.Text(key = "value", label = "Value", required = true))
        override fun create(config: Map<String, String>): Trigger =
            error("not built in this test")
    }

    /** An action with one required field, the same shape as [RequiredFieldTrigger]. */
    private class RequiredFieldAction(override val type: String, override val displayName: String) :
        ActionFactory {
        override val configFields = listOf(ConfigField.Text(key = "value", label = "Value", required = true))
        override fun create(config: Map<String, String>): Action = object : Action {
            override suspend fun execute(event: TriggerEvent): ActionResult =
                error("not run in this test")
        }
    }

    private fun registryOf(
        triggers: List<TriggerFactory> = emptyList(),
        actions: List<ActionFactory> = emptyList(),
    ) = Registry(triggerFactories = triggers, actionFactories = actions)

    private fun registryOf(vararg types: String) = registryOf(triggers = types.map { EdgeTrigger(it) })

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

    // --- absent versus wrong: a picked component nobody has finished --------

    @Test
    fun `a trigger with an unfilled required field is named, not folded into 'add a trigger'`() {
        val draft = draftOf(
            trigger = leaf("needs_value"),
            actions = listOf(ComponentDraft("toast")),
        )
        val registry = registryOf(
            triggers = listOf(RequiredFieldTrigger("needs_value", "Needs a value")),
        )

        assertEquals(
            "Finish setting up Needs a value before switching this on.",
            draft.enableRefusal(registry),
        )
    }

    @Test
    fun `an action with an unfilled required field is named, not folded into 'add an action'`() {
        val draft = draftOf(
            trigger = leaf("edge"),
            actions = listOf(ComponentDraft("needs_value")),
        )
        val registry = registryOf(
            triggers = listOf(EdgeTrigger("edge")),
            actions = listOf(RequiredFieldAction("needs_value", "Needs a value")),
        )

        assertEquals(
            "Finish setting up Needs a value (action 1) before switching this on.",
            draft.enableRefusal(registry),
        )
    }

    @Test
    fun `filling in the required field lets the rule be switched on`() {
        val draft = draftOf(
            trigger = leaf("edge"),
            actions = listOf(ComponentDraft("needs_value", config = mapOf("value" to "hi"))),
        )
        val registry = registryOf(
            triggers = listOf(EdgeTrigger("edge")),
            actions = listOf(RequiredFieldAction("needs_value", "Needs a value")),
        )

        assertNull(draft.enableRefusal(registry))
    }

    @Test
    fun `an unfinished trigger and an unfinished action are both named`() {
        val draft = draftOf(
            trigger = leaf("needs_value"),
            actions = listOf(ComponentDraft("also_needs_value")),
        )
        val registry = registryOf(
            triggers = listOf(RequiredFieldTrigger("needs_value", "Trigger name")),
            actions = listOf(RequiredFieldAction("also_needs_value", "Action name")),
        )

        assertEquals(
            "Finish setting up Trigger name and Action name (action 1) before switching this on.",
            draft.enableRefusal(registry),
        )
    }
}
