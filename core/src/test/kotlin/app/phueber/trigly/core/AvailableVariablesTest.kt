package app.phueber.trigly.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [availableVariables], which the picker lists from, and [variableProblems],
 * which validates a field's references against that list.
 */
class AvailableVariablesTest {

    private fun one(type: String) = TriggerNode.One(ComponentSpec(type, emptyMap()))

    private fun all(vararg children: TriggerNode) =
        TriggerNode.Group(TriggerNode.Op.ALL, children.toList())

    private fun spec(key: String, alwaysPresent: Boolean = true) =
        VariableSpec(key = key, label = key, sample = "sample-$key", alwaysPresent = alwaysPresent)

    // --- availableVariables ------------------------------------------------------------

    @Test
    fun `a null tree yields only the engine variables`() {
        val available = availableVariables(null) { emptyList() }

        assertEquals(VariableScope.engineVariables, available)
    }

    @Test
    fun `the engine variables are always present`() {
        val tree = one("notification_posted")

        val available = availableVariables(tree) { listOf(spec("title")) }

        assertTrue(available.containsAll(VariableScope.engineVariables))
    }

    @Test
    fun `a one-leaf tree offers its specs under trigger scope, not type-qualified`() {
        val tree = one("notification_posted")

        val available = availableVariables(tree) { type ->
            if (type == "notification_posted") listOf(spec("title"), spec("text")) else emptyList()
        }

        assertTrue(available.any { it.scope == VariableScope.TRIGGER && it.spec.key == "title" })
        assertTrue(available.any { it.scope == VariableScope.TRIGGER && it.spec.key == "text" })
        // The type-qualified form says nothing extra for one leaf, so it is
        // not offered at all.
        assertTrue(available.none { it.scope == "notification_posted" })
    }

    @Test
    fun `two leaves of different types are each offered under their own type`() {
        val tree = all(one("bluetooth_connected"), one("sms_received"))
        val declarations = mapOf(
            "bluetooth_connected" to listOf(spec("name")),
            "sms_received" to listOf(spec("body")),
        )

        val available = availableVariables(tree) { declarations[it].orEmpty() }

        assertTrue(available.any { it.scope == "bluetooth_connected" && it.spec.key == "name" })
        assertTrue(available.any { it.scope == "sms_received" && it.spec.key == "body" })
    }

    @Test
    fun `an instance spec is never always present, whatever it declared`() {
        // Another leaf may be what fired, so a type-qualified reference can
        // never be relied on, even for a key that leaf always emits.
        val tree = all(one("bluetooth_connected"), one("sms_received"))
        val declarations = mapOf(
            "bluetooth_connected" to listOf(spec("name", alwaysPresent = true)),
            "sms_received" to listOf(spec("body", alwaysPresent = true)),
        )

        val available = availableVariables(tree) { declarations[it].orEmpty() }
        val qualified = available.filter {
            it.scope == "bluetooth_connected" || it.scope == "sms_received"
        }

        assertTrue(qualified.isNotEmpty())
        assertTrue(qualified.all { !it.spec.alwaysPresent })
    }

    /**
     * Two or more leaves: the short form is not offered at all, because it
     * cannot say which payload arrives. This reverses what
     * `docs/variables.md` section 3 first decided, and section 15 records the
     * reversal and the rewrite that makes it survivable.
     */
    @Test
    fun `two leaves offer nothing under trigger scope`() {
        val tree = all(one("bluetooth_connected"), one("sms_received"))
        val declarations = mapOf(
            "bluetooth_connected" to listOf(spec("name")),
            "sms_received" to listOf(spec("body")),
        )

        val available = availableVariables(tree) { declarations[it].orEmpty() }

        assertTrue(
            "the short form cannot say which leaf fired, so it is not offered",
            available.none { it.scope == VariableScope.TRIGGER },
        )
    }

    /**
     * The requirement this whole change exists for: two leaves of one type are
     * two separate things to read, not one shared namespace whose value
     * depends on which of them happened to fire.
     */
    @Test
    fun `two leaves of the same type offer two numbered namespaces`() {
        val tree = all(one("notification_posted"), one("notification_posted"))

        val available = availableVariables(tree) { type ->
            if (type == "notification_posted") listOf(spec("title")) else emptyList()
        }

        assertTrue(available.any { it.scope == "notification_posted" && it.spec.key == "title" })
        assertTrue(available.any { it.scope == "notification_posted_2" && it.spec.key == "title" })
        assertTrue(available.none { it.scope == VariableScope.TRIGGER })
    }

