package app.phueber.trigly.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The action tree ([ActionNode]) and every reader that walks it.
 *
 * The readers are the part worth the most here. A reader that still walks only
 * the top level of [Rule.actions] skips every action inside a branch, and the
 * result is quiet: a permission nobody asks for, a reference nobody checks, an
 * id nobody mints again. Each test names one reader and puts the action it
 * must find inside a branch.
 */
class ActionTreeTest {

    private fun spec(type: String, config: Map<String, String> = emptyMap()) = ComponentSpec(type, config)

    /** `[a, Parallel([[b, c], [d]]), e]`: the shape every test below reads. */
    private val tree: List<ActionNode> = listOf(
        spec("a"),
        ActionNode.Parallel(listOf(listOf(spec("b"), spec("c")), listOf(spec("d")))),
        spec("e"),
    )

    // --- the walk ---------------------------------------------------------------

    @Test
    fun `the leaves come in list order, and branch by branch inside a block`() {
        assertEquals(listOf("a", "b", "c", "d", "e"), tree.leafActions().map { it.type })
    }

    @Test
    fun `every leaf has a path that leads back to it`() {
        val paths = tree.leafActionPaths()
        assertEquals(
            listOf(listOf(0), listOf(1, 0, 0), listOf(1, 0, 1), listOf(1, 1, 0), listOf(2)),
            paths.map { it.first },
        )
        paths.forEach { (path, leaf) -> assertEquals(leaf, tree.actionAt(path)) }
    }

    @Test
    fun `a path to a block or a branch names no action`() {
        assertNull(tree.actionAt(listOf(1)))
        assertNull(tree.actionAt(listOf(1, 0)))
        assertNull(tree.actionAt(listOf(9)))
        assertEquals(-1, tree.leafIndexOf(listOf(1)))
        assertEquals(3, tree.leafIndexOf(listOf(1, 1, 0)))
    }

    @Test
    fun `a flat list is a tree with only leaves`() {
        val flat: List<ActionNode> = listOf(spec("a"), spec("b"))
        assertEquals(flat, flat.leafActions())
        assertFalse(flat.hasParallel())
        assertTrue(tree.hasParallel())
    }

    @Test
    fun `mapping the specs keeps the shape`() {
        val mapped = tree.mapActionSpecs { it.copy(config = mapOf("seen" to "yes")) }
        assertTrue(mapped[1] is ActionNode.Parallel)
        assertTrue(mapped.leafActions().all { it.config == mapOf("seen" to "yes") })
        assertEquals(tree.leafActions().map { it.type }, mapped.leafActions().map { it.type })
    }

    // --- what finishes before what ----------------------------------------------

    @Test
    fun `an action before a block is finished before every branch starts`() {
        assertTrue(tree.finishesBefore(0, 1))
        assertTrue(tree.finishesBefore(0, 3))
    }

    @Test
    fun `an earlier action in the same branch is finished first`() {
        assertTrue(tree.finishesBefore(1, 2))
    }

    @Test
    fun `two branches of one block are never finished before each other`() {
        assertFalse(tree.finishesBefore(1, 3))
        assertFalse(tree.finishesBefore(2, 3))
    }

    @Test
    fun `every action in a block is finished before the action after it`() {
        (0..3).forEach { assertTrue("leaf $it", tree.finishesBefore(it, 4)) }
    }

    @Test
    fun `nothing is finished before itself or before an earlier action`() {
        assertFalse(tree.finishesBefore(2, 2))
        assertFalse(tree.finishesBefore(4, 0))
    }

    // --- the readers --------------------------------------------------------------

    private val permission = ComponentRequirement.RuntimePermission("android.permission.EXAMPLE")

    private fun action(
        type: String,
        requirements: List<ComponentRequirement> = emptyList(),
        fields: List<ConfigField> = emptyList(),
        normalise: (Map<String, String>) -> Map<String, String> = { it },
        outputs: List<VariableSpec> = emptyList(),
    ): ActionFactory = object : ActionFactory {
        override val type = type
        override val requirements = requirements
        override val configFields = fields
        override val variables = outputs
        override fun normalise(config: Map<String, String>) = normalise(config)
        override fun create(config: Map<String, String>): Action = object : Action {
            override suspend fun execute(event: TriggerEvent) = ActionResult.Success()
        }
    }

    private val trigger = object : TriggerFactory {
        override val type = "t"
        override fun create(config: Map<String, String>): Trigger = object : Trigger {
            override fun events() = kotlinx.coroutines.flow.emptyFlow<TriggerEvent>()
        }
    }

    private fun ruleWith(vararg actions: ActionNode) =
        Rule(id = "r", name = "r", trigger = ComponentSpec("t"), actions = actions.toList())

