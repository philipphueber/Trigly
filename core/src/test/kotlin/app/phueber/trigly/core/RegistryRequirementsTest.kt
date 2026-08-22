package app.phueber.trigly.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistryRequirementsTest {

    private val permission = ComponentRequirement.RuntimePermission("android.permission.EXAMPLE")
    private val feature = ComponentRequirement.SystemFeature("android.hardware.example")

    private val registry = Registry(
        triggerFactories = listOf(
            StubTriggerFactory("needs-permission", listOf(permission)),
            StubTriggerFactory("needs-nothing", emptyList()),
        ),
        actionFactories = listOf(
            StubActionFactory("needs-feature", listOf(feature)),
        ),
    )

    @Test
    fun `requirements are answerable without instantiating the component`() {
        assertEquals(listOf(permission), registry.triggerRequirements("needs-permission"))
        assertEquals(listOf(feature), registry.actionRequirements("needs-feature"))
    }

    @Test
    fun `a component with no requirements reports an empty list`() {
        assertTrue(registry.triggerRequirements("needs-nothing").isEmpty())
    }

    @Test
    fun `an unknown type reports empty rather than throwing`() {
        // The rule editor asks about types before validating them; throwing here
        // would make every unknown type a crash instead of a rejected rule.
        assertTrue(registry.triggerRequirements("no-such-type").isEmpty())
        assertTrue(registry.actionRequirements("no-such-type").isEmpty())
    }

    @Test
    fun `a rule reports the union of its trigger and action requirements`() {
        val rule = Rule(
            id = "r",
            name = "r",
            trigger = ComponentSpec("needs-permission"),
            actions = listOf(ComponentSpec("needs-feature")),
        )

        assertEquals(listOf(permission, feature), registry.requirementsOf(rule))
    }

    @Test
    fun `a requirement shared by two components is reported once`() {
        val shared = Registry(
            triggerFactories = listOf(StubTriggerFactory("t", listOf(permission))),
            actionFactories = listOf(
                StubActionFactory("a1", listOf(permission)),
                StubActionFactory("a2", listOf(permission)),
            ),
        )
        val rule = Rule(
            id = "r",
            name = "r",
            trigger = ComponentSpec("t"),
            actions = listOf(ComponentSpec("a1"), ComponentSpec("a2")),
        )

        assertEquals(listOf(permission), shared.requirementsOf(rule))
    }
}

private class StubTriggerFactory(
    override val type: String,
    override val requirements: List<ComponentRequirement>,
) : TriggerFactory {
    override fun create(config: Map<String, String>): Trigger =
        error("not needed: requirements are read without creating the trigger")
}

private class StubActionFactory(
    override val type: String,
    override val requirements: List<ComponentRequirement>,
) : ActionFactory {
    override fun create(config: Map<String, String>): Action =
        error("not needed: requirements are read without creating the action")
}
