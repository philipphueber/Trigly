package app.phueber.trigly.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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

        h.engine.sync(listOf(h.rule))

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

            h.engine.sync(listOf(h.rule))

            // The action declared after the failing one still ran, for both events.
            assertEquals(listOf(1L, 2L), survivor.seen.map { it.firedAtMillis })
            assertEquals(2, h.outcomes.filterIsInstance<ActionResult.Failure>().size)
            assertEquals(2, h.outcomes.filterIsInstance<ActionResult.Success>().size)
        }

    @Test
    fun `disabled rules are never started`() = runTest(UnconfinedTestDispatcher()) {
        val action = RecordingAction()
        val h = harness(this, listOf(event(1)), listOf(action))

        h.engine.sync(listOf(h.rule.copy(enabled = false)))

        assertTrue(action.seen.isEmpty())
        assertTrue(h.engine.runningRuleIds.isEmpty())
    }

    @Test
    fun `a rule naming an unknown trigger fails at start, not silently`() =
        runTest(UnconfinedTestDispatcher()) {
            val h = harness(this, listOf(event(1)), listOf(RecordingAction()))

            assertThrows(UnknownComponentException::class.java) {
                h.engine.startRule(h.rule.withTrigger(ComponentSpec("nope")))
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

    // --- sync: the entry point the hosting service calls on every rule change ---

    @Test
    fun `sync stops a rule that was disabled or deleted`() =
        runTest(UnconfinedTestDispatcher()) {
            val engine = TriggerEngine(idleRegistry(CountingTriggerFactory()), this)

            engine.sync(listOf(idleRule("a"), idleRule("b")))
            assertEquals(setOf("a", "b"), engine.runningRuleIds)

            engine.sync(listOf(idleRule("a"), idleRule("b", enabled = false)))
            assertEquals(setOf("a"), engine.runningRuleIds)

            engine.sync(emptyList())
            assertTrue(engine.runningRuleIds.isEmpty())

            engine.stop()
        }

    /**
     * The property that makes `sync` safe to call on every keystroke-sized change
     * to the rule store: adding one rule must not tear down and rebuild the
     * others. Rebuilding re-registers a broadcast receiver, and a sticky
     * broadcast replays on registration — so a restart is a phantom firing.
     */
    @Test
    fun `sync leaves an unchanged rule running rather than rebuilding it`() =
        runTest(UnconfinedTestDispatcher()) {
            val factory = CountingTriggerFactory()
            val engine = TriggerEngine(idleRegistry(factory), this)

            engine.sync(listOf(idleRule("a")))
            engine.sync(listOf(idleRule("a"), idleRule("b")))

            assertEquals(setOf("a", "b"), engine.runningRuleIds)
            // Two triggers built in total: 'a' once, 'b' once. Not three.
            assertEquals(2, factory.created)

            engine.stop()
        }

    @Test
    fun `sync rebuilds a rule whose configuration changed`() =
        runTest(UnconfinedTestDispatcher()) {
            val factory = CountingTriggerFactory()
            val engine = TriggerEngine(idleRegistry(factory), this)

            engine.sync(listOf(idleRule("a", mapOf("level" to "20"))))
            engine.sync(listOf(idleRule("a", mapOf("level" to "30"))))

            assertEquals(setOf("a"), engine.runningRuleIds)
            assertEquals(2, factory.created)

            engine.stop()
        }

    @Test
    fun `a rule that cannot be built is reported, and the others still run`() =
        runTest(UnconfinedTestDispatcher()) {
            val failed = mutableListOf<String>()
            val engine = TriggerEngine(
                registry = idleRegistry(CountingTriggerFactory()),
                scope = this,
                onStartFailure = { rule, _ -> failed += rule.id },
            )

            engine.sync(
                listOf(
                    idleRule("bad").withTrigger(ComponentSpec("nope")),
                    idleRule("good"),
                )
            )

            assertEquals(listOf("bad"), failed)
            assertEquals(setOf("good"), engine.runningRuleIds)

            engine.stop()
        }

    /**
     * `sync` and `runningRuleIds` are reached from two different threads in the
     * real app and must not corrupt each other.
     *
     * This is not hypothetical. The hosting service calls `sync` from the
     * coroutine collecting the rule store, on `Dispatchers.Default` — while
     * `onStartCommand`, which Android delivers on the **main thread**, reads
     * `runningRuleIds` to label its notification. Every rule change triggers both
     * at once, because the app re-starts the service on each change. An
     * unsynchronised map there means iterating one thread's `HashMap` while
     * another restructures it: a `ConcurrentModificationException` on the main
     * thread, or a count that is simply wrong.
     *
     * Real threads rather than a test dispatcher, because a single-threaded
     * dispatcher is exactly the condition that hides this.
     */
    @Test
    fun `sync and runningRuleIds are safe to call from two threads`() {
        val scope = CoroutineScope(Dispatchers.Default)
        val engine = TriggerEngine(idleRegistry(CountingTriggerFactory()), scope)
        val failures = java.util.concurrent.CopyOnWriteArrayList<Throwable>()

        // Alternating sets, so every round both starts and stops something and
        // the map is genuinely restructured rather than merely written to.
        val even = listOf(idleRule("a"), idleRule("b"))
        val odd = listOf(idleRule("b"), idleRule("c"))

        val writer = Thread {
            runCatching {
                repeat(STRESS_ROUNDS) { round ->
                    engine.sync(if (round % 2 == 0) even else odd)
                }
            }.onFailure(failures::add)
        }

        val reader = Thread {
            runCatching {
                repeat(STRESS_ROUNDS) {
                    // .size forces the snapshot to be built, which is where an
                    // unguarded map throws.
                    engine.runningRuleIds.size
                }
            }.onFailure(failures::add)
        }

        writer.start()
        reader.start()
        writer.join()
        reader.join()

        engine.stop()
        scope.cancel()

        assertEquals("concurrent access threw: $failures", emptyList<Throwable>(), failures)
    }
}

/**
 * Enough rounds to make an unsynchronised map fail essentially every run, while
 * still finishing in well under a second.
 */
private const val STRESS_ROUNDS = 5_000

private const val TRIGGER_TYPE = "fake"
private const val ACTION_TYPE = "action-0"

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
    val engine = TriggerEngine(
        registry = registry,
        scope = scope,
        onOutcome = { _, _, _, result -> outcomes += result },
    )
    val rule = Rule(
        id = "rule-1",
        name = "test rule",
        trigger = ComponentSpec(TRIGGER_TYPE),
        actions = specs,
    )
    return Harness(engine, rule, outcomes)
}

