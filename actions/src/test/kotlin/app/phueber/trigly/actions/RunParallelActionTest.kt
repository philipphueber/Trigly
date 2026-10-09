package app.phueber.trigly.actions

import app.phueber.trigly.core.Action
import app.phueber.trigly.core.ActionFactory
import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.ComponentSpec
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.InMemoryRuleRepository
import app.phueber.trigly.core.InMemoryRuleVariableStore
import app.phueber.trigly.core.InMemoryVariableStore
import app.phueber.trigly.core.Registry
import app.phueber.trigly.core.Rule
import app.phueber.trigly.core.RuleRunner
import app.phueber.trigly.core.RunRuleOutcome
import app.phueber.trigly.core.RunScope
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerEngine
import app.phueber.trigly.core.TriggerEvent
import app.phueber.trigly.core.TriggerFactory
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `run_parallel`: two rules run at the same time, and the action returns when
 * both are finished.
 *
 * The first half uses a fake [RuleRunner], so the tests see only what this
 * action does with the two calls. The second half uses a real [TriggerEngine],
 * because cancellation, the shared run scope and the loop guard belong to the
 * engine, and a fake cannot show that the action and the engine work together.
 * All times are virtual, from `runTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RunParallelActionTest {

    private val event = TriggerEvent(triggerType = "fake", firedAtMillis = 1_000)

    private fun rule(id: String, actions: List<ComponentSpec> = emptyList()) = Rule(
        id = id,
        name = "Rule $id",
        trigger = ComponentSpec(TRIGGER_TYPE),
        actions = actions,
        enabled = false,
    )

    /**
     * Waits [millisFor] each rule, records the start and the end in virtual
     * time, and answers [outcomeFor].
     */
    private class TimedRunner(
        private val scope: TestScope,
        private val millisFor: (String) -> Long,
        private val outcomeFor: (String) -> RunRuleOutcome = { RunRuleOutcome.Ran },
    ) : RuleRunner {
        val started = mutableMapOf<String, Long>()
        val finished = mutableMapOf<String, Long>()
        val cancelled = mutableListOf<String>()

        override suspend fun runNow(rule: Rule, causingEvent: TriggerEvent): RunRuleOutcome {
            started[rule.id] = scope.currentTime
            try {
                delay(millisFor(rule.id))
            } catch (c: CancellationException) {
                cancelled += rule.id
                throw c
            }
            finished[rule.id] = scope.currentTime
            return outcomeFor(rule.id)
        }
    }

    private fun repository() = InMemoryRuleRepository(listOf(rule("a"), rule("b")))

    // --- the action alone, against a fake runner ------------------------------------

    @Test
    fun `both branches start at once and the action returns after the slower one`() = runTest {
        val runner = TimedRunner(this, millisFor = { if (it == "a") 1_000 else 3_000 })
        val action = RunParallelAction(repository(), runner, "a", "b")

        val result = action.execute(event)

        assertEquals(ActionResult.Success(), result)
        assertEquals(mapOf("a" to 0L, "b" to 0L), runner.started)
        assertEquals(mapOf("a" to 1_000L, "b" to 3_000L), runner.finished)
        // In sequence this would be 4000. At the same time it is the slower one.
        assertEquals(3_000L, currentTime)
    }

    @Test
    fun `a refused branch does not stop the other, and the failure names it`() = runTest {
        val runner = TimedRunner(
            this,
            millisFor = { if (it == "a") 100 else 2_000 },
            outcomeFor = { if (it == "a") RunRuleOutcome.Refused("Too deep.") else RunRuleOutcome.Ran },
        )
        val action = RunParallelAction(repository(), runner, "a", "b")

        val result = action.execute(event)

        assertTrue("expected a failure, got $result", result is ActionResult.Failure)
        val reason = (result as ActionResult.Failure).reason
        assertTrue(reason, reason.contains("first branch") && reason.contains("Too deep."))
        assertFalse(reason, reason.contains("second branch"))
        assertEquals(2_000L, runner.finished["b"])
        assertEquals(2_000L, currentTime)
    }

    @Test
    fun `a branch that throws does not cancel the other branch`() = runTest {
        val finishedOther = mutableListOf<String>()
        val runner = object : RuleRunner {
            override suspend fun runNow(rule: Rule, causingEvent: TriggerEvent): RunRuleOutcome {
                if (rule.id == "a") {
                    delay(10)
                    error("branch blew up")
                }
                delay(500)
                finishedOther += rule.id
                return RunRuleOutcome.Ran
            }
        }
        val action = RunParallelAction(repository(), runner, "a", "b")

        val result = action.execute(event)

        assertTrue("expected a failure, got $result", result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).reason.contains("branch blew up"))
        assertEquals(listOf("b"), finishedOther)
    }

    @Test
    fun `a cancellation that does not come from the rule stops only its own branch`() = runTest {
        val runner = object : RuleRunner {
            override suspend fun runNow(rule: Rule, causingEvent: TriggerEvent): RunRuleOutcome {
                if (rule.id == "a") throw CancellationException("leaked timeout")
                delay(500)
                return RunRuleOutcome.Ran
            }
        }
        val action = RunParallelAction(repository(), runner, "a", "b")

        val result = action.execute(event)

        assertTrue("expected a failure, got $result", result is ActionResult.Failure)
        assertEquals(500L, currentTime)
    }

    @Test
    fun `cancelling the action cancels both branches`() = runTest {
        val runner = TimedRunner(this, millisFor = { 10_000 })
        val action = RunParallelAction(repository(), runner, "a", "b")

        val running = async { action.execute(event) }
        advanceTimeBy(1_000)
        runCurrent()
        running.cancel()
        advanceUntilIdle()

        assertEquals(setOf("a", "b"), runner.cancelled.toSet())
        assertTrue(runner.finished.isEmpty())
    }

    @Test
    fun `nothing runs when a branch has no rule, the same rule twice, or a deleted rule`() =
        runTest {
            val runner = TimedRunner(this, millisFor = { 0 })
            val cases = listOf(
                RunParallelAction(repository(), runner, null, "b"),
                RunParallelAction(repository(), runner, "a", " "),
                RunParallelAction(repository(), runner, "a", "a"),
                RunParallelAction(repository(), runner, "a", "gone"),
            )

            val results = cases.map { it.execute(event) }

            results.forEach { assertTrue("expected a failure, got $it", it is ActionResult.Failure) }
            assertTrue(
                "the deleted id is named",
                (results.last() as ActionResult.Failure).reason.contains("gone"),
            )
            assertTrue("no branch may start", runner.started.isEmpty())
        }

    @Test
    fun `the factory offers two required rule pickers and builds the action`() {
        val factory = RunParallelActionFactory(repository(), TimedRunner(TestScope(), { 0 }))

        assertEquals("run_parallel", factory.type)
        assertEquals(
            listOf(RunParallelAction.CONFIG_FIRST_RULE, RunParallelAction.CONFIG_SECOND_RULE),
            factory.configFields.map { it.key },
        )
        // Read through the interface type, as the editor reads it.
        val fields: List<ConfigField> = factory.configFields
        assertTrue(fields.all { it is ConfigField.RuleRef && it.required })
        assertTrue(
            factory.create(
                mapOf(
                    RunParallelAction.CONFIG_FIRST_RULE to "a",
                    RunParallelAction.CONFIG_SECOND_RULE to "b",
                ),
            ) is RunParallelAction,
        )
    }

    // --- the action inside a real engine ----------------------------------------------

    /**
     * One rule that fires once and runs `run_parallel` and then [AFTER_TYPE].
     * Branch "a" waits one second, branch "b" waits three, and each writes a
     * run-scope value when it is finished.
     */
    private class EngineHarness(scope: TestScope, extraBranchActions: List<ComponentSpec> = emptyList()) {
        val log = mutableListOf<Pair<Long, String>>()
        var afterSaw: Map<String, String>? = null
        val outcomes = mutableListOf<Triple<String, String, ActionResult>>()
        val cancelled = mutableListOf<String>()

        val caller = Rule(
            id = "caller",
            name = "Caller",
            trigger = ComponentSpec(TRIGGER_TYPE),
            actions = listOf(
                ComponentSpec(
                    RunParallelAction.TYPE,
                    mapOf(
                        RunParallelAction.CONFIG_FIRST_RULE to "a",
                        RunParallelAction.CONFIG_SECOND_RULE to "b",
                    ),
                ),
                ComponentSpec(AFTER_TYPE),
            ),
        )
        val repository = InMemoryRuleRepository(
            listOf(
                caller,
                Rule("a", "A", ComponentSpec(TRIGGER_TYPE), listOf(ComponentSpec("wait_a")) + extraBranchActions, enabled = false),
                Rule("b", "B", ComponentSpec(TRIGGER_TYPE), listOf(ComponentSpec("wait_b")), enabled = false),
            ),
        )
        private val runner = DeferredRunner()

        val engine = TriggerEngine(
            registry = Registry(
                triggerFactories = listOf(OnceTriggerFactory()),
                actionFactories = listOf(
                    RunParallelActionFactory(repository, runner),
                    WaitAndWriteFactory("wait_a", 1_000, scope, log, cancelled),
                    WaitAndWriteFactory("wait_b", 3_000, scope, log, cancelled),
                    object : ActionFactory {
                        override val type = AFTER_TYPE
                        override fun create(config: Map<String, String>) = object : Action {
                            override suspend fun execute(event: TriggerEvent): ActionResult {
                                log += scope.currentTime to AFTER_TYPE
                                afterSaw = coroutineContext[RunScope]?.snapshot()
                                return ActionResult.Success()
                            }
                        }
                    },
                    object : ActionFactory {
                        override val type = RUN_CALLER_TYPE
                        override fun create(config: Map<String, String>) = object : Action {
                            override suspend fun execute(event: TriggerEvent): ActionResult {
                                val outcome = runner.runNow(caller, event)
                                return if (outcome is RunRuleOutcome.Refused) {
                                    ActionResult.Failure(outcome.reason)
                                } else {
                                    ActionResult.Success()
                                }
                            }
                        }
                    },
                ),
            ),
            store = InMemoryVariableStore(),
            ruleStore = InMemoryRuleVariableStore(),
            scope = scope,
            onOutcome = { rule, _, type, result -> outcomes += Triple(rule.id, type, result) },
        )

        init {
            runner.engine = engine
        }
    }

    /** The engine does not exist when the factory is built, the same as in
     * the app. This plays the part of `RuleRunnerHandle`. */
    private class DeferredRunner : RuleRunner {
        lateinit var engine: TriggerEngine
        override suspend fun runNow(rule: Rule, causingEvent: TriggerEvent) =
            engine.runNow(rule, causingEvent)
    }

    @Test
    fun `in the engine, the next action starts after the slower branch and sees both writes`() =
        runTest {
            val h = EngineHarness(this)

            h.engine.startRule(h.caller)
            advanceUntilIdle()

            assertEquals(
                listOf(1_000L to "wait_a", 3_000L to "wait_b", 3_000L to AFTER_TYPE),
                h.log,
            )
            assertEquals(mapOf("wait_a" to "done", "wait_b" to "done"), h.afterSaw)
            assertEquals(
                ActionResult.Success(),
                h.outcomes.single { it.first == "caller" && it.second == RunParallelAction.TYPE }.third,
            )
            h.engine.stop()
        }

    @Test
    fun `in the engine, stopping the rule cancels both branches and the rest of the rule`() =
        runTest {
            val h = EngineHarness(this)

            h.engine.startRule(h.caller)
            advanceTimeBy(500)
            runCurrent()
            h.engine.stopRule(h.caller.id)
            advanceUntilIdle()

            assertEquals(setOf("wait_a", "wait_b"), h.cancelled.toSet())
            assertTrue("no action may run after a stop: ${h.log}", h.log.isEmpty())
        }

    @Test
    fun `in the engine, a branch that runs the calling rule is refused and the other branch finishes`() =
        runTest {
            val h = EngineHarness(this, extraBranchActions = listOf(ComponentSpec(RUN_CALLER_TYPE)))

            h.engine.startRule(h.caller)
            advanceUntilIdle()

            // Branch "a" itself ran. Its call back into the caller was refused
            // by the engine's loop guard, and reported against branch "a".
            val loop = h.outcomes.single { it.second == RUN_CALLER_TYPE }
            assertEquals("a", loop.first)
            assertTrue(loop.third is ActionResult.Failure)
            assertTrue(h.log.contains(3_000L to "wait_b"))
            assertTrue(h.log.contains(3_000L to AFTER_TYPE))
            h.engine.stop()
        }

    /** A trigger that fires once. */
    private class OnceTriggerFactory : TriggerFactory {
        override val type = TRIGGER_TYPE
        override fun create(config: Map<String, String>): Trigger = object : Trigger {
            override fun events(): Flow<TriggerEvent> =
                flowOf(TriggerEvent(TRIGGER_TYPE, firedAtMillis = 1))
        }
    }

    /** Waits [millis], then writes `<type>=done` into the run scope. */
    private class WaitAndWriteFactory(
        override val type: String,
        private val millis: Long,
        private val scope: TestScope,
        private val log: MutableList<Pair<Long, String>>,
        private val cancelled: MutableList<String>,
    ) : ActionFactory {
        override fun create(config: Map<String, String>) = object : Action {
            override suspend fun execute(event: TriggerEvent): ActionResult {
                try {
                    delay(millis)
                } catch (c: CancellationException) {
                    cancelled += type
                    throw c
                }
                coroutineContext[RunScope]?.set(type, "done")
                log += scope.currentTime to type
                return ActionResult.Success()
            }
        }
    }

    private companion object {
        const val TRIGGER_TYPE = "fake"
        const val AFTER_TYPE = "after"
        const val RUN_CALLER_TYPE = "run_caller"
    }
}