    @Test
    fun `three leaves of one type are numbered to three`() {
        val tree = all(*Array(3) { one("notification_posted") })

        val available = availableVariables(tree) { listOf(spec("title")) }
        val scopes = available.map { it.scope }.filter { it.startsWith("notification_posted") }

        assertEquals(
            listOf("notification_posted", "notification_posted_2", "notification_posted_3"),
            scopes,
        )
    }

    /**
     * Only one leaf fires per run, so no instance form can promise a value,
     * however the leaf declared it. The one-leaf case is the exception and
     * keeps its declaration, because there is no other leaf to lose to.
     */
    @Test
    fun `an instance form is never always present once there are two leaves`() {
        val tree = all(one("bluetooth_connected"), one("bluetooth_connected"))

        val available = availableVariables(tree) { listOf(spec("name", alwaysPresent = true)) }
        val instances = available.filter { it.scope.startsWith("bluetooth_connected") }

        assertTrue(instances.isNotEmpty())
        assertTrue(instances.all { !it.spec.alwaysPresent })
    }

    @Test
    fun `a one-leaf tree keeps the declaration's own mark`() {
        val tree = one("bluetooth_connected")

        val available = availableVariables(tree) { listOf(spec("name", alwaysPresent = true)) }
        val shared = available.single {
            it.scope == VariableScope.TRIGGER && it.spec.key == "name"
        }

        assertTrue(shared.spec.alwaysPresent)
    }

    // --- availableActionOutputs --------------------------------------------------------

    /**
     * The declarations a rule's actions make, for the tests below. Two
     * producing types and one that produces nothing, which is what almost
     * every action is.
     */
    private fun actionDeclarations(type: String): List<VariableSpec> = when (type) {
        "set_variable" -> listOf(spec("value"))
        "set_rule_enabled" -> listOf(spec("enabled"))
        else -> emptyList()
    }

    @Test
    fun `the first action has no earlier action to read from`() {
        val types = listOf("set_variable", "post_notification")

        val available = availableActionOutputs(types, index = 0, ::actionDeclarations)

        assertEquals(emptyList<ScopedVariable>(), available)
    }

    @Test
    fun `an action is offered what the action above it produces`() {
        val types = listOf("set_variable", "post_notification")

        val available = availableActionOutputs(types, index = 1, ::actionDeclarations)

        // Both forms: the unnumbered one for "whichever produced it last", and
        // the instance one for "that action there".
        assertTrue(available.any { it.scope == VariableScope.ACTION && it.spec.key == "value" })
        assertTrue(available.any { it.scope == "set_variable" && it.spec.key == "value" })
    }

    /**
     * The dead end this function exists to prevent. A reference to an action
     * further down the list resolves absent on every firing, because the
     * engine grows [ActionOutputs] as each action returns.
     */
    @Test
    fun `an action is not offered what a later action produces`() {
        val types = listOf("post_notification", "set_variable")

        val available = availableActionOutputs(types, index = 0, ::actionDeclarations)

        assertTrue(available.none { it.spec.key == "value" })
    }

    /**
     * An earlier action running is not the same as it producing: it can fail
     * first, and `set_variable`'s clear mode succeeds while storing nothing.
     * So the mark is forced off however the declaration reads.
     */
    @Test
    fun `an action output is never marked always present`() {
        val types = listOf("set_variable", "post_notification")

        val available = availableActionOutputs(types, index = 1) { type ->
            if (type == "set_variable") listOf(spec("value", alwaysPresent = true))
            else emptyList()
        }

        assertTrue(available.isNotEmpty())
        assertTrue(available.all { !it.spec.alwaysPresent })
    }

    @Test
    fun `one producing action above is offered both unnumbered and by name`() {
        val types = listOf("set_variable", "post_notification", "toast")

        val available = availableActionOutputs(types, index = 2, ::actionDeclarations)

        assertTrue(available.any { it.scope == VariableScope.ACTION && it.spec.key == "value" })
        assertTrue(available.any { it.scope == "set_variable" && it.spec.key == "value" })
    }

