package app.phueber.trigly.ui

import app.phueber.trigly.core.IntentTargetCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [describeIntentTargetCheck] is pure logic over a closed enum, so this runs on
 * the JVM rather than joining `RuleEditorViewModelTest` as an instrumented spec.
 * That is the same split `RuleDraftTest`'s own KDoc explains for the save decision.
 *
 * The property that matters most is not any one message's exact wording, it is
 * that the four read as four different things: `docs/actions.md` warns that a
 * careless version of this control reports [IntentTargetCheck.WOULD_NOT_RESOLVE]
 * and [IntentTargetCheck.HIDDEN_BY_VISIBILITY] the same way, which would make a
 * "Trigly cannot tell" read as "this will not work" and get a working rule
 * deleted.
 */
class IntentTargetCheckMessageTest {

    @Test
    fun `every answer produces a distinct message`() {
        val messages = IntentTargetCheck.entries.map { describeIntentTargetCheck(it) }

        assertEquals(IntentTargetCheck.entries.size, messages.distinct().size)
    }

    @Test
    fun `an app that would answer reads as working`() {
        val message = describeIntentTargetCheck(IntentTargetCheck.WOULD_RESOLVE)

        assertTrue(message, message.contains("would answer"))
        // Must not carry either the "will not work" verdict or the
        // "cannot see" disclaimer that belong to the other two answers.
        assertTrue(message, "will not work" !in message)
        assertTrue(message, "cannot see" !in message)
    }

    /**
     * The one case `docs/actions.md` names as the real danger: this must say
     * outright that the rule will not work, and it must say so in words that
     * cannot be confused with [IntentTargetCheck.HIDDEN_BY_VISIBILITY]'s
     * "no answer" message.
     */
    @Test
    fun `no app answering reads as a real failure, not a shrug`() {
        val message = describeIntentTargetCheck(IntentTargetCheck.WOULD_NOT_RESOLVE)

        assertTrue(message, message.contains("No app"))
        assertTrue(message, message.contains("will not work"))
        assertTrue(message, "cannot see" !in message)
    }

    /**
     * The other half of the pair: this must say Trigly could not see the
     * answer, and it must not claim the rule is broken, since it may not be.
     */
    @Test
    fun `hidden by visibility reads as no answer, not as a failure`() {
        val message = describeIntentTargetCheck(IntentTargetCheck.HIDDEN_BY_VISIBILITY)

        assertTrue(message, message.contains("cannot see"))
        assertTrue(message, message.contains("may still work"))
        assertTrue(message, "will not work" !in message)
    }

    /**
     * Trigly's own refusal, not a platform finding. The message has to say
     * whose decision this is and what to change, not report a "no" that
     * sounds like it came from Android.
     */
    @Test
    fun `a self target is named as trigly's own refusal, with what to change`() {
        val message = describeIntentTargetCheck(IntentTargetCheck.REFUSED_SELF_TARGET)

        assertTrue(message, message.contains("Trigly refuses"))
        assertTrue(message, message.contains("Change the app or the class name"))
    }

    @Test
    fun `neither resolve message is mistaken for the other`() {
        val resolves = describeIntentTargetCheck(IntentTargetCheck.WOULD_RESOLVE)
        val doesNot = describeIntentTargetCheck(IntentTargetCheck.WOULD_NOT_RESOLVE)

        assertNotEquals(resolves, doesNot)
    }
}
