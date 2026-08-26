package app.phueber.trigly.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine's handling of a trigger tree: every leaf's events merged into the
 * single flow [TriggerEngine.startRule] collects, and [TriggerNode.holds]
 * evaluated between a leaf firing and the actions running. `GateTest` covers
 * the pure evaluation of a [TriggerNode]; this covers what the engine
 * actually does with one end to end, including the two failure directions
 * that matter most for an app that fires actions unattended — a leaf that
 * cannot answer must not be read as "yes", and one bad leaf must not take the
 * whole rule down.
 *
 * Harness pattern borrowed from `TriggerEngineTest`: fake triggers and actions
 * behind a real [Registry], so the engine is exercised through its real
 * dependency-resolution path rather than mocked out from under it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GateEngineTest {

    @Test
    fun `a rule with two leaves under ANY fires when either emits`() =
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
                name = "two leaves",
                trigger = TriggerNode.Group(
                    TriggerNode.Op.ANY,
                    listOf(TriggerNode.One(ComponentSpec("edge-a")), TriggerNode.One(ComponentSpec("edge-b"))),
                ),
                actions = listOf(ComponentSpec(ACTION_TYPE)),
            )

            engine.startRule(rule)

            // Either leaf fires the same rule — which one fired first is not
            // the point, only that both do.
            assertEquals(setOf(1L, 2L), action.seen.map { it.firedAtMillis }.toSet())
        }

    @Test
    fun `an event whose other leaf holds runs the actions`() = runTest(UnconfinedTestDispatcher()) {
        val h = conditionHarness(this, conditionHolds = { true })

        h.engine.startRule(h.rule)

        assertEquals(listOf(1L), h.action.seen.map { it.firedAtMillis })
    }

    @Test
    fun `an event whose other leaf does not hold runs nothing`() =
        runTest(UnconfinedTestDispatcher()) {
            val h = conditionHarness(this, conditionHolds = { false })

            h.engine.startRule(h.rule)

            assertTrue(h.action.seen.isEmpty())
        }

    @Test
    fun `a leaf returning null does not hold, so the actions do not run`() =
        runTest(UnconfinedTestDispatcher()) {
            // Null means "cannot be asked" — neither yes nor no. Reading it as
            // holding would fire the rule on a guess; see TriggerNode.holds.
            val h = conditionHarness(this, conditionHolds = { null })

            h.engine.startRule(h.rule)

            assertTrue(h.action.seen.isEmpty())
        }

    @Test
    fun `a leaf that throws when asked is asked again, and does not take the rule down`() =
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

            // Both events fire, and the first one is the point. Its check threw,
            // the engine asked again, and the second ask answered yes, so the
            // rule ran late rather than losing the event. This test asserted
            // `[2]` before the retry existed, when one throw dropped an event
            // for good. Neither event firing would still mean the throw took the
            // rule's job down with it, which is the other half it guards.
            advanceTimeBy(UNREADABLE_RETRY_DELAY_MILLIS + 1)

            assertEquals(listOf(1L, 2L), h.action.seen.map { it.firedAtMillis })
            assertTrue(h.engine.runningRuleIds.contains(h.rule.id))
        }

    @Test
    fun `a state read that throws CancellationException stops the rule instead of being read as unknown`() =
        runTest(UnconfinedTestDispatcher()) {
            // The direction that must NOT be caught by the same catch-all as an
            // ordinary throw: if it were, the second event below would still
            // be evaluated. It is not, because the collecting coroutine itself
            // is cancelled the moment the first check raises cancellation —
            // exactly what must happen when anything in a rule's job does.
            var calls = 0
            val h = conditionHarness(
                this,
                emissions = listOf(event(1), event(2)),
                conditionHolds = {
                    calls++
                    throw CancellationException("condition cancelled")
                },
            )

            h.engine.startRule(h.rule)

            assertEquals(1, calls)
            assertTrue(h.action.seen.isEmpty())
        }

    @Test
    fun `in an ALL group, the leaf that fired is not asked for a state it does not have`() =
        runTest(UnconfinedTestDispatcher()) {
            // The single most important property in this file: a momentary
            // component paired with a condition, the whole reason a group can
            // hold more than one leaf. If the engine asked "momentary" for its
            // own state when it was the leaf that just fired, this rule could
            // never run — that is exactly the shape of a rule that silently
            // never fires, for every rule combining a one-shot trigger with a
            // condition.
            val action = GateRecordingAction()
            val registry = Registry(
                triggerFactories = listOf(
                    edgeFactory("momentary", event(1)) {
                        error("asked for a state this component does not have")
                    },
                    conditionFactory(CONDITION_TYPE) { true },
                ),
                actionFactories = listOf(GateActionFactory(ACTION_TYPE, action)),
            )
            val engine = TriggerEngine(registry, this)
            val rule = Rule(
                id = "r",
                name = "momentary and condition",
                trigger = TriggerNode.Group(
                    TriggerNode.Op.ALL,
                    listOf(
                        TriggerNode.One(ComponentSpec("momentary")),
                        TriggerNode.One(ComponentSpec(CONDITION_TYPE)),
                    ),
                ),
                actions = listOf(ComponentSpec(ACTION_TYPE)),
            )

            engine.startRule(rule)

            assertEquals(listOf(1L), action.seen.map { it.firedAtMillis })
        }

    @Test
    fun `three levels of ALL and ANY fire through the nested ALL branch`() =
        runTest(UnconfinedTestDispatcher()) {
            // a AND (b OR (c AND d)) — satisfied here only through c and d.
            val state = ConditionState(b = false, c = true, d = true)
            val action = GateRecordingAction()
            val engine = TriggerEngine(nestedRegistry(action, state), this)

            engine.startRule(nestedRule())

            assertEquals(listOf(1L), action.seen.map { it.firedAtMillis })
        }

    @Test
    fun `three levels of ALL and ANY do not fire when both branches of the ANY fail`() =
        runTest(UnconfinedTestDispatcher()) {
            val state = ConditionState(b = false, c = true, d = false)
            val action = GateRecordingAction()
            val engine = TriggerEngine(nestedRegistry(action, state), this)

            engine.startRule(nestedRule())

            assertTrue(action.seen.isEmpty())
        }

    @Test
    fun `three levels of ALL and ANY fire through the ANY branch alone`() =
        runTest(UnconfinedTestDispatcher()) {
            // b alone satisfies the ANY, regardless of what c and d say.
            val state = ConditionState(b = true, c = false, d = false)
            val action = GateRecordingAction()
            val engine = TriggerEngine(nestedRegistry(action, state), this)

            engine.startRule(nestedRule())

            assertEquals(listOf(1L), action.seen.map { it.firedAtMillis })
        }

    @Test
    fun `two leaves firing in quick succession run their actions one at a time, not concurrently`() =
        runTest(UnconfinedTestDispatcher()) {
            // The reason startRule merges every leaf's events into a single
            // collected flow instead of one collector per leaf: independent
            // collectors would run these concurrently when their leaves fire
            // together. The delay widens the window an accidental regression
            // to concurrent execution would have to fall into, so this is
            // deterministic rather than a race that happens to pass.
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
                name = "two leaves, one job",
                trigger = TriggerNode.Group(
                    TriggerNode.Op.ANY,
                    listOf(TriggerNode.One(ComponentSpec("edge-a")), TriggerNode.One(ComponentSpec("edge-b"))),
                ),
                actions = listOf(ComponentSpec(ACTION_TYPE)),
            )

            engine.startRule(rule)
            advanceUntilIdle()

            assertFalse("actions from two leaves overlapped", action.overlapped)
            assertEquals(setOf(1L, 2L), action.seen.map { it.firedAtMillis }.toSet())
        }

    @Test
    fun `a rule naming an unknown leaf fails at start, wherever in the tree it sits`() =
        runTest(UnconfinedTestDispatcher()) {
            val registry = Registry(
                triggerFactories = listOf(edgeFactory(TRIGGER_TYPE, event(1))),
                actionFactories = listOf(GateActionFactory(ACTION_TYPE, GateRecordingAction())),
            )
            val engine = TriggerEngine(registry, this)
            val rule = Rule(
                id = "r",
                name = "bad leaf",
                trigger = TriggerNode.Group(
                    TriggerNode.Op.ALL,
                    listOf(TriggerNode.One(ComponentSpec(TRIGGER_TYPE)), TriggerNode.One(ComponentSpec("nope"))),
                ),
                actions = listOf(ComponentSpec(ACTION_TYPE)),
            )

            // Every leaf is resolved up front in startRule, exactly like a
            // single trigger and the actions — a rule naming an unknown leaf
            // must fail here, not look healthy until the moment it first
            // tries to evaluate that branch.
            assertThrows(UnknownComponentException::class.java) { engine.startRule(rule) }
        }
}

