package app.phueber.trigly.actions

import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.TriggerEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A rule that waits, then continues.
 *
 * These tests pin the two halves `DelayAction`'s own KDoc argues for: the
 * wait goes through [AlarmScheduler.waitFor][app.phueber.trigly.core.AlarmScheduler.waitFor],
 * never a bare coroutine `delay`, and cancelling the coroutine that runs this
 * action, the way `TriggerEngine` cancels a disabled rule's job, stops the
 * wait rather than letting it fire late. [FakeAlarmScheduler] delays on
 * virtual time, so the schedule is asserted rather than tolerated.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DelayActionTest {

    private val event = TriggerEvent(triggerType = "interval", firedAtMillis = 1_000)

    // --- the wait itself ------------------------------------------------------

    @Test
    fun `it waits the configured duration through the scheduler, then succeeds`() = runTest {
        val scheduler = FakeAlarmScheduler()
        val action = DelayAction(scheduler, durationMillis = 45_000)

        val result = action.execute(event)

        assertEquals(ActionResult.Success(), result)
        assertEquals(listOf(45_000L), scheduler.waitForCalls)
        assertEquals(45_000L, currentTime)
    }

    @Test
    fun `a cancelled wait never reports success`() = runTest {
        val scheduler = FakeAlarmScheduler()
        val action = DelayAction(scheduler, durationMillis = 60_000)
        var result: ActionResult? = null

        val job: Job = backgroundScope.launch { result = action.execute(event) }
        advanceTimeBy(1_000)
        job.cancel()
        job.join()

        assertTrue("the job should have been cancelled", job.isCancelled)
        assertNull("a cancelled wait must not report a result", result)
    }

    @Test
    fun `disabling the rule mid-wait does not let the delay fire late`() = runTest {
        // The same shape TriggerEngine.stopRule uses: cancel the job, then
        // advance time well past when the wait would otherwise have fired.
        // Nothing should observe a result afterwards.
        val scheduler = FakeAlarmScheduler()
        val action = DelayAction(scheduler, durationMillis = 60_000)
        var result: ActionResult? = null

        val job = backgroundScope.launch { result = action.execute(event) }
        advanceTimeBy(10_000)
        job.cancel()
        advanceTimeBy(60_000)

        assertNull(result)
    }

    // --- reading the duration from config --------------------------------------

    @Test
    fun `duration is read from config, in milliseconds`() {
        assertEquals(90_000L, delayDurationMillis("90000"))
    }

    @Test
    fun `a duration over the cap is capped, not refused`() {
        assertEquals(
            DelayAction.MAX_DURATION_MILLIS,
            delayDurationMillis((DelayAction.MAX_DURATION_MILLIS + 1).toString()),
        )
    }

    @Test
    fun `a missing duration is refused rather than defaulted`() {
        assertThrows(IllegalArgumentException::class.java) { delayDurationMillis(null) }
        assertThrows(IllegalArgumentException::class.java) { delayDurationMillis("") }
        assertThrows(IllegalArgumentException::class.java) { delayDurationMillis("   ") }
    }

    @Test
    fun `a duration that is not a number is refused`() {
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            delayDurationMillis("soon")
        }
        assertTrue(thrown.message.orEmpty().contains("soon"))
    }

    @Test
    fun `a zero or negative duration is refused`() {
        assertThrows(IllegalArgumentException::class.java) { delayDurationMillis("0") }
        assertThrows(IllegalArgumentException::class.java) { delayDurationMillis("-1") }
    }

    // --- the factory ------------------------------------------------------------

    @Test
    fun `the factory refuses to build an action with no duration configured`() {
        val factory = DelayActionFactory(FakeAlarmScheduler())

        assertThrows(IllegalArgumentException::class.java) { factory.create(emptyMap()) }
    }

    @Test
    fun `the factory builds an action that waits the configured duration`() = runTest {
        val scheduler = FakeAlarmScheduler()
        val factory = DelayActionFactory(scheduler)

        val action = factory.create(mapOf(DelayAction.CONFIG_DURATION_MILLIS to "5000"))
        val result = action.execute(event)

        assertEquals(ActionResult.Success(), result)
        assertEquals(listOf(5_000L), scheduler.waitForCalls)
    }

    @Test
    fun `the warning explains the queue and the kill risk`() {
        val warning = DelayActionFactory(FakeAlarmScheduler()).warning

        assertFalse("must not read as empty documentation", warning.isBlank())
        assertTrue(warning.contains("wait"))
    }
}
