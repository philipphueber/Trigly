package app.phueber.trigly.core.storage

import app.phueber.trigly.core.ComponentSpec
import app.phueber.trigly.core.NO_TRIGGER
import app.phueber.trigly.core.Rule
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [Rule] through [Rule.toEntity]/[Rule.toComponentEntities] and back through
 * [RuleWithComponents.toRuleOrNull]: the exact codec the repository runs a
 * rule through on every save and every read. No Room database needed: both
 * halves are plain functions over the entity data classes, and the promise
 * under test is that the mapping agrees with itself, not that Room's own
 * query machinery works.
 *
 * A rule saved before it is finished has to survive this the same as any
 * other, since it is the repository, not a special case, that a half-built
 * rule goes through the moment `RuleEditorViewModel.save` lets it save.
 */
class RuleEntityRoundTripTest {

    private fun Rule.roundTrip(): Rule? {
        val entity = toEntity(position = 0)
        val components = toComponentEntities()
        return RuleWithComponents(entity, components).toRuleOrNull()
    }

    @Test
    fun `a rule with no trigger chosen survives the entity round trip`() {
        val unfinished = Rule(
            id = "rule-1",
            name = "Half built",
            trigger = NO_TRIGGER,
            actions = emptyList(),
            enabled = false,
        )

        assertEquals(unfinished, unfinished.roundTrip())
    }

    @Test
    fun `a rule with no actions survives the entity round trip`() {
        val actionless = Rule(
            id = "rule-2",
            name = "Watches only",
            trigger = ComponentSpec("screen_state", mapOf("state" to "on")),
            actions = emptyList(),
            enabled = false,
        )

        assertEquals(actionless, actionless.roundTrip())
    }

    @Test
    fun `a fully built rule still survives the entity round trip`() {
        val whole = Rule(
            id = "rule-3",
            name = "Whole",
            trigger = ComponentSpec("screen_state", mapOf("state" to "on")),
            actions = listOf(ComponentSpec("toast", mapOf("text" to "hi"))),
            enabled = true,
        )

        assertEquals(whole, whole.roundTrip())
    }
}