private const val TRIGGER_TYPE = "edge"
private const val CONDITION_TYPE = "condition"
private const val ACTION_TYPE = "action"

private fun event(millis: Long) = TriggerEvent(TRIGGER_TYPE, millis)

/** A component with edges of its own; [holds] backs [Trigger.currentlyHolds] if it is ever asked. */
private fun edgeFactory(
    triggerType: String,
    vararg emissions: TriggerEvent,
    holds: suspend () -> Boolean? = { null },
): TriggerFactory = edgeFactory(triggerType, emissions.toList(), holds)

private fun edgeFactory(
    triggerType: String,
    emissions: List<TriggerEvent>,
    holds: suspend () -> Boolean? = { null },
): TriggerFactory = object : TriggerFactory {
    override val type: String = triggerType
    override fun create(config: Map<String, String>): Trigger = object : Trigger {
        override fun events(): Flow<TriggerEvent> = emissions.asFlow()
        override suspend fun currentlyHolds(): Boolean? = holds()
    }
}

/** A condition-only component: no edges of its own, just a configurable state. */
private fun conditionFactory(type: String, holds: suspend () -> Boolean?): TriggerFactory = object : TriggerFactory {
    override val type: String = type
    override fun create(config: Map<String, String>): Trigger = object : Trigger {
        override fun events(): Flow<TriggerEvent> = emptyFlow()
        override suspend fun currentlyHolds(): Boolean? = holds()
    }
}