    @Test
    fun `a permission an action in a branch needs is a permission the rule needs`() {
        val registry = Registry(
            triggerFactories = listOf(trigger),
            actionFactories = listOf(action("plain"), action("needs", listOf(permission))),
        )
        val rule = ruleWith(
            spec("plain"),
            ActionNode.Parallel(listOf(listOf(spec("plain")), listOf(spec("needs")))),
        )

        assertEquals(listOf(permission), registry.requirementsOf(rule))
    }

    @Test
    fun `normalising a rule fills in the actions inside a branch`() {
        val registry = Registry(
            triggerFactories = listOf(trigger),
            actionFactories = listOf(action("old", normalise = { it + ("added" to "yes") })),
        )
        val rule = ruleWith(ActionNode.Parallel(listOf(listOf(spec("old")), emptyList())))

        assertEquals("yes", registry.normalise(rule).actions.leafActions().single().config["added"])
    }

    @Test
    fun `a duplicate or an import mints a new id for an action inside a branch`() {
        val registry = Registry(
            triggerFactories = listOf(trigger),
            actionFactories = listOf(action("ided", fields = listOf(ConfigField.GeneratedId("id", "Id")))),
        )
        val rule = ruleWith(ActionNode.Parallel(listOf(listOf(spec("ided", mapOf("id" to "old"))))))

        val fresh = rule.withFreshGeneratedIds(registry)

        assertTrue(fresh.actions.single() is ActionNode.Parallel)
        assertNotEquals("old", fresh.actions.leafActions().single().config["id"])
    }

    @Test
    fun `an app variable read inside a branch is a variable the rule reads`() {
        val rule = ruleWith(
            ActionNode.Parallel(listOf(listOf(spec("say", mapOf("text" to "{{app.count}}"))))),
        )
        val read = rule.appVariablesRead { if (it.type == "say") mapOf("text" to Substitution.TEXT) else emptyMap() }
        assertEquals(setOf("count"), read)
    }

    // --- what an action can read --------------------------------------------------

    private val produced = VariableSpec(key = "value", label = "Value", sample = "1")

    private fun variablesOf(type: String) = if (type == "out") listOf(produced) else emptyList()

    private fun reach(actions: List<ActionNode>) = VariableReach(
        trigger = null,
        actions = actions,
        variablesOf = ::variablesOf,
        writesOf = { type ->
            if (type == "set") {
                listOf(
                    VariableWriteSpec(
                        nameKey = "name",
                        scopeKey = null,
                        namespaceByScopeValue = emptyMap(),
                        defaultNamespace = VariableScope.LOCAL,
                        sample = "1",
                    )
                )
            } else {
                emptyList()
            }
        },
    )

    private fun List<ScopedVariable>.has(scope: String, key: String) =
        any { it.scope == scope && it.spec.key == key }

    @Test
    fun `an action in a branch is not offered what the branch beside it produces`() {
        // [Parallel([[out], [reader]]), reader]
        val actions = listOf(
            ActionNode.Parallel(listOf(listOf(spec("out")), listOf(spec("reader")))),
            spec("reader"),
        )
        val inSibling = reach(actions).at(ReadPoint.Action(1))
        val afterBlock = reach(actions).at(ReadPoint.Action(2))

        assertFalse(inSibling.has("out", "value"))
        assertFalse(inSibling.has(VariableScope.ACTION, "value"))
        assertTrue(afterBlock.has("out", "value"))
        assertTrue(afterBlock.has(VariableScope.ACTION, "value"))
    }

    @Test
    fun `a run value set in one branch is not offered to the branch beside it`() {
        val actions = listOf(
            ActionNode.Parallel(
                listOf(
                    listOf(spec("set", mapOf("name" to "x")), spec("reader")),
                    listOf(spec("reader")),
                )
            ),
            spec("reader"),
        )
        val r = reach(actions)

        assertTrue("same branch, later", r.at(ReadPoint.Action(1)).has(VariableScope.LOCAL, "x"))
        assertFalse("the other branch", r.at(ReadPoint.Action(2)).has(VariableScope.LOCAL, "x"))
        assertTrue("after the block", r.at(ReadPoint.Action(3)).has(VariableScope.LOCAL, "x"))
    }

    @Test
    fun `the numbering counts every action in the rule, inside a block or not`() {
        val actions = listOf(
            spec("out"),
            ActionNode.Parallel(listOf(listOf(spec("out")), listOf(spec("out")))),
            spec("reader"),
        )
        val afterBlock = reach(actions).at(ReadPoint.Action(3))

        assertTrue(afterBlock.has("out", "value"))
        assertTrue(afterBlock.has("out_2", "value"))
        assertTrue(afterBlock.has("out_3", "value"))
    }
}
