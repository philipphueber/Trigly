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

    /** Being lenient about app scope must not make anything else lenient. */
    @Test
    fun `an unknown name in another scope is still a problem`() {
        val available = availableVariables(null) { emptyList() }

        assertEquals(1, variableProblems("{{trigger.nonsense}}", available).size)
        assertEquals(1, variableProblems("{{event.nonsense}}", available).size)
        assertEquals(1, variableProblems("{{app.ok}} {{rule.nonsense}}", available).size)
    }
}
