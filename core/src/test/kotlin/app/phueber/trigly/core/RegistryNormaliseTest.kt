package app.phueber.trigly.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Filling in config an older build left out, across a whole rule.
 *
 * The mechanism, not any component's use of it: `:core` must not know which key
 * `bluetooth_connected` grew. What it owes is that every component in a rule is
 * offered the chance, trigger tree and actions alike, and that a component which
 * has nothing to add is left exactly as it was.
 */
class RegistryNormaliseTest {

    private val registry = Registry(
        triggerFactories = listOf(
            FillingTriggerFactory("grew-a-key"),
            PlainTriggerFactory("unchanged"),
        ),
        actionFactories = listOf(FillingActionFactory("action-grew-a-key")),
    )

    @Test
    fun `a component with nothing to add is untouched`() {
        val spec = ComponentSpec("unchanged", mapOf("a" to "1"))

        assertEquals(spec, registry.normalise(spec))
    }

    @Test
    fun `a component fills in what it left out`() {
        val filled = registry.normalise(ComponentSpec("grew-a-key", mapOf("a" to "1")))

        assertEquals(mapOf("a" to "1", "filled" to "yes"), filled.config)
    }

    /** A stored value is never overwritten: normalising is not correcting. */
    @Test
    fun `a value already stored survives`() {
        val filled = registry.normalise(ComponentSpec("grew-a-key", mapOf("filled" to "no")))

        assertEquals(mapOf("filled" to "no"), filled.config)
    }

    /**
     * An unknown type is returned as it stands. The editor asks about types
     * before validating them, and a rule naming a component this build lacks is
     * already drawn as unavailable; throwing here would take the screen down
     * with it.
     */
    @Test
    fun `an unknown type is returned unchanged`() {
        val spec = ComponentSpec("no-such-type", mapOf("a" to "1"))

        assertEquals(spec, registry.normalise(spec))
    }

    /** Every leaf of the tree, however deep, and every action beside it. */
    @Test
    fun `a whole rule is normalised through its tree and its actions`() {
        val rule = Rule(
            id = "r",
            name = "r",
            trigger = TriggerNode.Group(
                TriggerNode.Op.ALL,
                listOf(
                    TriggerNode.One(ComponentSpec("grew-a-key")),
                    TriggerNode.Group(
                        TriggerNode.Op.ANY,
                        listOf(
                            TriggerNode.One(ComponentSpec("grew-a-key")),
                            TriggerNode.One(ComponentSpec("unchanged")),
                        ),
                    ),
                ),
            ),
            actions = listOf(ComponentSpec("action-grew-a-key")),
        )

        val normalised = registry.normalise(rule)

        val filled = normalised.trigger.leaves().count { it.config["filled"] == "yes" }
        assertEquals(2, filled)
        assertEquals(mapOf("filled" to "yes"), normalised.actions.single().config)
        // Structure preserved, which is the other half of the contract.
        assertEquals(4, normalised.trigger.leaves().size + 1)
    }
}

private class FillingTriggerFactory(override val type: String) : TriggerFactory {
    override fun normalise(config: Map<String, String>): Map<String, String> =
        if ("filled" in config) config else config + ("filled" to "yes")

    override fun create(config: Map<String, String>): Trigger =
        error("not needed: normalising never builds the component")
}

private class PlainTriggerFactory(override val type: String) : TriggerFactory {
    override fun create(config: Map<String, String>): Trigger =
        error("not needed: normalising never builds the component")
}

private class FillingActionFactory(override val type: String) : ActionFactory {
    override fun normalise(config: Map<String, String>): Map<String, String> =
        if ("filled" in config) config else config + ("filled" to "yes")

    override fun create(config: Map<String, String>): Action =
        error("not needed: normalising never builds the component")
}
