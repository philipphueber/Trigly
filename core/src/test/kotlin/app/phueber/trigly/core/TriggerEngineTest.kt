package app.phueber.trigly.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
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
            val engine = TriggerEngine(idleRegistry(CountingTriggerFactory()), InMemoryVariableStore(), this)

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
            val engine = TriggerEngine(idleRegistry(factory), InMemoryVariableStore(), this)

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
            val engine = TriggerEngine(idleRegistry(factory), InMemoryVariableStore(), this)

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
                store = InMemoryVariableStore(),
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
     * The case this whole callback exists for: a rule fires, an `ALL` group asks
     * its other leaf whether it holds, and that leaf cannot answer.
     *
     * Real and not hypothetical. "A notification arrives AND I am in this area"
     * is exactly this shape, and until the app held background location the area
     * check answered null every time the engine ran off screen. The rule was
     * dropped in silence, which on screen is identical to being outside the area.
     */
    @Test
    fun `a rule dropped by a component that could not answer is reported`() =
        runTest(UnconfinedTestDispatcher()) {
            val suppressed = mutableListOf<List<String>>()
            val engine = TriggerEngine(
                registry = Registry(
                    triggerFactories = listOf(
                        FakeTriggerFactory(TRIGGER_TYPE, listOf(event(1L))),
                        UnreadableTriggerFactory(),
                    ),
                    actionFactories = listOf(SingleActionFactory(ACTION_TYPE, RecordingAction())),
                ),
                store = InMemoryVariableStore(),
                scope = this,
                onSuppressed = { _, _, unreadable -> suppressed += unreadable.map { it.type } },
            )

            engine.startRule(
                Rule(
                    id = "rule-1",
                    name = "notification and area",
                    trigger = TriggerNode.Group(
                        TriggerNode.Op.ALL,
                        listOf(
                            TriggerNode.One(ComponentSpec(TRIGGER_TYPE)),
                            TriggerNode.One(ComponentSpec(UNREADABLE_TYPE)),
                        ),
                    ),
                    actions = listOf(ComponentSpec(ACTION_TYPE)),
                )
            )

            // The report comes after the retry budget, not on the first miss.
            // A component that answers on a later try is the rule working late,
            // so nothing is reported until every try has missed.
            advanceTimeBy(UNREADABLE_RETRIES * UNREADABLE_RETRY_DELAY_MILLIS + 1)

            assertEquals(listOf(listOf(UNREADABLE_TYPE)), suppressed)

            engine.stop()
        }

    /**
     * A read that is slow rather than absent still ends inside the budget.
     *
     * The bound this pins is the whole evaluation, reads included. Before it,
     * the waits between tries were bounded and the reads were not, so a leaf
     * with a slow read could hold a rule for four times what the schedule
     * suggested. The `location` component allows one position read fifteen
     * seconds, which is what made that reachable rather than theoretical.
     */
    @Test
    fun `a leaf whose read never finishes is given up on inside the budget`() =
        runTest(UnconfinedTestDispatcher()) {
            val action = RecordingAction()
            val suppressed = mutableListOf<List<String>>()
            val engine = TriggerEngine(
                registry = Registry(
                    triggerFactories = listOf(
                        FakeTriggerFactory(TRIGGER_TYPE, listOf(event(1L))),
                        // Far longer than the budget, so it can only be cut off.
                        SlowTriggerFactory(readMillis = UNREADABLE_TOTAL_BUDGET_MILLIS * 10),
                    ),
                    actionFactories = listOf(SingleActionFactory(ACTION_TYPE, action)),
                ),
                store = InMemoryVariableStore(),
                scope = this,
                onSuppressed = { _, _, unreadable -> suppressed += unreadable.map { it.type } },
            )

            engine.startRule(
                Rule(
                    id = "rule-1",
                    name = "slow condition",
                    trigger = TriggerNode.Group(
                        TriggerNode.Op.ALL,
                        listOf(
                            TriggerNode.One(ComponentSpec(TRIGGER_TYPE)),
                            TriggerNode.One(ComponentSpec(UNREADABLE_TYPE)),
                        ),
                    ),
                    actions = listOf(ComponentSpec(ACTION_TYPE)),
                ),
            )

            advanceTimeBy(UNREADABLE_TOTAL_BUDGET_MILLIS + 1)

            assertTrue("no action may run on a state nobody read", action.seen.isEmpty())
            // Named, not merely counted. A read cancelled while it is still
            // running never returns to report itself, so the reader has to mark
            // the leaf before it asks.
            assertEquals(listOf(listOf(UNREADABLE_TYPE)), suppressed)

            engine.stop()
        }

    /**
     * The other half, and the one that keeps the report from crying wolf. A
     * condition that answers a clean "no" is the rule working as written, so
     * nothing is reported. Without this guard every rule with a condition would
     * accuse itself each time the condition was simply false.
     */
    @Test
    fun `a rule held back by a condition that answered no is not reported`() =
        runTest(UnconfinedTestDispatcher()) {
            val suppressed = mutableListOf<List<String>>()
            val engine = TriggerEngine(
                registry = Registry(
                    triggerFactories = listOf(
                        FakeTriggerFactory(TRIGGER_TYPE, listOf(event(1L))),
                        UnreadableTriggerFactory(answer = false),
                    ),
                    actionFactories = listOf(SingleActionFactory(ACTION_TYPE, RecordingAction())),
                ),
                store = InMemoryVariableStore(),
                scope = this,
                onSuppressed = { _, _, unreadable -> suppressed += unreadable.map { it.type } },
            )

            engine.startRule(
                Rule(
                    id = "rule-1",
                    name = "notification and area",
                    trigger = TriggerNode.Group(
                        TriggerNode.Op.ALL,
                        listOf(
                            TriggerNode.One(ComponentSpec(TRIGGER_TYPE)),
                            TriggerNode.One(ComponentSpec(UNREADABLE_TYPE)),
                        ),
                    ),
                    actions = listOf(ComponentSpec(ACTION_TYPE)),
                )
            )

            assertEquals(emptyList<List<String>>(), suppressed)

            engine.stop()
        }

    /**
     * The retry T3 adds. A component that misses once and then answers is the
     * rule working, a little late, not a fault: nothing is reported, and the
     * action still runs for the event that started the whole evaluation.
     */
    @Test
    fun `a rule fires after a read that failed once and then answered`() =
        runTest(UnconfinedTestDispatcher()) {
            val action = RecordingAction()
            val suppressed = mutableListOf<List<String>>()
            val engine = TriggerEngine(
                registry = Registry(
                    triggerFactories = listOf(
                        FakeTriggerFactory(TRIGGER_TYPE, listOf(event(1L))),
                        FlakyTriggerFactory(missesBeforeAnswering = 1, answer = true),
                    ),
                    actionFactories = listOf(SingleActionFactory(ACTION_TYPE, action)),
                ),
                store = InMemoryVariableStore(),
                scope = this,
                onSuppressed = { _, _, unreadable -> suppressed += unreadable.map { it.type } },
            )

            engine.startRule(
                Rule(
                    id = "rule-1",
                    name = "notification and area",
                    trigger = TriggerNode.Group(
                        TriggerNode.Op.ALL,
                        listOf(
                            TriggerNode.One(ComponentSpec(TRIGGER_TYPE)),
                            TriggerNode.One(ComponentSpec(UNREADABLE_TYPE)),
                        ),
                    ),
                    actions = listOf(ComponentSpec(ACTION_TYPE)),
                ),
            )

            // One retry is all this needs: the first read misses, the engine
            // waits out one retry delay, and the second read is the one that
            // answers. Virtual time, not a real wait.
            advanceTimeBy(UNREADABLE_RETRY_DELAY_MILLIS + 1)

            assertEquals(listOf(1L), action.seen.map { it.firedAtMillis })
            assertEquals(emptyList<List<String>>(), suppressed)

            engine.stop()
        }

    /**
     * The other half. A component that never answers, however many times the
     * engine asks, ends the wait with one named fault rather than an event
     * held open forever. The whole retry schedule elapses in virtual time
     * here, which is the point of driving this engine with fakes at all.
     */
    @Test
    fun `a rule reports a give-up after a read that never answered`() =
        runTest(UnconfinedTestDispatcher()) {
            val action = RecordingAction()
            val suppressed = mutableListOf<List<String>>()
            val engine = TriggerEngine(
                registry = Registry(
                    triggerFactories = listOf(
                        FakeTriggerFactory(TRIGGER_TYPE, listOf(event(1L))),
                        UnreadableTriggerFactory(),
                    ),
                    actionFactories = listOf(SingleActionFactory(ACTION_TYPE, action)),
                ),
                store = InMemoryVariableStore(),
                scope = this,
                onSuppressed = { _, _, unreadable -> suppressed += unreadable.map { it.type } },
            )

            engine.startRule(
                Rule(
                    id = "rule-1",
                    name = "notification and area",
                    trigger = TriggerNode.Group(
                        TriggerNode.Op.ALL,
                        listOf(
                            TriggerNode.One(ComponentSpec(TRIGGER_TYPE)),
                            TriggerNode.One(ComponentSpec(UNREADABLE_TYPE)),
                        ),
                    ),
                    actions = listOf(ComponentSpec(ACTION_TYPE)),
                ),
            )

            // The whole budget: the first read plus every retry, all of them
            // missing.
            advanceTimeBy(UNREADABLE_RETRIES * UNREADABLE_RETRY_DELAY_MILLIS + 1)

            assertTrue(action.seen.isEmpty())
            // Reported once, after the budget is spent, not once per miss.
            assertEquals(listOf(listOf(UNREADABLE_TYPE)), suppressed)

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
        val engine = TriggerEngine(idleRegistry(CountingTriggerFactory()), InMemoryVariableStore(), scope)
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

    // --- the variable seam: ActionSlot, exercised through TriggerEngine -------------

    @Test
    fun `an action with no variables is built exactly once, however many events fire`() =
        runTest(UnconfinedTestDispatcher()) {
            val factory = CountingActionFactory(ACTION_TYPE)
            val emissions = listOf(event(1), event(2), event(3))
            val engine = TriggerEngine(
                registry = Registry(
                    triggerFactories = listOf(FakeTriggerFactory(TRIGGER_TYPE, emissions)),
                    actionFactories = listOf(factory),
                ),
                store = InMemoryVariableStore(),
                scope = this,
            )

            engine.startRule(
                Rule(
                    id = "rule-1",
                    name = "plain",
                    trigger = ComponentSpec(TRIGGER_TYPE),
                    actions = listOf(ComponentSpec(ACTION_TYPE)),
                )
            )

            // Built once at start, and never again. This is the compatibility
            // promise the whole feature rests on: no existing rule pays for a
            // feature it does not use.
            assertEquals(1, factory.buildCount)
            engine.stop()
        }

    @Test
    fun `an action with a variable is rebuilt when the resolved value changes`() =
        runTest(UnconfinedTestDispatcher()) {
            val fields = listOf(
                ConfigField.Text(key = "text", label = "Text", substitution = Substitution.TEXT),
            )
            val factory = CountingActionFactory(ACTION_TYPE, fields)
            val emissions = listOf(
                TriggerEvent(TRIGGER_TYPE, 1L, mapOf("level" to "10")),
                TriggerEvent(TRIGGER_TYPE, 2L, mapOf("level" to "20")),
            )
            val engine = TriggerEngine(
                registry = Registry(
                    triggerFactories = listOf(FakeTriggerFactory(TRIGGER_TYPE, emissions)),
                    actionFactories = listOf(factory),
                ),
                store = InMemoryVariableStore(),
                scope = this,
            )
            val config = mapOf("text" to "Battery: {{trigger.level}}%")

            engine.startRule(
                Rule(
                    id = "rule-1",
                    name = "templated",
                    trigger = ComponentSpec(TRIGGER_TYPE),
                    actions = listOf(ComponentSpec(ACTION_TYPE, config)),
                )
            )

            // One build at start from the raw config, plus one per event whose
            // resolved value actually differs from the last build.
            assertEquals(3, factory.buildCount)
            assertEquals(
                listOf("Battery: {{trigger.level}}%", "Battery: 10%", "Battery: 20%"),
                factory.builtWith.map { it.getValue("text") },
            )
            engine.stop()
        }

    @Test
    fun `an action with a variable is not rebuilt when the resolved value repeats`() =
        runTest(UnconfinedTestDispatcher()) {
            val fields = listOf(
                ConfigField.Text(key = "text", label = "Text", substitution = Substitution.TEXT),
            )
            val factory = CountingActionFactory(ACTION_TYPE, fields)
            val emissions = listOf(
                TriggerEvent(TRIGGER_TYPE, 1L, mapOf("level" to "10")),
                TriggerEvent(TRIGGER_TYPE, 2L, mapOf("level" to "10")),
            )
            val engine = TriggerEngine(
                registry = Registry(
                    triggerFactories = listOf(FakeTriggerFactory(TRIGGER_TYPE, emissions)),
                    actionFactories = listOf(factory),
                ),
                store = InMemoryVariableStore(),
                scope = this,
            )
            val config = mapOf("text" to "Battery: {{trigger.level}}%")

            engine.startRule(
                Rule(
                    id = "rule-1",
                    name = "templated",
                    trigger = ComponentSpec(TRIGGER_TYPE),
                    actions = listOf(ComponentSpec(ACTION_TYPE, config)),
                )
            )

            // Start, then one rebuild for the first event. The second event
            // resolves to the same text, so the live instance is reused.
            assertEquals(2, factory.buildCount)
            engine.stop()
        }

    // --- app scope: the store read TriggerEngine.ActionSlot does per action ---------

    @Test
    fun `an action reading an app variable gets the value from the store`() =
        runTest(UnconfinedTestDispatcher()) {
            val fields = listOf(
                ConfigField.Text(key = "text", label = "Text", substitution = Substitution.TEXT),
            )
            val factory = CountingActionFactory(ACTION_TYPE, fields)
            val store = InMemoryVariableStore(mapOf("trip_count" to "5"))
            val engine = TriggerEngine(
                registry = Registry(
                    triggerFactories = listOf(FakeTriggerFactory(TRIGGER_TYPE, listOf(event(1)))),
                    actionFactories = listOf(factory),
                ),
                store = store,
                scope = this,
            )
            val config = mapOf("text" to "Trips: {{app.trip_count}}")

            engine.startRule(
                Rule(
                    id = "rule-1",
                    name = "reads app scope",
                    trigger = ComponentSpec(TRIGGER_TYPE),
                    actions = listOf(ComponentSpec(ACTION_TYPE, config)),
                )
            )

            assertEquals(
                listOf("Trips: {{app.trip_count}}", "Trips: 5"),
                factory.builtWith.map { it.getValue("text") },
            )
            engine.stop()
        }

    @Test
    fun `an app-scope action is rebuilt only when the stored value actually changes`() =
        runTest(UnconfinedTestDispatcher()) {
            val fields = listOf(
                ConfigField.Text(key = "text", label = "Text", substitution = Substitution.TEXT),
            )
            val factory = CountingActionFactory(ACTION_TYPE, fields)
            val store = InMemoryVariableStore(mapOf("trip_count" to "5"))
            // Controllable rather than a fixed list, and drained with
            // advanceUntilIdle after each event, so the store can be changed
            // between two events with the guarantee that the first event's
            // action has actually finished reading it. `TriggerEngine`
            // collects every leaf's events through a `merge()`, which buffers,
            // so firing all three events up front (as a scripted cold flow
            // would) races the store write against event processing instead
            // of ordering the two, and that race is exactly what a `flow {}`
            // producer emitting ahead of a buffered `merge()` consumer loses.
            val trigger = ControllableTriggerFactory(TRIGGER_TYPE)
            val engine = TriggerEngine(
                registry = Registry(
                    triggerFactories = listOf(trigger),
                    actionFactories = listOf(factory),
                ),
                store = store,
                scope = this,
            )
            val config = mapOf("text" to "Trips: {{app.trip_count}}")

            engine.startRule(
                Rule(
                    id = "rule-1",
                    name = "app scope reuse",
                    trigger = ComponentSpec(TRIGGER_TYPE),
                    actions = listOf(ComponentSpec(ACTION_TYPE, config)),
                )
            )

            trigger.events.emit(event(1))
            advanceUntilIdle()
            trigger.events.emit(event(2)) // Store unchanged: must reuse the first rebuild.
            advanceUntilIdle()
            store.set("trip_count", "6")
            trigger.events.emit(event(3)) // Store changed: must rebuild again.
            advanceUntilIdle()

            // Start (raw config), event 1 (first real read, differs from raw:
            // rebuild), event 2 (same value: reused, no third entry here),
            // event 3 (value changed: rebuild). Three builds, not four.
            assertEquals(
                listOf("Trips: {{app.trip_count}}", "Trips: 5", "Trips: 6"),
                factory.builtWith.map { it.getValue("text") },
            )
            assertEquals(3, factory.buildCount)
            engine.stop()
        }

    @Test
    fun `an action that names no app variable never reads the store`() =
        runTest(UnconfinedTestDispatcher()) {
            val fields = listOf(
                ConfigField.Text(key = "text", label = "Text", substitution = Substitution.TEXT),
            )
            val factory = CountingActionFactory(ACTION_TYPE, fields)
            val store = CountingVariableStore()
            val emissions = listOf(
                TriggerEvent(TRIGGER_TYPE, 1L, mapOf("level" to "10")),
                TriggerEvent(TRIGGER_TYPE, 2L, mapOf("level" to "20")),
            )
            val engine = TriggerEngine(
                registry = Registry(
                    triggerFactories = listOf(FakeTriggerFactory(TRIGGER_TYPE, emissions)),
                    actionFactories = listOf(factory),
                ),
                store = store,
                scope = this,
            )
            // A trigger-scope reference only. This is the promise that keeps
            // existing rules free: naming no app variable must cost nothing.
            val config = mapOf("text" to "Battery: {{trigger.level}}%")

            engine.startRule(
                Rule(
                    id = "rule-1",
                    name = "no app scope",
                    trigger = ComponentSpec(TRIGGER_TYPE),
                    actions = listOf(ComponentSpec(ACTION_TYPE, config)),
                )
            )

            assertEquals(3, factory.buildCount)
            assertEquals(0, store.getCalls)
            engine.stop()
        }

    @Test
    fun `a later action sees a variable an earlier action wrote in the same run`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = InMemoryVariableStore()
            val fields = listOf(
                ConfigField.Text(key = "text", label = "Text", substitution = Substitution.TEXT),
            )
            val readFactory = CountingActionFactory("read-action", fields)
            val writeFactory = WritingActionFactory("write-action", store, "trip_count", "1")
            val engine = TriggerEngine(
                registry = Registry(
                    triggerFactories = listOf(FakeTriggerFactory(TRIGGER_TYPE, listOf(event(1)))),
                    actionFactories = listOf(writeFactory, readFactory),
                ),
                store = store,
                scope = this,
            )

            engine.startRule(
                Rule(
                    id = "rule-1",
                    name = "write then read",
                    trigger = ComponentSpec(TRIGGER_TYPE),
                    actions = listOf(
                        ComponentSpec("write-action"),
                        ComponentSpec("read-action", mapOf("text" to "Trips: {{app.trip_count}}")),
                    ),
                )
            )

            // A snapshot taken once for the whole event, before either action
            // ran, would have shown the read action nothing here. Reading
            // immediately before each action runs is what lets the second
            // action see what the first one, running just before it, wrote.
            assertEquals("Trips: 1", readFactory.builtWith.last().getValue("text"))
            engine.stop()
        }

    @Test
    fun `a field that cannot be resolved fails through onOutcome and the action does not run`() =
        runTest(UnconfinedTestDispatcher()) {
            val fields = listOf(
                ConfigField.Text(key = "text", label = "Text", substitution = Substitution.TEXT),
            )
            val action = RecordingAction()
            val outcomes = mutableListOf<ActionResult>()
            val actionFactory = TemplatedRecordingActionFactory(ACTION_TYPE, fields, action)
            val engine = TriggerEngine(
                registry = Registry(
                    triggerFactories = listOf(FakeTriggerFactory(TRIGGER_TYPE, listOf(event(1)))),
                    actionFactories = listOf(actionFactory),
                ),
                store = InMemoryVariableStore(),
                scope = this,
                onOutcome = { _, _, _, result -> outcomes += result },
            )
            val config = mapOf("text" to "{{trigger.missing}}")

            engine.startRule(
                Rule(
                    id = "rule-1",
                    name = "unresolvable",
                    trigger = ComponentSpec(TRIGGER_TYPE),
                    actions = listOf(ComponentSpec(ACTION_TYPE, config)),
                )
            )

            assertTrue(action.seen.isEmpty())
            val failure = outcomes.single() as ActionResult.Failure
            // Names the config key, not just "an action failed".
            assertTrue(failure.reason.contains("text"))
            engine.stop()
        }

    @Test
    fun `a rule naming an unknown action type still fails at start`() =
        runTest(UnconfinedTestDispatcher()) {
            val failed = mutableListOf<String>()
            val engine = TriggerEngine(
                registry = Registry(
                    triggerFactories = listOf(FakeTriggerFactory(TRIGGER_TYPE, listOf(event(1)))),
                    actionFactories = emptyList(),
                ),
                store = InMemoryVariableStore(),
                scope = this,
                onStartFailure = { rule, _ -> failed += rule.id },
            )

            // The slot builds an instance from the raw config at start time,
            // which is what keeps this failing inside startRule rather than
            // regressing into a failed run at the first event.
            engine.sync(
                listOf(
                    Rule(
                        id = "rule-1",
                        name = "bad action",
                        trigger = ComponentSpec(TRIGGER_TYPE),
                        actions = listOf(ComponentSpec("nope")),
                    )
                )
            )

            assertEquals(listOf("rule-1"), failed)
            engine.stop()
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
        store = InMemoryVariableStore(),
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

private const val UNREADABLE_TYPE = "cannot-answer"

/**
 * A leaf that never starts a rule and whose state read answers [answer].
 *
 * `null` is the shape of the real failure: a location check with no position, a
 * notification condition with no bound listener. `false` is the same component
 * simply saying no, which is the control the second test needs.
 */
/**
 * A leaf whose read takes [readMillis] and then answers.
 *
 * The other way to fail, and the one a list of misses cannot express. Every
 * other fake here answers instantly or not at all, so none of them can run the
 * evaluation's budget out. This one can, which is the case
 * [UNREADABLE_TOTAL_BUDGET_MILLIS] exists for: a read cancelled by the budget
 * never returns to say it failed.
 */
private class SlowTriggerFactory(
    private val readMillis: Long,
    private val answer: Boolean = true,
) : TriggerFactory {
    override val type: String = UNREADABLE_TYPE
    override val supportsCondition = true
    override val producesEvents = false

    override fun create(config: Map<String, String>): Trigger = object : Trigger {
        override fun events(): Flow<TriggerEvent> = emptyFlow()
        override suspend fun currentlyHolds(): Boolean {
            delay(readMillis)
            return answer
        }
    }
}

private class UnreadableTriggerFactory(private val answer: Boolean? = null) : TriggerFactory {
    override val type: String = UNREADABLE_TYPE
    override val supportsCondition = true
    override val producesEvents = false

    override fun create(config: Map<String, String>): Trigger = object : Trigger {
        override fun events(): Flow<TriggerEvent> = emptyFlow()
        override suspend fun currentlyHolds(): Boolean? = answer
    }
}

/**
 * A leaf that misses the first [missesBeforeAnswering] reads and then answers
 * [answer] on every read after that.
 *
 * The shape of the failure the retry exists for: a read that comes back once
 * it is asked again, not one that is broken forever. [UnreadableTriggerFactory]
 * with a null answer is the other half, a component that never comes back.
 */
private class FlakyTriggerFactory(
    private val missesBeforeAnswering: Int,
    private val answer: Boolean,
) : TriggerFactory {
    override val type: String = UNREADABLE_TYPE
    override val supportsCondition = true
    override val producesEvents = false

    override fun create(config: Map<String, String>): Trigger = object : Trigger {
        var reads = 0
        override fun events(): Flow<TriggerEvent> = emptyFlow()
        override suspend fun currentlyHolds(): Boolean? {
            reads++
            return if (reads <= missesBeforeAnswering) null else answer
        }
    }
}

private class FakeTriggerFactory(
    override val type: String,
    private val emissions: List<TriggerEvent>,
) : TriggerFactory {
    override fun create(config: Map<String, String>): Trigger = object : Trigger {
        override fun events(): Flow<TriggerEvent> = emissions.asFlow()
    }
}

/**
 * A trigger whose events are fired one at a time by the test, through
 * [events], rather than replayed from a fixed list.
 *
 * What a test needs to act *between* two events, such as changing the store,
 * with the guarantee that the first event's actions have actually finished
 * first. [FakeTriggerFactory]'s fixed list cannot express that ordering:
 * `TriggerEngine` collects every leaf's events through a `merge()`, which
 * buffers, so a source flow that emits several values up front races ahead of
 * the engine rather than waiting on it. Emitting one event and draining the
 * test scheduler with `advanceUntilIdle()` before emitting the next is what
 * actually orders "the engine is done with this event" before "act on the
 * effects of that event".
 */
private class ControllableTriggerFactory(override val type: String) : TriggerFactory {
    val events = MutableSharedFlow<TriggerEvent>()
    override fun create(config: Map<String, String>): Trigger = object : Trigger {
        override fun events(): Flow<TriggerEvent> = events
    }
}

/**
 * Wraps a [VariableStore] and counts calls to [get], so a test can prove an
 * action never touched the store rather than merely observing it behaved as
 * if it had not. A plain [InMemoryVariableStore] cannot say that of itself.
 */
private class CountingVariableStore(
    private val delegate: VariableStore = InMemoryVariableStore(),
) : VariableStore {
    var getCalls = 0
        private set

    override fun history() = delegate.history()

    override suspend fun get(name: String): String? {
        getCalls++
        return delegate.get(name)
    }

    override suspend fun set(name: String, value: String) = delegate.set(name, value)

    override suspend fun remove(name: String) = delegate.remove(name)
}

/**
 * An action that writes one fixed value to [store] when it runs, standing in
 * for `set_variable`. What proves a later action in the same rule sees a
 * value an earlier one just wrote.
 */
private class WritingActionFactory(
    override val type: String,
    private val store: VariableStore,
    private val name: String,
    private val value: String,
) : ActionFactory {
    override fun create(config: Map<String, String>): Action = object : Action {
        override suspend fun execute(event: TriggerEvent): ActionResult {
            store.set(name, value)
            return ActionResult.Success
        }
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

/**
 * An action factory that counts how many times [create] is called, and what
 * config each call was built from.
 *
 * The reuse rule is invisible unless something outside `ActionSlot` can see
 * how often the factory is asked, since a plain fake returning one fixed
 * instance cannot tell a fresh build from a reused one.
 */
private class CountingActionFactory(
    override val type: String,
    override val configFields: List<ConfigField> = emptyList(),
) : ActionFactory {
    var buildCount = 0
        private set

    val builtWith = mutableListOf<Map<String, String>>()

    override fun create(config: Map<String, String>): Action {
        buildCount++
        builtWith += config
        return object : Action {
            override suspend fun execute(event: TriggerEvent): ActionResult = ActionResult.Success
        }
    }
}

/**
 * An action factory that always hands back the same [RecordingAction],
 * whatever config it is built with, so a test can tell whether the action ran
 * at all rather than which instance ran.
 */
private class TemplatedRecordingActionFactory(
    override val type: String,
    override val configFields: List<ConfigField>,
    private val action: RecordingAction,
) : ActionFactory {
    override fun create(config: Map<String, String>): Action = action
}
