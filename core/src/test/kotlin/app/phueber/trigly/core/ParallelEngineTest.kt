package app.phueber.trigly.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.currentCoroutineContext

/**
 * How the engine runs a parallel block: a fork and a join inside one rule.
 * See [ActionNode.Parallel] and `TriggerEngine.runParallel`.
 *
 * Virtual time, so "at the same time" is a number a test can read: two
 * branches that wait one and three seconds are both finished at three seconds,
 * not at four, and the action after the block starts at three.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ParallelEngineTest {

    @Test
    fun `branches run at the same time and the next action waits for the slowest`() = runTest {
        val log = mutableListOf<Pair<String, Long>>()
        val engine = engine(log)

        engine.startRule(
            rule(
                ActionNode.Parallel(
                    listOf(
                        listOf(wait("fast", 1_000)),
                        listOf(wait("slow", 3_000)),
                    )
                ),
                wait("after", 0),
            )
        )
        advanceUntilIdle()

        assertEquals(
            listOf("fast" to 1_000L, "slow" to 3_000L, "after" to 3_000L),
            log,
        )
        engine.stop()
    }

    @Test
    fun `a branch runs its own actions in order`() = runTest {
        val log = mutableListOf<Pair<String, Long>>()
        val engine = engine(log)

        engine.startRule(
            rule(
                ActionNode.Parallel(
                    listOf(
                        listOf(wait("a1", 500), wait("a2", 500)),
                        listOf(wait("b1", 200)),
                    )
                ),
            )
        )
        advanceUntilIdle()

        assertEquals(listOf("b1" to 200L, "a1" to 500L, "a2" to 1_000L), log)
        engine.stop()
    }

    @Test
    fun `a failed action does not stop its branch or the other branch`() = runTest {
        val log = mutableListOf<Pair<String, Long>>()
        val outcomes = mutableListOf<Pair<String, ActionResult>>()
        val engine = engine(log, outcomes)

        engine.startRule(
            rule(
                ActionNode.Parallel(
                    listOf(
                        listOf(ComponentSpec(THROWS), wait("a2", 100)),
                        listOf(wait("b1", 300)),
                    )
                ),
                wait("after", 0),
            )
        )
        advanceUntilIdle()

        assertEquals(listOf("a2", "b1", "after"), log.map { it.first })
        assertTrue(outcomes.single { it.first == THROWS }.second is ActionResult.Failure)
        engine.stop()
    }

    @Test
    fun `a cancellation that leaks out of an action stops only its own branch`() = runTest {
        val log = mutableListOf<Pair<String, Long>>()
        val outcomes = mutableListOf<Pair<String, ActionResult>>()
        val engine = engine(log, outcomes)

        engine.startRule(
            rule(
                ActionNode.Parallel(
                    listOf(
                        listOf(ComponentSpec(LEAKS), wait("a2", 100)),
                        listOf(wait("b1", 300)),
                    )
                ),
                wait("after", 0),
            )
        )
        advanceUntilIdle()

        // The leaking branch stops where it was; the other branch and the
        // action after the block are not touched.
        assertEquals(listOf("b1", "after"), log.map { it.first })
        val stopped = outcomes.single { it.first == LEAKS }.second as ActionResult.Failure
        assertTrue(stopped.reason, stopped.reason.contains("other branches continued"))
        engine.stop()
    }

    @Test
    fun `stopping the rule cancels every branch and nothing after the block runs`() = runTest {
        val log = mutableListOf<Pair<String, Long>>()
        val engine = engine(log)

        engine.startRule(
            rule(
                ActionNode.Parallel(
                    listOf(
                        listOf(wait("a", 5_000)),
                        listOf(wait("b", 5_000)),
                    )
                ),
                wait("after", 0),
            )
        )
        advanceTimeBy(1_000)
        engine.stopRule(RULE_ID)
        advanceUntilIdle()

        assertTrue("nothing finished: $log", log.isEmpty())
        assertFalse(RULE_ID in engine.runningRuleIds)
    }

    @Test
    fun `a branch reads what came before the block and never what a branch beside it made`() =
        runTest {
            val outcomes = mutableListOf<Pair<String, ActionResult>>()
            val read = mutableListOf<String>()
            val engine = engine(mutableListOf(), outcomes, read)

            engine.startRule(
                rule(
                    output("before"),
                    ActionNode.Parallel(
                        listOf(
                            listOf(output("in a")),
                            // Waits so the first branch is long finished, which
                            // proves the answer is not about timing.
                            listOf(wait("pause", 1_000), reader("{{action.value}}")),
                        )
                    ),
                )
            )
            advanceUntilIdle()

            // The first output action is `output`, the one in the first branch
            // is `output_2`. The reader in the second branch reads the one
            // before the block, not the one in its sibling branch.
            assertEquals(listOf("before"), read)
            engine.stop()
        }

    @Test
    fun `after the join every branch's outputs are readable, in branch order`() = runTest {
        val read = mutableListOf<String>()
        val engine = engine(mutableListOf(), mutableListOf(), read)

        engine.startRule(
            rule(
                ActionNode.Parallel(
                    listOf(
                        // The first branch finishes last in time. The plain
                        // {{action.value}} still reads the last branch in the
                        // list, because the order is fixed, not timed.
                        listOf(wait("slow", 2_000), output("first")),
                        listOf(output("second")),
                    )
                ),
                reader("{{output.value}}/{{output_2.value}}/{{action.value}}"),
            )
        )
        advanceUntilIdle()

        assertEquals(listOf("first/second/second"), read)
        engine.stop()
    }

    @Test
    fun `run values are shared with every branch and with the actions after the block`() = runTest {
        val read = mutableListOf<String>()
        val outcomes = mutableListOf<Pair<String, ActionResult>>()
        val engine = engine(mutableListOf(), outcomes, read)

        engine.startRule(
            rule(
                ActionNode.Parallel(
                    listOf(
                        listOf(writeLocal("from_a", "1")),
                        listOf(writeLocal("from_b", "2")),
                    )
                ),
                reader("{{local.from_a}}{{local.from_b}}"),
            )
        )
        advanceUntilIdle()

        assertEquals(outcomes.toString(), listOf("12"), read)
        engine.stop()
    }

    @Test
    fun `a rule run by another rule runs its parallel block too`() = runTest {
        val log = mutableListOf<Pair<String, Long>>()
        val engine = engine(log)

        val outcome = engine.runNow(
            rule(
                ActionNode.Parallel(listOf(listOf(wait("a", 100)), listOf(wait("b", 200)))),
                wait("after", 0),
            ),
            TriggerEvent(TRIGGER, 0),
        )

        assertEquals(RunRuleOutcome.Ran, outcome)
        assertEquals(listOf("a", "b", "after"), log.map { it.first })
    }

    @Test
    fun `a block with no branches runs nothing and the rule goes on`() = runTest {
        val log = mutableListOf<Pair<String, Long>>()
        val engine = engine(log)

        engine.startRule(rule(ActionNode.Parallel(emptyList()), wait("after", 0)))
        advanceUntilIdle()

        assertEquals(listOf("after"), log.map { it.first })
        engine.stop()
    }

    // --- fixtures -------------------------------------------------------------

    private fun TestScope.engine(
        log: MutableList<Pair<String, Long>>,
        outcomes: MutableList<Pair<String, ActionResult>> = mutableListOf(),
        read: MutableList<String> = mutableListOf(),
    ): TriggerEngine {
        val clock = { testScheduler.currentTime }
        val text = listOf(ConfigField.Text(key = "text", label = "Text", substitution = Substitution.TEXT))
        return TriggerEngine(
            registry = Registry(
                triggerFactories = listOf(OnceTriggerFactory()),
                actionFactories = listOf(
                    factory(WAIT) { config ->
                        delay(config.getValue("ms").toLong())
                        log += config.getValue("name") to clock()
                        ActionResult.Success()
                    },
                    factory(THROWS) { error("this action blew up") },
                    factory(LEAKS) { throw CancellationException("a timeout that leaked") },
                    factory(OUTPUT) { config -> ActionResult.Success(mapOf("value" to config.getValue("v"))) },
                    factory(WRITE_LOCAL) { config ->
                        currentCoroutineContext()[RunScope]!!.set(config.getValue("n"), config.getValue("v"))
                        ActionResult.Success()
                    },
                    factory(READ, text) { config ->
                        read += config.getValue("text")
                        ActionResult.Success()
                    },
                ),
            ),
            store = InMemoryVariableStore(),
            ruleStore = InMemoryRuleVariableStore(),
            scope = this,
            onOutcome = { _, _, type, result -> outcomes += type to result },
        )
    }

    private fun rule(vararg actions: ActionNode) = Rule(
        id = RULE_ID,
        name = "parallel",
        trigger = ComponentSpec(TRIGGER),
        actions = actions.toList(),
    )

    private fun wait(name: String, ms: Long) =
        ComponentSpec(WAIT, mapOf("name" to name, "ms" to ms.toString()))

    private fun output(value: String) = ComponentSpec(OUTPUT, mapOf("v" to value))

    private fun writeLocal(name: String, value: String) =
        ComponentSpec(WRITE_LOCAL, mapOf("n" to name, "v" to value))

    private fun reader(text: String) = ComponentSpec(READ, mapOf("text" to text))

    private fun factory(
        type: String,
        fields: List<ConfigField> = emptyList(),
        body: suspend (Map<String, String>) -> ActionResult,
    ): ActionFactory = object : ActionFactory {
        override val type = type
        override val configFields = fields
        override fun create(config: Map<String, String>): Action = object : Action {
            override suspend fun execute(event: TriggerEvent): ActionResult = body(config)
        }
    }

    private class OnceTriggerFactory : TriggerFactory {
        override val type = TRIGGER
        override fun create(config: Map<String, String>): Trigger = object : Trigger {
            override fun events(): Flow<TriggerEvent> = listOf(TriggerEvent(TRIGGER, 0)).asFlow()
        }
    }

    private companion object {
        const val RULE_ID = "rule-1"
        const val TRIGGER = "once"
        const val WAIT = "wait"
        const val THROWS = "throws"
        const val LEAKS = "leaks"
        const val OUTPUT = "output"
        const val WRITE_LOCAL = "write_local"
        const val READ = "read"
    }
}