private fun idleRegistry(triggerFactory: TriggerFactory) = Registry(
    triggerFactories = listOf(triggerFactory),
    actionFactories = listOf(SingleActionFactory(ACTION_TYPE, RecordingAction())),
)

private fun idleRule(
    id: String,
    config: Map<String, String> = emptyMap(),
    enabled: Boolean = true,
) = Rule(
    id = id,
    name = id,
    trigger = ComponentSpec(TRIGGER_TYPE, config),
    actions = listOf(ComponentSpec(ACTION_TYPE)),
    enabled = enabled,
)

private class FakeTriggerFactory(
    override val type: String,
    private val emissions: List<TriggerEvent>,
) : TriggerFactory {
    override fun create(config: Map<String, String>): Trigger = object : Trigger {
        override fun events(): Flow<TriggerEvent> = emissions.asFlow()
    }
}

/**
 * A trigger that never emits and never completes, so a started rule stays
 * running for the assertion — and a count of how many times one was built,
 * which is how "was this rule restarted?" is observed from outside.
 */
private class CountingTriggerFactory : TriggerFactory {
    override val type: String = TRIGGER_TYPE

    var created = 0
        private set

    override fun create(config: Map<String, String>): Trigger {
        created++
        return object : Trigger {
            override fun events(): Flow<TriggerEvent> = MutableSharedFlow()
        }
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
