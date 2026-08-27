package app.phueber.trigly.actions

import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.ComponentSpec
import app.phueber.trigly.core.InMemoryRuleRepository
import app.phueber.trigly.core.Rule
import app.phueber.trigly.core.RuleRunner
import app.phueber.trigly.core.RunRuleOutcome
import app.phueber.trigly.core.TriggerEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `run_rule`'s own logic: which rule it asks for, whether the condition lets
 * it through, and how it turns what [RuleRunner] answers into an
 * [ActionResult].
 *
 * The recursion guard itself, the self-call refusal and the chain depth cap,
 * belongs to `TriggerEngine.runNow` and is tested there: this class only
 * ever sees a [RuleRunner] as an interface, and a fake standing in for it
 * cannot exercise a guard it does not implement. What this file pins is that
 * `run_rule` calls that interface at all, and reports what it says honestly.
 */
class RunRuleActionTest {

    private val event = TriggerEvent(triggerType = "interval", firedAtMillis = 1_000)

    private fun rule(id: String) = Rule(
        id = id,
        name = "Rule $id",
        trigger = ComponentSpec("interval", mapOf("periodMillis" to "60000")),
        actions = listOf(ComponentSpec("toast", mapOf("text" to "hi"))),
    )

    /** Records what it was asked to run, and always answers [outcome]. */
    private class RecordingRunner(private val outcome: RunRuleOutcome) : RuleRunner {
        val asked = mutableListOf<Rule>()

        override suspend fun runNow(rule: Rule, causingEvent: TriggerEvent): RunRuleOutcome {
            asked += rule
            return outcome
        }
    }

    @Test
    fun `the target runs when the condition holds`() = runTest {
        val repository = InMemoryRuleRepository(listOf(rule("target")))
        val runner = RecordingRunner(RunRuleOutcome.Ran)
        val action = RunRuleAction(repository, runner, "target", "1 == 1")

        val result = action.execute(event)

        val ranYes = mapOf(RunRuleAction.OUTPUT_RAN to RunRuleAction.RAN_YES)
        assertEquals(ActionResult.Success(outputs = ranYes), result)
        assertEquals(listOf("target"), runner.asked.map { it.id })
    }

    @Test
    fun `the target does not run when the condition is false, and that is a success`() =
        runTest {
            val repository = InMemoryRuleRepository(listOf(rule("target")))
            val runner = RecordingRunner(RunRuleOutcome.Ran)
            val action = RunRuleAction(repository, runner, "target", "1 == 2")

            val result = action.execute(event)

            val ranNo = mapOf(RunRuleAction.OUTPUT_RAN to RunRuleAction.RAN_NO)
            assertEquals(ActionResult.Success(outputs = ranNo), result)
            assertTrue(
                "the runner must not be asked when the condition is false",
                runner.asked.isEmpty(),
            )
        }

    @Test
    fun `a blank condition always runs the target`() = runTest {
        val repository = InMemoryRuleRepository(listOf(rule("target")))
        val runner = RecordingRunner(RunRuleOutcome.Ran)

        val blank = RunRuleAction(repository, runner, "target", "")
        val absent = RunRuleAction(repository, runner, "target", null)

        blank.execute(event)
        absent.execute(event)

        assertEquals(listOf("target", "target"), runner.asked.map { it.id })
    }

    @Test
    fun `an expression that does not evaluate fails, and the reason names the problem`() =
        runTest {
            val repository = InMemoryRuleRepository(listOf(rule("target")))
            val runner = RecordingRunner(RunRuleOutcome.Ran)
            // Unmatched parenthesis: a typo, not a false condition.
            val action = RunRuleAction(repository, runner, "target", "(1 == 1")

            val result = action.execute(event)

            assertTrue("expected a failure, got $result", result is ActionResult.Failure)
            assertTrue(
                "the runner must not be asked when the condition is broken",
                runner.asked.isEmpty(),
            )
        }

    @Test
    fun `a rule id that no longer exists fails with a readable reason`() = runTest {
        val repository = InMemoryRuleRepository(listOf(rule("other")))
        val runner = RecordingRunner(RunRuleOutcome.Ran)
        val action = RunRuleAction(repository, runner, "gone", null)

        val result = action.execute(event)

        assertTrue("expected a failure, got $result", result is ActionResult.Failure)
        assertTrue(
            "the reason should name the id: ${(result as ActionResult.Failure).reason}",
            result.reason.contains("gone"),
        )
        assertTrue(runner.asked.isEmpty())
    }

    @Test
    fun `no rule chosen fails rather than guessing one`() = runTest {
        val repository = InMemoryRuleRepository(listOf(rule("target")))
        val runner = RecordingRunner(RunRuleOutcome.Ran)

        val blank = RunRuleAction(repository, runner, "  ", null).execute(event)
        val absent = RunRuleAction(repository, runner, null, null).execute(event)

        assertTrue(blank is ActionResult.Failure)
        assertTrue(absent is ActionResult.Failure)
        assertTrue(runner.asked.isEmpty())
    }

    @Test
    fun `a refusal from the engine becomes this action's own failure`() = runTest {
        val repository = InMemoryRuleRepository(listOf(rule("target")))
        val refusal = RunRuleOutcome.Refused("This chain of run-rule calls is too deep.")
        val runner = RecordingRunner(refusal)
        val action = RunRuleAction(repository, runner, "target", null)

        val result = action.execute(event)

        assertTrue("expected a failure, got $result", result is ActionResult.Failure)
        val reason = (result as ActionResult.Failure).reason
        assertTrue(
            "the reason should be the one the engine gave: $reason",
            reason.contains("too deep"),
        )
    }
}
