package app.phueber.trigly.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VariableReach]: which variables can be read at one point in a rule.
 *
 * One test per scope, plus the ordering rules, because the scopes do not follow
 * one rule and the differences are the substance. A run value reaches forwards
 * only, a rule value reaches both ways, and an app value reaches out of the rule
 * entirely. Getting any of those wrong produces a picker that offers a name the
 * engine will never resolve, or hides one it resolves every time.
 *
 * The declarations are fakes rather than the real factories, for the reason
 * [AvailableVariablesTest]'s are: `:core` cannot see a factory, and the point
 * here is the reachability rules rather than any component's declaration.
 */
class VariableReachTest {

    // --- fixtures ----------------------------------------------------------------------

    /** A writer shaped like the one real writing action: a name key and a scope key. */
    private val writeDeclaration = VariableWriteSpec(
        nameKey = "name",
        scopeKey = "scope",
        namespaceByScopeValue = mapOf(
            "run" to VariableScope.LOCAL,
            "rule" to VariableScope.MINE,
            "app" to VariableScope.APP,
        ),
        defaultNamespace = VariableScope.APP,
        sample = "4",
    )

    private fun writesOf(type: String): List<VariableWriteSpec> =
        if (type == WRITER) listOf(writeDeclaration) else emptyList()

    private fun variablesOf(type: String): List<VariableSpec> = when (type) {
        NOTIFICATION -> listOf(
            VariableSpec(key = "title", label = "Title", sample = "Dinner")
        )
        WRITER -> listOf(
            VariableSpec(key = "value", label = "Value stored", sample = "4", alwaysPresent = false)
        )
        else -> emptyList()
    }

    /** `set_variable`-shaped config: writes [name] into [scope]. */
    private fun writer(name: String, scope: String = "app") =
        ComponentSpec(WRITER, mapOf("name" to name, "scope" to scope))

    private fun toast(text: String = "hello") = ComponentSpec(TOAST, mapOf("text" to text))

    private fun reach(
        trigger: TriggerNode? = TriggerNode.One(ComponentSpec(NOTIFICATION)),
        actions: List<ComponentSpec> = emptyList(),
        savedApp: List<ScopedVariable> = emptyList(),
        savedMine: List<ScopedVariable> = emptyList(),
        otherRules: List<Rule> = emptyList(),
    ) = VariableReach(
        trigger = trigger,
        actions = actions,
        variablesOf = ::variablesOf,
        writesOf = ::writesOf,
        savedAppVariables = savedApp,
        savedRuleVariables = savedMine,
        otherRules = otherRules,
        displayNameOf = { if (it == WRITER) "Set an app variable" else it },
    )

    private fun stored(scope: String, name: String, value: String) = ScopedVariable(
        scope,
        VariableSpec(key = name, label = name, sample = value, alwaysPresent = false),
    )

    private fun List<ScopedVariable>.has(scope: String, name: String) =
        any { it.scope == scope && it.spec.key == name }

    private fun List<ScopedVariable>.find(scope: String, name: String) =
        firstOrNull { it.scope == scope && it.spec.key == name }

    private companion object {
        const val NOTIFICATION = "notification_posted"
        const val WRITER = "set_variable"
        const val TOAST = "toast"
    }

    // --- the scopes the engine fills in -------------------------------------------------

    /**
     * The trigger tree, the event and the rule read the same everywhere. They
     * are filled in from the event that started the run, before the first
     * action, so no position in the rule can change the answer.
     */
    @Test
    fun `the trigger and engine scopes read the same at every point`() {
        val reach = reach(actions = listOf(toast(), toast()))

        listOf(ReadPoint.Trigger, ReadPoint.Action(0), ReadPoint.Action(1)).forEach { point ->
            val available = reach.at(point)
            assertTrue("$point", available.has(VariableScope.TRIGGER, "title"))
            assertTrue("$point", available.has(VariableScope.EVENT, VariableScope.EVENT_TIME))
            assertTrue("$point", available.has(VariableScope.RULE, VariableScope.RULE_NAME))
        }
    }

    /**
     * An action output exists only once the action has returned, so a trigger,
     * which is built before any action runs, is offered none of them.
     */
    @Test
    fun `an action output is offered to a later action and never to a trigger`() {
        val reach = reach(actions = listOf(writer("count"), toast()))

        assertFalse(reach.at(ReadPoint.Trigger).has(VariableScope.ACTION, "value"))
        assertFalse(reach.at(ReadPoint.Action(0)).has(VariableScope.ACTION, "value"))
        assertTrue(reach.at(ReadPoint.Action(1)).has(VariableScope.ACTION, "value"))
    }