private class ConditionHarness(val engine: TriggerEngine, val rule: Rule, val action: GateRecordingAction)

/** One edge, one condition leaf under ALL, one recording action — the shape most of these tests need. */
private fun conditionHarness(
    scope: CoroutineScope,
    emissions: List<TriggerEvent> = listOf(event(1)),
    conditionHolds: suspend () -> Boolean?,
): ConditionHarness {
    val action = GateRecordingAction()
    val registry = Registry(
        triggerFactories = listOf(edgeFactory(TRIGGER_TYPE, emissions), conditionFactory(CONDITION_TYPE, conditionHolds)),
        actionFactories = listOf(GateActionFactory(ACTION_TYPE, action)),
    )
    val engine = TriggerEngine(registry, scope)
    val rule = Rule(
        id = "r",
        name = "conditioned",
        trigger = TriggerNode.Group(
            TriggerNode.Op.ALL,
            listOf(TriggerNode.One(ComponentSpec(TRIGGER_TYPE)), TriggerNode.One(ComponentSpec(CONDITION_TYPE))),
        ),
        actions = listOf(ComponentSpec(ACTION_TYPE)),
    )
    return ConditionHarness(engine, rule, action)
}

/** Mutable backing for the three condition-only leaves in [nestedRule]. */
private class ConditionState(var b: Boolean?, var c: Boolean?, var d: Boolean?)

/** `a AND (b OR (c AND d))` — three levels deep, ALL wrapping an ANY wrapping an ALL. */
private fun nestedRule() = Rule(
    id = "r",
    name = "nested",
    trigger = TriggerNode.Group(
        TriggerNode.Op.ALL,
        listOf(
            TriggerNode.One(ComponentSpec("edge")),
            TriggerNode.Group(
                TriggerNode.Op.ANY,
                listOf(
                    TriggerNode.One(ComponentSpec("cond-b")),
                    TriggerNode.Group(
                        TriggerNode.Op.ALL,
                        listOf(TriggerNode.One(ComponentSpec("cond-c")), TriggerNode.One(ComponentSpec("cond-d"))),
                    ),
                ),
            ),
        ),
    ),
    actions = listOf(ComponentSpec(ACTION_TYPE)),
)

private fun nestedRegistry(action: Action, state: ConditionState) = Registry(
    triggerFactories = listOf(
        edgeFactory("edge", event(1)),
        conditionFactory("cond-b") { state.b },
        conditionFactory("cond-c") { state.c },
        conditionFactory("cond-d") { state.d },
    ),
    actionFactories = listOf(GateActionFactory(ACTION_TYPE, action)),
)

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