    @Test
    fun `two producing types above are also offered type-qualified`() {
        val types = listOf("set_variable", "set_rule_enabled", "post_notification")

        val available = availableActionOutputs(types, index = 2, ::actionDeclarations)

        assertTrue(available.any { it.scope == VariableScope.ACTION && it.spec.key == "value" })
        assertTrue(available.any { it.scope == VariableScope.ACTION && it.spec.key == "enabled" })
        assertTrue(available.any { it.scope == "set_variable" && it.spec.key == "value" })
        assertTrue(available.any { it.scope == "set_rule_enabled" && it.spec.key == "enabled" })
    }

    /**
     * The action half of the same requirement. Two `set_variable` actions
     * above this one are two things that produced a value, and each is
     * readable on its own. Before instances they collapsed into one namespace
     * whose value was whichever ran most recently, which is still available as
     * the unnumbered form for a rule that does not care.
     */
    @Test
    fun `two actions of the same type above are each offered by name`() {
        val types = listOf("set_variable", "set_variable", "post_notification")

        val available = availableActionOutputs(types, index = 2, ::actionDeclarations)

        assertTrue(available.any { it.scope == "set_variable" && it.spec.key == "value" })
        assertTrue(available.any { it.scope == "set_variable_2" && it.spec.key == "value" })
        assertTrue(available.any { it.scope == VariableScope.ACTION && it.spec.key == "value" })
    }

    // --- variableProblems ---------------------------------------------------------------

    @Test
    fun `a well-formed reference to an offered name has no problems`() {
        val available = listOf(ScopedVariable(VariableScope.TRIGGER, spec("text")))

        val problems = variableProblems("Value: {{trigger.text}}", available)

        assertTrue(problems.isEmpty())
    }

    @Test
    fun `a reference to a name nobody offers yields one problem naming it`() {
        val problems = variableProblems("Value: {{trigger.nope}}", emptyList())

        assertEquals(1, problems.size)
        assertTrue(problems.single().contains("{{trigger.nope}}"))
    }

    @Test
    fun `a malformed reference yields one problem`() {
        val problems = variableProblems("Value: {{no dot}}", emptyList())

        assertEquals(1, problems.size)
    }

    @Test
    fun `a reference to a key that is only sometimes present is not a problem`() {
        // A legitimate rule. alwaysPresent is how the editor warns about it
        // separately, not a reason to refuse the save.
        val onlySometimes = spec("name", alwaysPresent = false)
        val available = listOf(ScopedVariable(VariableScope.TRIGGER, onlySometimes))

        val problems = variableProblems("{{trigger.name}}", available)

        assertTrue(problems.isEmpty())
    }

    /**
     * The case that would break the feature if it were checked harder. An app
     * variable is written by a rule, so the rule that reads one is very often
     * saved before the rule that first sets it. Refusing that save would make
     * the pair impossible to write in either order.
     */
    @Test
    fun `an app reference is not a problem even though nothing offers it`() {
        val available = availableVariables(null) { emptyList() }

        assertEquals(
            emptyList<String>(),
            variableProblems("Trip {{app.trip_count}}", available),
        )
    }

    /**
     * The two halves together, which is what the editor actually asks. Before
     * `availableActionOutputs` existed, `{{action.value}}` was a name nobody
     * offered, so save-time validation refused every field that read an
     * earlier action's output. That made the whole action-output feature
     * unreachable from the editor while the engine resolved it correctly.
     */
    @Test
    fun `a reference to an earlier action's output is not a problem`() {
        val available = availableVariables(null) { emptyList() } +
            availableActionOutputs(
                listOf("set_variable", "post_notification"),
                index = 1,
                ::actionDeclarations,
            )

        assertEquals(
            emptyList<String>(),
            variableProblems("Now {{action.value}}", available),
        )
    }

    /** And the same reference from the action above it is still refused. */
    @Test
    fun `a reference to a later action's output is a problem`() {
        val available = availableVariables(null) { emptyList() } +
            availableActionOutputs(
                listOf("post_notification", "set_variable"),
                index = 0,
                ::actionDeclarations,
            )

        val problems = variableProblems("Now {{action.value}}", available)

        assertEquals(1, problems.size)
        assertTrue(problems.single().contains("{{action.value}}"))
    }

    /** Being lenient about app scope must not make anything else lenient. */
    @Test
    fun `an unknown name in another scope is still a problem`() {
        val available = availableVariables(null) { emptyList() }

        assertEquals(1, variableProblems("{{trigger.nonsense}}", available).size)
        assertEquals(1, variableProblems("{{event.nonsense}}", available).size)
        assertEquals(1, variableProblems("{{app.ok}} {{rule.nonsense}}", available).size)
    }
}
