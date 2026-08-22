package app.phueber.trigly.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TriggerEngineTest {

    @Test
    fun `runs every action for every event`() = runTest(UnconfinedTestDispatcher()) {
        val action = RecordingAction()
        val h = harness(this, emissions = listOf(event(1), event(2)), actions = listOf(action))

        h.engine.start(listOf(h.rule))

        assertEquals(listOf(1L, 2L), action.seen.map { it.firedAtMillis })
        assertEquals(listOf<ActionResult>(ActionResult.Success, ActionResult.Success), h.outcomes)
    }

    @Test
    fun `a throwing action does not stop the rule or the actions behind it`() =
        runTest(UnconfinedTestDispatcher()) {
            val survivor = RecordingAction()
            val h = harness(
                this,
                emissions = listOf(event(1), event(2)),
                actions = listOf(ThrowingAction(), survivor),
            )

            h.engine.start(listOf(h.rule))

            // The action declared after the failing one still ran, for both events.
            assertEquals(listOf(1L, 2L), survivor.seen.map { it.firedAtMillis })
            assertEquals(2, h.outcomes.filterIsInstance<ActionResult.Failure>().size)
            assertEquals(2, h.outcomes.filterIsInstance<ActionResult.Success>().size)
        }

    @Test
    fun `disabled rules are never started`() = runTest(UnconfinedTestDispatcher()) {
        val action = RecordingAction()
        val h = harness(this, listOf(event(1)), listOf(action))

        h.engine.start(listOf(h.rule.copy(enabled = false)))

        assertTrue(action.seen.isEmpty())
        assertTrue(h.engine.runningRuleIds.isEmpty())
    }

    @Test
    fun `a rule naming an unknown trigger fails at start, not silently`() =
        runTest(UnconfinedTestDispatcher()) {
            val h = harness(this, listOf(event(1)), listOf(RecordingAction()))

            assertThrows(UnknownComponentException::class.java) {
                h.engine.startRule(h.rule.copy(trigger = ComponentSpec("nope")))
            }
        }

    @Test
    fun `two factories claiming one type is rejected at assembly`() {
        assertThrows(IllegalArgumentException::class.java) {
            Registry(
                triggerFactories = listOf(
                    FakeTriggerFactory("dupe", emptyList()),
                    FakeTriggerFactory("dupe", emptyList()),
                ),
                actionFactories = emptyList(),
            )
        }
    }
}

private const val TRIGGER_TYPE = "fake"

private fun event(millis: Long) = TriggerEvent(TRIGGER_TYPE, millis)

private class Harness(
    val engine: TriggerEngine,
    val rule: Rule,
    val outcomes: List<ActionResult>,
)

/**
 * Registers each action under its own type so the rule holds one spec per
 * action — otherwise the engine would see a single action and the isolation
 * these tests check would not be exercised.
 */
private fun harness(
    scope: CoroutineScope,
    emissions: List<TriggerEvent>,
    actions: List<Action>,
): Harness {
    val specs = actions.indices.map { ComponentSpec("action-$it") }
    val registry = Registry(
        triggerFactories = listOf(FakeTriggerFactory(TRIGGER_TYPE, emissions)),
        actionFactories = actions.mapIndexed { i, a -> SingleActionFactory("action-$i", a) },
    )
    val outcomes = mutableListOf<ActionResult>()
    val engine = TriggerEngine(registry, scope) { _, _, result -> outcomes += result }
    val rule = Rule(
        id = "rule-1",
        name = "test rule",
        trigger = ComponentSpec(TRIGGER_TYPE),
        actions = specs,
    )
    return Harness(engine, rule, outcomes)
}

private class FakeTriggerFactory(
    override val type: String,
    private val emissions: List<TriggerEvent>,
) : TriggerFactory {
    override fun create(config: Map<String, String>): Trigger = object : Trigger {
        override fun events(): Flow<TriggerEvent> = emissions.asFlow()
    }
}

private class SingleActionFactory(
    override val type: String,
    private val action: Action,
) : ActionFactory {
    override fun create(config: Map<String, String>): Action = action
}

private class RecordingAction : Action {
    val seen = mutableListOf<TriggerEvent>()

    override suspend fun execute(event: TriggerEvent): ActionResult {
        seen += event
        return ActionResult.Success
    }
}

private class ThrowingAction : Action {
    override suspend fun execute(event: TriggerEvent): ActionResult =
        error("action blew up")
}
