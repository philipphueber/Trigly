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
    fun `a tree with two leaves of different types offers both type-qualified`() {
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
    fun `every type-qualified spec is never always present, whatever it declared`() {
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

    @Test
    fun `under trigger scope a key every leaf declares stays always present`() {
        val tree = all(one("bluetooth_connected"), one("sms_received"))
        val btSpecs = listOf(
            spec("state", alwaysPresent = true),
            spec("address", alwaysPresent = false),
        )
        val smsSpecs = listOf(
            spec("state", alwaysPresent = true),
            spec("sender", alwaysPresent = false),
        )
        val declarations = mapOf("bluetooth_connected" to btSpecs, "sms_received" to smsSpecs)

        val available = availableVariables(tree) { declarations[it].orEmpty() }
        val shared = available.single {
            it.scope == VariableScope.TRIGGER && it.spec.key == "state"
        }

        assertTrue(shared.spec.alwaysPresent)
    }

    @Test
    fun `under trigger scope a key only one leaf declares is not always present`() {
        // This recomputation is why availableVariables exists rather than the
        // screen copying a spec's own alwaysPresent.
        val tree = all(one("bluetooth_connected"), one("sms_received"))
        val btSpecs = listOf(
            spec("state", alwaysPresent = true),
            spec("address", alwaysPresent = true),
        )
        val smsSpecs = listOf(spec("state", alwaysPresent = true))
        val declarations = mapOf("bluetooth_connected" to btSpecs, "sms_received" to smsSpecs)

        val available = availableVariables(tree) { declarations[it].orEmpty() }
        val onlyOne = available.single {
            it.scope == VariableScope.TRIGGER && it.spec.key == "address"
        }

        assertFalse(onlyOne.spec.alwaysPresent)
    }

    /**
     * Two leaves of one type share one namespace, per `docs/variables.md`
     * section 3: whichever of them fires fills `{{trigger.key}}`. There is
     * nothing here for the type-qualified form to say that the shared form does
     * not, because a person still cannot tell the two leaves apart by type, so
     * it is not offered at all.
     */
    @Test
    fun `two leaves of the same type offer it once under trigger scope, not type-qualified`() {
        val tree = all(one("battery_level"), one("battery_level"))

        val available = availableVariables(tree) { type ->
            if (type == "battery_level") listOf(spec("level")) else emptyList()
        }

        assertEquals(
            1,
            available.count { it.scope == VariableScope.TRIGGER && it.spec.key == "level" },
        )
        assertTrue(available.none { it.scope == "battery_level" })
    }

    /**
     * The type-qualified form always forces `alwaysPresent = false`, per
     * [availableVariables]'s KDoc. A duplicated type is only ever offered
     * under the shared `trigger` scope, so that forcing never applies to it.
     * What decides the shared entry's mark is only ever the declaration itself.
     */
    @Test
    fun `a key only sometimes present stays that way when its type is duplicated`() {
        val tree = all(one("bluetooth_connected"), one("bluetooth_connected"))

        val available = availableVariables(tree) { type ->
            if (type == "bluetooth_connected") listOf(spec("name", alwaysPresent = false))
            else emptyList()
        }
        val shared = available.single {
            it.scope == VariableScope.TRIGGER && it.spec.key == "name"
        }

        assertFalse(shared.spec.alwaysPresent)
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

        assertEquals(VariableScope.ACTION, available.single().scope)
        assertEquals("value", available.single().spec.key)
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

        assertFalse(available.single().spec.alwaysPresent)
    }

    @Test
    fun `one producing type above is offered under action scope only`() {
        val types = listOf("set_variable", "post_notification", "toast")

        val available = availableActionOutputs(types, index = 2, ::actionDeclarations)

        assertTrue(available.any { it.scope == VariableScope.ACTION && it.spec.key == "value" })
        assertTrue(available.none { it.scope == "set_variable" })
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
     * Two earlier actions of one type collapse to one type, so the qualified
     * form is not offered: the engine keeps the most recent value per key, so
     * neither form can say which of the two is meant. The same call
     * `availableVariables` makes for two trigger leaves of one type.
     */
    @Test
    fun `two actions of the same type above are offered under action scope only`() {
        val types = listOf("set_variable", "set_variable", "post_notification")

        val available = availableActionOutputs(types, index = 2, ::actionDeclarations)

        assertEquals(1, available.size)
        assertEquals(VariableScope.ACTION, available.single().scope)
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