    // --- run scope ----------------------------------------------------------------------

    /**
     * The point of the whole change: a name typed into a writing action becomes
     * a name the actions below it can pick.
     */
    @Test
    fun `a run value is offered to the actions after the one that writes it`() {
        val reach = reach(actions = listOf(toast(), writer("total", scope = "run"), toast()))

        assertFalse(reach.at(ReadPoint.Action(0)).has(VariableScope.LOCAL, "total"))
        assertFalse(reach.at(ReadPoint.Action(1)).has(VariableScope.LOCAL, "total"))
        assertTrue(reach.at(ReadPoint.Action(2)).has(VariableScope.LOCAL, "total"))
    }

    /**
     * A run value lives on the coroutine running the firing. A trigger is built
     * before the firing exists, so there is never one to read.
     */
    @Test
    fun `a run value is never offered to a trigger`() {
        val reach = reach(actions = listOf(writer("total", scope = "run")))

        assertFalse(reach.at(ReadPoint.Trigger).has(VariableScope.LOCAL, "total"))
    }

    /** Nothing stores a run value, so nothing but the draft can offer one. */
    @Test
    fun `a run value never comes from a store`() {
        val reach = reach(
            actions = listOf(toast()),
            savedApp = listOf(stored(VariableScope.APP, "total", "9")),
        )

        assertFalse(reach.at(ReadPoint.Action(0)).has(VariableScope.LOCAL, "total"))
    }

    // --- rule scope ---------------------------------------------------------------------

    /**
     * The case that must not be refused. A rule value survives the run, so an
     * action reading what a *later* action writes is reading last run's value,
     * which is exactly how a counter is built.
     */
    @Test
    fun `a rule value written later is offered to an earlier action`() {
        val reach = reach(actions = listOf(toast(), writer("count", scope = "rule")))

        assertTrue(reach.at(ReadPoint.Action(0)).has(VariableScope.MINE, "count"))
        assertTrue(reach.at(ReadPoint.Action(1)).has(VariableScope.MINE, "count"))
        assertTrue(reach.at(ReadPoint.Trigger).has(VariableScope.MINE, "count"))
    }

    /** And the store is a source of its own, for a rule that has already run. */
    @Test
    fun `a rule value in the store is offered with its current value as the sample`() {
        val reach = reach(savedMine = listOf(stored(VariableScope.MINE, "count", "7")))

        assertEquals("7", reach.at(ReadPoint.Action(0)).find(VariableScope.MINE, "count")?.spec?.sample)
    }

    /** Another rule's values are keyed to that rule, so they are not this rule's. */
    @Test
    fun `another rule's rule-scope write is not offered`() {
        val other = Rule("other", "Other", NO_TRIGGER, listOf(writer("count", scope = "rule")))
        val reach = reach(otherRules = listOf(other))

        assertFalse(reach.at(ReadPoint.Action(0)).has(VariableScope.MINE, "count"))
    }

    // --- app scope ----------------------------------------------------------------------

    @Test
    fun `an app value is offered at every point, whoever writes it`() {
        val other = Rule("other", "Trip logger", NO_TRIGGER, listOf(writer("trip_count")))
        val reach = reach(
            actions = listOf(toast(), writer("last_seen")),
            savedApp = listOf(stored(VariableScope.APP, "greeting", "hi")),
            otherRules = listOf(other),
        )

        listOf(ReadPoint.Trigger, ReadPoint.Action(0), ReadPoint.Action(1)).forEach { point ->
            val available = reach.at(point)
            assertTrue("$point", available.has(VariableScope.APP, "greeting"))
            assertTrue("$point", available.has(VariableScope.APP, "trip_count"))
            assertTrue("$point", available.has(VariableScope.APP, "last_seen"))
        }
    }

    /**
     * A rule saved before the scope field existed has no value under that key,
     * and what it wrote then is what it writes now: the shared scope. The
     * declaration says so, and the editor must not read it differently from the
     * action that stores it.
     */
    @Test
    fun `a write with no scope key lands in app scope`() {
        val reach = reach(actions = listOf(ComponentSpec(WRITER, mapOf("name" to "count"))))

        assertTrue(reach.at(ReadPoint.Action(0)).has(VariableScope.APP, "count"))
    }

