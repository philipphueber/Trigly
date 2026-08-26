package app.phueber.trigly.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * When a fault is remembered and when it is dropped.
 *
 * The clearing rules are the whole substance here. A record that outlives the
 * problem accuses a rule that works, and a record cleared too eagerly hides a
 * rule that does not.
 */
class RuleFaultLogTest {

    private val log = RuleFaultLog()

    private fun actionFailed(actionType: String, reason: String) =
        RuleFault(RuleFault.Kind.ACTION_FAILED, reason, actionType)

    @Test
    fun `a failure is recorded against its rule`() {
        log.failed("rule-1", "toast", "No text to show.")

        assertEquals(actionFailed("toast", "No text to show."), log.faults.value["rule-1"])
    }

    @Test
    fun `a later failure replaces an earlier one`() {
        log.failed("rule-1", "toast", "first")
        log.failed("rule-1", "vibrate", "second")

        assertEquals(actionFailed("vibrate", "second"), log.faults.value["rule-1"])
    }

    @Test
    fun `a success by the same action clears the record`() {
        log.failed("rule-1", "toast", "No text to show.")

        log.succeeded("rule-1", "toast")

        assertNull(log.faults.value["rule-1"])
    }

    /**
     * The case an unguarded clear would get wrong. Two actions, the first fails
     * and the second works, and the rule is doing half its job. A success that
     * cleared any record would report the rule as healthy.
     */
    @Test
    fun `a success by a different action leaves the record standing`() {
        log.failed("rule-1", "open_app", "Nothing on this device handles that.")

        log.succeeded("rule-1", "toast")

        assertEquals(
            actionFailed("open_app", "Nothing on this device handles that."),
            log.faults.value["rule-1"],
        )
    }

    @Test
    fun `rules do not see each other's failures`() {
        log.failed("rule-1", "toast", "first")
        log.failed("rule-2", "vibrate", "second")

        log.succeeded("rule-1", "toast")

        assertNull(log.faults.value["rule-1"])
        assertEquals(actionFailed("vibrate", "second"), log.faults.value["rule-2"])
    }

    @Test
    fun `forgetting a rule drops its record whatever failed`() {
        log.failed("rule-1", "toast", "first")

        log.forget("rule-1")

        assertNull(log.faults.value["rule-1"])
    }

    @Test
    fun `a success for a rule with no record changes nothing`() {
        log.succeeded("rule-1", "toast")

        assertEquals(emptyMap<String, RuleFault>(), log.faults.value)
    }

    @Test
    fun `a rule that could not be built is recorded with no action named`() {
        log.couldNotStart("rule-1", "No trigger factory for type 'from_the_future'.")

        assertEquals(
            RuleFault(
                RuleFault.Kind.COULD_NOT_START,
                "No trigger factory for type 'from_the_future'.",
            ),
            log.faults.value["rule-1"],
        )
    }

    /**
     * What an edit that fixes the config does. The rule starts, and the report
     * has to go then rather than when a trigger next fires, which may be days.
     */
    @Test
    fun `a rule that starts clears its start failure`() {
        log.couldNotStart("rule-1", "cannot build")

        log.started("rule-1")

        assertNull(log.faults.value["rule-1"])
    }

    /**
     * The reason [RuleFaultLog.started] is narrow. Starting says nothing about
     * an action that failed on an earlier run, and every sync calls it for every
     * running rule, so a broad clear would wipe those records constantly.
     */
    @Test
    fun `a rule that starts leaves a failed action standing`() {
        log.failed("rule-1", "toast", "No text to show.")

        log.started("rule-1")

        assertEquals(actionFailed("toast", "No text to show."), log.faults.value["rule-1"])
    }

    @Test
    fun `a rule that starts leaves an undecided run standing`() {
        log.couldNotDecide("rule-1", "could not read Is in an area")

        log.started("rule-1")

        assertEquals(
            RuleFault(RuleFault.Kind.UNDECIDED, "could not read Is in an area"),
            log.faults.value["rule-1"],
        )
    }

    /**
     * Any action running is proof that a rule which "did not run at all" record
     * is stale, whichever action it was. Only an action failure is tied to the
     * action that reports success.
     */
    @Test
    fun `any success clears a record that no action was reached`() {
        log.couldNotStart("rule-1", "cannot build")
        log.succeeded("rule-1", "toast")
        assertNull(log.faults.value["rule-1"])

        log.couldNotDecide("rule-2", "could not read")
        log.succeeded("rule-2", "vibrate")
        assertNull(log.faults.value["rule-2"])
    }
}
