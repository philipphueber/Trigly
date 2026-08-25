package app.phueber.trigly.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * When a failure is remembered and when it is dropped.
 *
 * The clearing rules are the whole substance here. A record that outlives the
 * problem accuses a rule that works, and a record cleared too eagerly hides a
 * rule that does not.
 */
class ActionFailureLogTest {

    private val log = ActionFailureLog()

    @Test
    fun `a failure is recorded against its rule`() {
        log.failed("rule-1", "toast", "No text to show.")

        assertEquals(ActionFailure("toast", "No text to show."), log.failures.value["rule-1"])
    }

    @Test
    fun `a later failure replaces an earlier one`() {
        log.failed("rule-1", "toast", "first")
        log.failed("rule-1", "vibrate", "second")

        assertEquals(ActionFailure("vibrate", "second"), log.failures.value["rule-1"])
    }

    @Test
    fun `a success by the same action clears the record`() {
        log.failed("rule-1", "toast", "No text to show.")

        log.succeeded("rule-1", "toast")

        assertNull(log.failures.value["rule-1"])
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
            ActionFailure("open_app", "Nothing on this device handles that."),
            log.failures.value["rule-1"],
        )
    }

    @Test
    fun `rules do not see each other's failures`() {
        log.failed("rule-1", "toast", "first")
        log.failed("rule-2", "vibrate", "second")

        log.succeeded("rule-1", "toast")

        assertNull(log.failures.value["rule-1"])
        assertEquals(ActionFailure("vibrate", "second"), log.failures.value["rule-2"])
    }

    @Test
    fun `forgetting a rule drops its record whatever failed`() {
        log.failed("rule-1", "toast", "first")

        log.forget("rule-1")

        assertNull(log.failures.value["rule-1"])
    }

    @Test
    fun `a success for a rule with no record changes nothing`() {
        log.succeeded("rule-1", "toast")

        assertEquals(emptyMap<String, ActionFailure>(), log.failures.value)
    }
}