    /** The scope value is matched the way the action's own parse matches it. */
    @Test
    fun `a scope value is matched ignoring case and surrounding space`() {
        val reach = reach(actions = listOf(writer("total", scope = " RUN "), toast()))

        assertTrue(reach.at(ReadPoint.Action(1)).has(VariableScope.LOCAL, "total"))
    }

    // --- how an offer is labelled -------------------------------------------------------

    /**
     * A person has to be able to tell a value this rule writes from one that is
     * already stored. Both live under the same heading, so the difference has to
     * be said on the row.
     */
    @Test
    fun `a name this rule writes says which action writes it`() {
        val reach = reach(actions = listOf(writer("trip_count"), toast()))

        val offered = reach.at(ReadPoint.Action(1)).find(VariableScope.APP, "trip_count")

        assertEquals("Set by Set an app variable (action 1).", offered?.spec?.help)
    }

    @Test
    fun `a name that is both stored and written keeps the stored value as its sample`() {
        val reach = reach(
            actions = listOf(writer("trip_count")),
            savedApp = listOf(stored(VariableScope.APP, "trip_count", "12")),
        )

        val offered = reach.at(ReadPoint.Action(0)).find(VariableScope.APP, "trip_count")

        assertEquals("12", offered?.spec?.sample)
        assertTrue(offered?.spec?.help.orEmpty().contains("already exists"))
        // Once, not twice. The picker keys its rows by the reference.
        assertEquals(
            1,
            reach.at(ReadPoint.Action(0)).count {
                it.scope == VariableScope.APP && it.spec.key == "trip_count"
            },
        )
    }

    /**
     * Never always present, whatever wrote it. The writing action can fail
     * before it writes, and its clear mode succeeds while storing nothing.
     */
    @Test
    fun `a written name is never marked always present`() {
        val reach = reach(actions = listOf(writer("total", scope = "run"), toast()))

        assertFalse(
            reach.at(ReadPoint.Action(1)).find(VariableScope.LOCAL, "total")!!.spec.alwaysPresent
        )
    }

    /** Another rule's name says which rule, because that is where to go and change it. */
    @Test
    fun `a name another rule writes says which rule`() {
        val other = Rule("other", "Trip logger", NO_TRIGGER, listOf(writer("trip_count")))
        val reach = reach(otherRules = listOf(other))

        assertEquals(
            "Set by the rule 'Trip logger'.",
            reach.at(ReadPoint.Trigger).find(VariableScope.APP, "trip_count")?.spec?.help,
        )
    }

    // --- names that cannot be offered ---------------------------------------------------

    /**
     * A name built from a template is a real write to a name nobody can predict.
     * It cannot be offered, because there is no name to offer, and it must not
     * be warned about, because no name in that scope can then be called wrong.
     */
    @Test
    fun `a templated name is offered to nobody and silences the warning for its scope`() {
        val reach = reach(
            actions = listOf(writer("{{trigger.title}}", scope = "run"), toast()),
        )

        val available = reach.at(ReadPoint.Action(1))
        assertTrue(available.none { it.scope == VariableScope.LOCAL })
        assertEquals(
            emptyList<String>(),
            reach.warnings("{{local.anything}}", ReadPoint.Action(1)),
        )
    }

    /**
     * And the silence is bounded by the same ordering the offer is. A template
     * written *below* this action says nothing about what this action can read.
     */
    @Test
    fun `a templated run name below this action does not silence its warning`() {
        val reach = reach(
            actions = listOf(toast(), writer("{{trigger.title}}", scope = "run")),
        )

        assertEquals(1, reach.warnings("{{local.anything}}", ReadPoint.Action(0)).size)
    }

    /** A name no rule could refer to is not offered: the save refuses it anyway. */
    @Test
    fun `a name a rule could not read back is not offered`() {
        val reach = reach(actions = listOf(writer("two words"), toast()))

        assertTrue(reach.at(ReadPoint.Action(1)).none { it.scope == VariableScope.APP })
    }

    /** An unfinished action writes nothing, and offering "" would be nonsense. */
    @Test
    fun `an empty name is not offered`() {
        val reach = reach(actions = listOf(writer(""), toast()))

        assertTrue(reach.at(ReadPoint.Action(1)).none { it.scope == VariableScope.APP })
    }

    // --- warnings -----------------------------------------------------------------------

