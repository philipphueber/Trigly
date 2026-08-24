package app.phueber.trigly.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine's handling of a gate: several trigger edges merged into the
 * first-level OR the design calls for, and the condition tree evaluated
 * between an edge firing and the actions running. `GateTest` covers the pure
 * evaluation of a [ConditionNode]; this covers what the engine actually does
 * with one, including the two failure directions that matter most for an app
 * that fires actions unattended — a condition that cannot answer must not be
 * read as "yes", and one bad edge or check must not take the whole rule down.
 *
 * Harness pattern borrowed from `TriggerEngineTest`: fake triggers and actions
 * behind a real [Registry], so the engine is exercised through its real
 * dependency-resolution path rather than mocked out from under it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GateEngineTest {

    @Test
    fun `a rule with two edges fires its actions when either edge emits`() =
        runTest(UnconfinedTestDispatcher()) {
            val action = GateRecordingAction()
            val registry = Registry(
                triggerFactories = listOf(
                    edgeFactory("edge-a", event(1)),
                    edgeFactory("edge-b", event(2)),
                ),
                actionFactories = listOf(GateActionFactory(ACTION_TYPE, action)),
            )
            val engine = TriggerEngine(registry, this)
            val rule = Rule(
                id = "r",
                name = "two edges",
                gate = Gate(triggers = listOf(ComponentSpec("edge-a"), ComponentSpec("edge-b"))),
                actions = listOf(ComponentSpec(ACTION_TYPE)),
            )

            engine.startRule(rule)

            // Either edge fires the same rule — which one fired first is not the
            // point, only that both do.
            assertEquals(setOf(1L, 2L), action.seen.map { it.firedAtMillis }.toSet())
        }

    @Test
    fun `an event whose condition holds runs the actions`() = runTest(UnconfinedTestDispatcher()) {
        val h = conditionHarness(this, conditionHolds = { true })

        h.engine.startRule(h.rule)

        assertEquals(listOf(1L), h.action.seen.map { it.firedAtMillis })
    }

    @Test
    fun `an event whose condition does not hold runs nothing`() =
        runTest(UnconfinedTestDispatcher()) {
            val h = conditionHarness(this, conditionHolds = { false })

            h.engine.startRule(h.rule)

            assertTrue(h.action.seen.isEmpty())
        }

    @Test
    fun `a condition trigger returning null does not hold, so the actions do not run`() =
        runTest(UnconfinedTestDispatcher()) {
            // Null means "cannot be asked" — neither yes nor no. Reading it as
            // holding would fire the rule on a guess; see ConditionNode.holds.
            val h = conditionHarness(this, conditionHolds = { null })

            h.engine.startRule(h.rule)

            assertTrue(h.action.seen.isEmpty())
        }

    @Test
    fun `a condition trigger that throws does not hold, and does not take the rule down`() =
        runTest(UnconfinedTestDispatcher()) {
            var calls = 0
            val h = conditionHarness(
                this,
                emissions = listOf(event(1), event(2)),
                conditionHolds = {
                    calls++
                    if (calls == 1) error("boom") else true
                },
            )

            h.engine.startRule(h.rule)

            // The first event's check threw and so did not fire; the second
            // event's check held and did. Both firing would mean the throw was
            // never caught; neither firing would mean it took the rule's job
            // down with it.
            assertEquals(listOf(2L), h.action.seen.map { it.firedAtMillis })
            assertTrue(h.engine.runningRuleIds.contains(h.rule.id))
        }

    @Test
    fun `two edges firing in quick succession run their actions one at a time, not concurrently`() =
        runTest(UnconfinedTestDispatcher()) {
            // The reason startRule merges the edges into a single collected flow
            // instead of one collector per edge: three independent collectors
            // would run these concurrently when their edges fire together. The
            // delay widens the window an accidental regression to concurrent
            // execution would have to fall into, so this is deterministic rather
            // than a race that happens to pass.
            val action = OverlapDetectingAction()
            val registry = Registry(
                triggerFactories = listOf(
                    edgeFactory("edge-a", event(1)),
                    edgeFactory("edge-b", event(2)),
                ),
                actionFactories = listOf(GateActionFactory(ACTION_TYPE, action)),
            )
            val engine = TriggerEngine(registry, this)
            val rule = Rule(
                id = "r",
                name = "two edges, one job",
                gate = Gate(triggers = listOf(ComponentSpec("edge-a"), ComponentSpec("edge-b"))),
                actions = listOf(ComponentSpec(ACTION_TYPE)),
            )

            engine.startRule(rule)
            advanceUntilIdle()

            assertFalse("actions from two edges overlapped", action.overlapped)
            assertEquals(setOf(1L, 2L), action.seen.map { it.firedAtMillis }.toSet())
        }

    @Test
    fun `a rule naming an unknown condition type fails at start, like an unknown trigger`() =
        runTest(UnconfinedTestDispatcher()) {
            val registry = Registry(
                triggerFactories = listOf(edgeFactory(TRIGGER_TYPE, event(1))),
                actionFactories = listOf(GateActionFactory(ACTION_TYPE, GateRecordingAction())),
            )
            val engine = TriggerEngine(registry, this)
            val rule = Rule(
                id = "r",
                name = "bad condition",
                gate = Gate(
                    trigger = ComponentSpec(TRIGGER_TYPE),
                    conditions = ConditionNode.Check(ComponentSpec("nope")),
                ),
                actions = listOf(ComponentSpec(ACTION_TYPE)),
            )

            // Checks are resolved up front in startRule, exactly like triggers and
            // actions — a rule naming an unknown condition must fail here, not
            // look healthy until the moment it first tries to evaluate.
            assertThrows(UnknownComponentException::class.java) { engine.startRule(rule) }
        }
}

