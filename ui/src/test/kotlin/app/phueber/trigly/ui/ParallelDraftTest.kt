package app.phueber.trigly.ui

import app.phueber.trigly.core.Action
import app.phueber.trigly.core.ActionFactory
import app.phueber.trigly.core.ActionNode
import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.ComponentSpec
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.Registry
import app.phueber.trigly.core.Rule
import app.phueber.trigly.core.Substitution
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerEvent
import app.phueber.trigly.core.TriggerFactory
import app.phueber.trigly.core.VariableSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parallel block on the editor's side: [ActionDraft], the pure operations
 * the ViewModel applies to it, and the reference repair every edit runs.
 *
 * Pure functions over a draft, so they run on the JVM. The ViewModel calls
 * each of these with one line, and `RuleEditorViewModelTest` covers that
 * wiring on a device.
 */
class ParallelDraftTest {

    private fun c(type: String, text: String? = null) =
        ComponentDraft(type, text?.let { mapOf("text" to it) }.orEmpty())

    private val a = c("a")
    private val b = c("b")
    private val d = c("d")
    private val e = c("e")

    /** `[a, Parallel([[b], [d]]), e]` */
    private val list: List<ActionDraft> = listOf(a, ActionDraft.Parallel(listOf(listOf(b), listOf(d))), e)

    // --- draft to rule and back -----------------------------------------------------

    @Test
    fun `a rule with a parallel block round trips through the draft`() {
        val rule = Rule(
            id = "r",
            name = "r",
            trigger = ComponentSpec("t"),
            actions = listOf(
                ComponentSpec("a"),
                ActionNode.Parallel(listOf(listOf(ComponentSpec("b", mapOf("x" to "1"))), emptyList())),
            ),
        )

        assertEquals(rule, rule.toDraft().toRuleOrNull())
    }

    @Test
    fun `the leaves of a draft come in the order the engine numbers them`() {
        assertEquals(listOf(a, b, d, e), list.leafComponents())
    }

    // --- editing ---------------------------------------------------------------------

    @Test
    fun `an action is added to the end of one branch`() {
        val grown = list.addToBranch(listOf(1, 0), c("new"))
        assertEquals(listOf(listOf(b, c("new")), listOf(d)), (grown[1] as ActionDraft.Parallel).branches)
    }

    @Test
    fun `a branch is added empty at the end of the block`() {
        val grown = list.addBranch(1)
        assertEquals(3, (grown[1] as ActionDraft.Parallel).branches.size)
        assertTrue((grown[1] as ActionDraft.Parallel).branches.last().isEmpty())
    }

    @Test
    fun `removing one of two branches puts the other branch's actions in place of the block`() {
        assertEquals(listOf(a, d, e), list.removeActionAt(listOf(1, 0)))
    }

    @Test
    fun `removing a branch from three keeps the block`() {
        val three = list.addBranch(1).removeActionAt(listOf(1, 0))
        assertEquals(listOf(listOf(d), emptyList()), (three[1] as ActionDraft.Parallel).branches)
    }

    @Test
    fun `removing the last action of a branch keeps the branch`() {
        val emptied = list.removeActionAt(listOf(1, 0, 0))
        assertEquals(listOf(emptyList(), listOf(d)), (emptied[1] as ActionDraft.Parallel).branches)
    }

    @Test
    fun `removing the block removes every action in it`() {
        assertEquals(listOf(a, e), list.removeActionAt(listOf(1)))
    }

    @Test
    fun `an action moves inside its branch and not out of it`() {
        val two = list.addToBranch(listOf(1, 0), c("b2"))
        val moved = two.moveActionAt(listOf(1, 0, 1), listOf(1, 0, 0))
        assertEquals(listOf(c("b2"), b), (moved[1] as ActionDraft.Parallel).branches[0])

        assertSame("a move between two lists is refused", list, list.moveActionAt(listOf(1, 0, 0), listOf(0)))
    }

    @Test
    fun `the block moves in the list like an action`() {
        assertEquals(listOf(list[1], a, e), list.moveActionAt(listOf(1), listOf(0)))
    }

    @Test
    fun `an edit inside a branch leaves every other action the same object`() {
        val edited = list.updateComponentAt(listOf(1, 1, 0)) { it.copy(config = mapOf("k" to "v")) }
        assertSame(a, edited[0])
        assertSame(b, (edited[1] as ActionDraft.Parallel).branches[0][0])
        assertEquals(mapOf("k" to "v"), (edited[1] as ActionDraft.Parallel).branches[1][0].config)
    }

    // --- reference repair ---------------------------------------------------------------

    private class TextAction(override val type: String) : ActionFactory {
        override val configFields = listOf(ConfigField.Text(key = "text", label = "Text", substitution = Substitution.TEXT))
        override val variables = listOf(VariableSpec(key = "value", label = "Value", sample = "1"))
        override fun create(config: Map<String, String>): Action = object : Action {
            override suspend fun execute(event: TriggerEvent) = ActionResult.Success()
        }
    }

    @Test
    fun `removing an action inside a branch renumbers the references after the block`() {
        val registry = Registry(triggerFactories = emptyList(), actionFactories = listOf(TextAction("out")))
        val first = c("out")
        val second = c("out")
        val reader = c("out", "{{out_2.value}}")
        val before = RuleDraft(
            id = null,
            actions = listOf(ActionDraft.Parallel(listOf(listOf(first), listOf(second))), reader),
        )
        // The first `out` goes, so the second is now the first of its type.
        val after = before.copy(actions = before.actions.removeActionAt(listOf(0, 0, 0)))

        val repaired = repairReferences(before, after, registry)

        assertEquals("{{out.value}}", repaired.actions.leafComponents().last().config["text"])
    }

    // --- the enable gate ----------------------------------------------------------------

    private class EdgeTrigger(override val type: String) : TriggerFactory {
        override fun create(config: Map<String, String>): Trigger = error("not built")
    }

    private class RequiredAction(override val type: String) : ActionFactory {
        override val displayName = "Needs text"
        override val configFields = listOf(ConfigField.Text(key = "value", label = "Value", required = true))
        override fun create(config: Map<String, String>): Action = error("not built")
    }

    private val gateRegistry = Registry(
        triggerFactories = listOf(EdgeTrigger("edge")),
        actionFactories = listOf(RequiredAction("needs")),
    )

    @Test
    fun `a rule whose only entry is an empty block has no action to run`() {
        val draft = RuleDraft(
            id = null,
            name = "Empty block",
            trigger = TriggerDraft.One(ComponentDraft("edge")),
            actions = listOf(ActionDraft.Parallel(listOf(emptyList(), emptyList()))),
        )
        assertEquals("Add an action before switching this on.", draft.enableRefusal(gateRegistry))
    }

    @Test
    fun `an unfinished action inside a branch keeps the rule off, and is named`() {
        val draft = RuleDraft(
            id = null,
            name = "Unfinished",
            trigger = TriggerDraft.One(ComponentDraft("edge")),
            actions = listOf(ActionDraft.Parallel(listOf(emptyList(), listOf(ComponentDraft("needs"))))),
        )
        val refusal = draft.enableRefusal(gateRegistry)
        assertTrue(refusal.orEmpty(), refusal.orEmpty().contains("Needs text (action 1)"))

        val finished = draft.copy(
            actions = listOf(
                ActionDraft.Parallel(listOf(emptyList(), listOf(ComponentDraft("needs", mapOf("value" to "x"))))),
            ),
        )
        assertNull(finished.enableRefusal(gateRegistry))
    }
}