    @Test
    fun `a run name nothing writes is warned about where it is read`() {
        val reach = reach(actions = listOf(toast("{{local.totl}}")))

        val warnings = reach.warnings("{{local.totl}}", ReadPoint.Action(0))

        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("{{local.totl}}"))
    }

    @Test
    fun `a name that is written is not warned about`() {
        val reach = reach(actions = listOf(writer("total", scope = "run"), toast()))

        assertEquals(
            emptyList<String>(),
            reach.warnings("{{local.total}}", ReadPoint.Action(1)),
        )
    }

    /**
     * The mistake this wording exists for: the writing action chose one scope
     * and the reading field named another. Both halves look right on their own,
     * and neither screen says the two do not meet.
     */
    @Test
    fun `a name written in another scope is named in the warning`() {
        val reach = reach(actions = listOf(writer("total", scope = "run"), toast()))

        val warning = reach.warnings("{{app.total}}", ReadPoint.Action(1)).single()

        assertTrue(warning, warning.contains("{{local.total}}"))
    }

    @Test
    fun `a name no rule writes and no store holds is warned about in app scope`() {
        val reach = reach()

        assertEquals(1, reach.warnings("Trip {{app.trip_count}}", ReadPoint.Trigger).size)
    }

    /** One warning per reference, however many times it is written in the field. */
    @Test
    fun `a name read twice is warned about once`() {
        val reach = reach()

        assertEquals(
            1,
            reach.warnings("{{app.x}} and {{app.x}}", ReadPoint.Action(0)).size,
        )
    }

    /** The scopes the engine fills in are refused, not warned about. See [problems]. */
    @Test
    fun `a name in a scope the engine fills in is not warned about`() {
        val reach = reach()

        assertEquals(emptyList<String>(), reach.warnings("{{trigger.nonsense}}", ReadPoint.Trigger))
    }

    // --- refusals -----------------------------------------------------------------------

    /**
     * A warning is not a refusal, and this is the line between them. A rule that
     * reads a value another rule writes has to stay buildable, in either order,
     * and a rule run by another rule reads that rule's run values, which nothing
     * here can see.
     */
    @Test
    fun `a name nothing writes is still not a refusal`() {
        val reach = reach(actions = listOf(toast()))

        assertEquals(
            emptyList<String>(),
            reach.problems("{{local.totl}} {{mine.x}} {{app.y}}", ReadPoint.Action(0)),
        )
    }

    /** And the scopes the engine fills in are as strict as they ever were. */
    @Test
    fun `an unknown name in a scope the engine fills in is still refused`() {
        val reach = reach(actions = listOf(toast()))

        assertEquals(1, reach.problems("{{trigger.nonsense}}", ReadPoint.Action(0)).size)
        assertEquals(1, reach.problems("{{action.value}}", ReadPoint.Action(0)).size)
    }

    /** The picker and the save agree, because they are the same list. */
    @Test
    fun `a name the picker offers is never refused by the save`() {
        val actions = listOf(writer("total", scope = "run"), toast("{{local.total}}"))
        val reach = reach(actions = actions)

        assertTrue(reach.at(ReadPoint.Action(1)).has(VariableScope.LOCAL, "total"))
        assertEquals(emptyList<String>(), reach.problems("{{local.total}}", ReadPoint.Action(1)))
    }

    // --- the declaration itself ---------------------------------------------------------

    @Test
    fun `a write declaration reads the name and the scope out of a configuration`() {
        val written = writeDeclaration.writes(mapOf("name" to " total ", "scope" to "run"))

        assertEquals(WrittenVariable.Named(VariableScope.LOCAL, "total", "4"), written)
    }

    @Test
    fun `a write declaration reports a templated name rather than inventing one`() {
        val written = writeDeclaration.writes(mapOf("name" to "{{trigger.title}}", "scope" to "rule"))

        assertEquals(WrittenVariable.Unknowable(VariableScope.MINE), written)
    }

    @Test
    fun `a write declaration reports nothing for a name that cannot be read back`() {
        assertNull(writeDeclaration.writes(mapOf("name" to "")))
        assertNull(writeDeclaration.writes(mapOf("name" to "two words")))
    }

    /** Every component is asked, so a trigger that ever writes one is covered. */
    @Test
    fun `a rule reports every variable it writes`() {
        val rule = Rule(
            "id",
            "Rule",
            NO_TRIGGER,
            listOf(writer("count", scope = "rule"), writer("total", scope = "run")),
        )

        assertEquals(
            listOf(
                WrittenVariable.Named(VariableScope.MINE, "count", "4"),
                WrittenVariable.Named(VariableScope.LOCAL, "total", "4"),
            ),
            rule.variableWrites(::writesOf),
        )
    }
}
