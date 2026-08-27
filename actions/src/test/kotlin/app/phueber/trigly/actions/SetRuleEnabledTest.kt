package app.phueber.trigly.actions

import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.ComponentSpec
import app.phueber.trigly.core.InMemoryRuleRepository
import app.phueber.trigly.core.Rule
import app.phueber.trigly.core.TriggerEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Switching a rule from inside a rule.
 *
 * Nothing in the engine changes for this to work — `EngineService` collects the
 * store and `TriggerEngine.sync` starts and stops against the `enabled` flag, so
 * writing the flag *is* the mechanism. What these tests pin is the arithmetic
 * around that write: which way each mode goes, that "already there" writes
 * nothing, and that a rule which has since been deleted fails loudly instead of
 * silently doing nothing.
 */
class SetRuleEnabledTest {

    private val event = TriggerEvent(triggerType = "interval", firedAtMillis = 1_000)

    private fun rule(id: String, enabled: Boolean) = Rule(
        id = id,
        name = "Rule $id",
        trigger = ComponentSpec("interval", mapOf("periodMillis" to "60000")),
        actions = listOf(ComponentSpec("toast", mapOf("text" to "hi"))),
        enabled = enabled,
    )

    private suspend fun InMemoryRuleRepository.enabledOf(id: String): Boolean =
        rules().first().first { it.id == id }.enabled

    /** The success outcome for the rule ending up on, or off. */
    private val onOutcome = ActionResult.Success(
        outputs = mapOf(SetRuleEnabledAction.OUTPUT_ENABLED to SetRuleEnabledAction.ENABLED)
    )
    private val offOutcome = ActionResult.Success(
        outputs = mapOf(SetRuleEnabledAction.OUTPUT_ENABLED to SetRuleEnabledAction.DISABLED)
    )

    // --- the decision, on its own ---------------------------------------------

    @Test
    fun `enable turns an off rule on, and leaves an on rule alone`() {
        assertEquals(true, RuleSwitch.ENABLE.applyTo(enabled = false))
        assertEquals(null, RuleSwitch.ENABLE.applyTo(enabled = true))
    }

    @Test
    fun `disable turns an on rule off, and leaves an off rule alone`() {
        assertEquals(false, RuleSwitch.DISABLE.applyTo(enabled = true))
        assertEquals(null, RuleSwitch.DISABLE.applyTo(enabled = false))
    }

    @Test
    fun `toggle always changes something`() {
        assertEquals(false, RuleSwitch.TOGGLE.applyTo(enabled = true))
        assertEquals(true, RuleSwitch.TOGGLE.applyTo(enabled = false))
    }

    @Test
    fun `an unknown mode is refused with the offending value`() {
        val thrown = assertThrows(IllegalStateException::class.java) {
            RuleSwitch.parse("invert")
        }
        assertTrue(thrown.message.orEmpty().contains("invert"))
    }

    // --- and against a store --------------------------------------------------

    @Test
    fun `disabling writes the flag the engine reads`() = runTest {
        val repository = InMemoryRuleRepository(listOf(rule("a", enabled = true)))

        val result = SetRuleEnabledAction(repository, "a", RuleSwitch.DISABLE).execute(event)

        assertEquals(offOutcome, result)
        assertFalse(repository.enabledOf("a"))
    }

    @Test
    fun `enabling an already-enabled rule succeeds without writing`() = runTest {
        // The write matters: it would churn the engine into stopping and
        // restarting a rule that was already running. "Make sure this is on"
        // must also not *fail* when it already was.
        val repository = InMemoryRuleRepository(listOf(rule("a", enabled = true)))
        val before = repository.rules().first()

        val result = SetRuleEnabledAction(repository, "a", RuleSwitch.ENABLE).execute(event)

        // Idempotent means no write, not "no answer". The output still says
        // the rule's true resulting state, which is what it already was.
        assertEquals(onOutcome, result)
        assertEquals("nothing should have been written", before, repository.rules().first())
    }

    /**
     * This is the case the output feature exists for: `TOGGLE` is "flip it",
     * and the resulting state is the one thing nothing else in the rule can
     * know, in either direction.
     */
    @Test
    fun `toggle reports the resulting state, both directions`() = runTest {
        val repository = InMemoryRuleRepository(
            listOf(rule("on", enabled = true), rule("off", enabled = false))
        )

        val fromOn = SetRuleEnabledAction(repository, "on", RuleSwitch.TOGGLE).execute(event)
        val fromOff = SetRuleEnabledAction(repository, "off", RuleSwitch.TOGGLE).execute(event)

        assertEquals(offOutcome, fromOn)
        assertEquals(onOutcome, fromOff)
        assertFalse(repository.enabledOf("on"))
        assertTrue(repository.enabledOf("off"))
    }

    @Test
    fun `it switches only the rule it was given`() = runTest {
        val repository = InMemoryRuleRepository(
            listOf(rule("a", enabled = true), rule("b", enabled = true))
        )

        SetRuleEnabledAction(repository, "a", RuleSwitch.DISABLE).execute(event)

        assertFalse(repository.enabledOf("a"))
        assertTrue("the other rule must be untouched", repository.enabledOf("b"))
    }

    @Test
    fun `a rule that no longer exists fails and names the id`() = runTest {
        // The rule was deleted after this action was set up. Silently doing
        // nothing is the outcome that gets reported as "the automation stopped
        // working" months later.
        val repository = InMemoryRuleRepository(listOf(rule("a", enabled = true)))

        val result = SetRuleEnabledAction(repository, "gone", RuleSwitch.ENABLE).execute(event)

        assertTrue("expected a failure, got $result", result is ActionResult.Failure)
        assertTrue(
            "the reason should name the id: ${(result as ActionResult.Failure).reason}",
            result.reason.contains("gone"),
        )
    }

    @Test
    fun `no rule chosen fails rather than guessing one`() = runTest {
        val repository = InMemoryRuleRepository(listOf(rule("a", enabled = true)))

        val blank = SetRuleEnabledAction(repository, "  ", RuleSwitch.DISABLE).execute(event)
        val absent = SetRuleEnabledAction(repository, null, RuleSwitch.DISABLE).execute(event)

        assertTrue(blank is ActionResult.Failure)
        assertTrue(absent is ActionResult.Failure)
        assertTrue("nothing should have changed", repository.enabledOf("a"))
    }

    @Test
    fun `a rule can turn itself off`() = runTest {
        // The one-shot: fire, then disable yourself. Worth a test because it is
        // the case the action exists for, and because the engine cancelling the
        // running rule mid-flight is the documented consequence.
        val repository = InMemoryRuleRepository(listOf(rule("self", enabled = true)))

        val result = SetRuleEnabledAction(repository, "self", RuleSwitch.DISABLE).execute(event)

        assertEquals(offOutcome, result)
        assertFalse(repository.enabledOf("self"))
    }
}