private const val TRIGGER_TYPE = "edge"
private const val CONDITION_TYPE = "condition"
private const val ACTION_TYPE = "action"

private fun event(millis: Long) = TriggerEvent(TRIGGER_TYPE, millis)

private fun edgeFactory(triggerType: String, vararg emissions: TriggerEvent): TriggerFactory =
    edgeFactory(triggerType, emissions.toList())

private fun edgeFactory(triggerType: String, emissions: List<TriggerEvent>): TriggerFactory =
    object : TriggerFactory {
        override val type: String = triggerType
        override fun create(config: Map<String, String>): Trigger = object : Trigger {
            override fun events(): Flow<TriggerEvent> = emissions.asFlow()
        }
    }

/** A condition-only component: no edges of its own, just a configurable state. */
private fun conditionFactory(holds: suspend () -> Boolean?): TriggerFactory = object : TriggerFactory {
    override val type: String = CONDITION_TYPE
    override fun create(config: Map<String, String>): Trigger = object : Trigger {
        override fun events(): Flow<TriggerEvent> = emptyFlow()
        override suspend fun currentlyHolds(): Boolean? = holds()
    }
}

private class ConditionHarness(val engine: TriggerEngine, val rule: Rule, val action: GateRecordingAction)

/** One edge, one condition check, one recording action — the shape most of these tests need. */
private fun conditionHarness(
    scope: CoroutineScope,
    emissions: List<TriggerEvent> = listOf(event(1)),
    conditionHolds: suspend () -> Boolean?,
): ConditionHarness {
    val action = GateRecordingAction()
    val registry = Registry(
        triggerFactories = listOf(edgeFactory(TRIGGER_TYPE, emissions), conditionFactory(conditionHolds)),
        actionFactories = listOf(GateActionFactory(ACTION_TYPE, action)),
    )
    val engine = TriggerEngine(registry, scope)
    val rule = Rule(
        id = "r",
        name = "conditioned",
        gate = Gate(
            trigger = ComponentSpec(TRIGGER_TYPE),
            conditions = ConditionNode.Check(ComponentSpec(CONDITION_TYPE)),
        ),
        actions = listOf(ComponentSpec(ACTION_TYPE)),
    )
    return ConditionHarness(engine, rule, action)
}

private class GateActionFactory(
    override val type: String,
    private val action: Action,
) : ActionFactory {
    override fun create(config: Map<String, String>): Action = action
}

private class GateRecordingAction : Action {
    val seen = mutableListOf<TriggerEvent>()

    override suspend fun execute(event: TriggerEvent): ActionResult {
        seen += event
        return ActionResult.Success
    }
}

/**
 * Records whether two calls to [execute] were ever in flight at once — the
 * property "one job per rule" exists to guarantee. The [delay] widens the
 * window so a regression to concurrent execution could not slip past by luck.
 */
private class OverlapDetectingAction : Action {
    private var active = 0
    var overlapped = false
        private set
    val seen = mutableListOf<TriggerEvent>()

    override suspend fun execute(event: TriggerEvent): ActionResult {
        active++
        if (active > 1) overlapped = true
        delay(50)
        seen += event
        active--
        return ActionResult.Success
    }
}
