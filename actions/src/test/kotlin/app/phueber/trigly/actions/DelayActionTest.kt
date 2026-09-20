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
 * These tests pin what `DelayAction`'s own KDoc argues for. A long wait goes
 * through [AlarmScheduler.waitFor][app.phueber.trigly.core.AlarmScheduler.waitFor],
 * which survives the device suspending and cannot be accurate. A wait of
 * [DelayAction.AWAKE_WAIT_MAX_MILLIS] or less goes through
 * [WakeGuard.keepingAwake][app.phueber.trigly.core.WakeGuard.keepingAwake]
 * instead and never touches the scheduler, because the scheduler's own window
 * floor is five seconds and that is most of a short wait. Cancelling the
 * coroutine that runs this action, the way `TriggerEngine` cancels a disabled
 * rule's job, must stop the wait rather than let it fire late, and must give
 * the wake lock back on the way out: a lock that outlives its block is a
 * battery fault nothing else here would catch.
 *
 * Both fakes delay on virtual time, so a schedule is asserted rather than
 * tolerated.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DelayActionTest {

    private val event = TriggerEvent(triggerType = "interval", firedAtMillis = 1_000)

    // --- the wait itself ------------------------------------------------------

    @Test
    fun `it waits the configured duration through the scheduler, then succeeds`() = runTest {
        val scheduler = FakeAlarmScheduler()
        val action = DelayAction(scheduler, FakeWakeGuard(), durationMillis = 45_000)

        val result = action.execute(event)

        assertEquals(ActionResult.Success(), result)
        assertEquals(listOf(45_000L), scheduler.waitForCalls)
        assertEquals(45_000L, currentTime)
    }

    @Test
    fun `a cancelled wait never reports success`() = runTest {
        val scheduler = FakeAlarmScheduler()
        val action = DelayAction(scheduler, FakeWakeGuard(), durationMillis = 60_000)
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
        val action = DelayAction(scheduler, FakeWakeGuard(), durationMillis = 60_000)
        var result: ActionResult? = null

        val job = backgroundScope.launch { result = action.execute(event) }
        advanceTimeBy(10_000)
        job.cancel()
        advanceTimeBy(60_000)

        assertNull(result)
    }

    // --- which wait, and giving the lock back ----------------------------------

    @Test
    fun `a short wait holds the CPU and never asks the scheduler`() = runTest {
        val scheduler = FakeAlarmScheduler()
        val guard = FakeWakeGuard()
        val action = DelayAction(scheduler, guard, durationMillis = 3_000)

        val result = action.execute(event)

        assertEquals(ActionResult.Success(), result)
        // The reported fault in one assertion: three seconds must not become a
        // three-to-eight second alarm window.
        assertTrue("a three second wait must not be scheduled", scheduler.waitForCalls.isEmpty())
        assertEquals(1, guard.acquired)
        assertEquals(3_000L, currentTime)
    }

    @Test
    fun `a short wait takes exactly its duration, which the alarm path cannot`() = runTest {
        val action = DelayAction(FakeAlarmScheduler(), FakeWakeGuard(), durationMillis = 3_000)

        action.execute(event)

        // Not "about three seconds". The whole point of holding the CPU rather
        // than scheduling is that there is no window to land inside.
        assertEquals(3_000L, currentTime)
    }

    @Test
    fun `the boundary is inclusive, so exactly thirty seconds is still held`() = runTest {
        val scheduler = FakeAlarmScheduler()
        val guard = FakeWakeGuard()

        DelayAction(scheduler, guard, DelayAction.AWAKE_WAIT_MAX_MILLIS).execute(event)

        assertEquals(1, guard.acquired)
        assertTrue(scheduler.waitForCalls.isEmpty())
    }

    @Test
    fun `one millisecond past the boundary goes to the scheduler`() = runTest {
        val scheduler = FakeAlarmScheduler()
        val guard = FakeWakeGuard()

        DelayAction(scheduler, guard, DelayAction.AWAKE_WAIT_MAX_MILLIS + 1).execute(event)

        assertEquals(listOf(DelayAction.AWAKE_WAIT_MAX_MILLIS + 1), scheduler.waitForCalls)
        assertTrue("a long wait must not hold the CPU", guard.calls.isEmpty())
    }

    @Test
    fun `the lock is asked for slightly longer than the wait`() = runTest {
        val guard = FakeWakeGuard()

        DelayAction(FakeAlarmScheduler(), guard, durationMillis = 3_000).execute(event)

        // A backstop shorter than the work it backs would cut the wait short,
        // which is the fault this path exists to fix, returning silently.
        assertEquals(listOf(DelayAction.TYPE to 8_000L), guard.calls)
        assertEquals(8_000L, wakeTimeoutMillis(3_000))
    }

    @Test
    fun `a completed short wait gives the lock back`() = runTest {
        val guard = FakeWakeGuard()

        DelayAction(FakeAlarmScheduler(), guard, durationMillis = 3_000).execute(event)

        assertEquals(guard.acquired, guard.released)
        assertFalse("the lock outlived the wait", guard.isHeld)
    }

    @Test
    fun `cancelling a short wait gives the lock back`() = runTest {
        // The case that matters most. TriggerEngine.stopRule cancels a
        // disabled rule's job mid-wait, and a partial wake lock left held
        // after that would hold the CPU until the process dies, with nothing
        // on screen and nothing in a log to say why the battery went.
        val guard = FakeWakeGuard()
        val action = DelayAction(FakeAlarmScheduler(), guard, durationMillis = 30_000)

        val job = backgroundScope.launch { action.execute(event) }
        advanceTimeBy(1_000)
        job.cancel()
        job.join()

        assertEquals(1, guard.acquired)
        assertEquals("a cancelled wait must release", guard.acquired, guard.released)
        assertFalse(guard.isHeld)
    }

    @Test
    fun `two short waits in one rule are two separate holds`() = runTest {
        // A rule runs its actions one at a time in one coroutine, so two waits
        // can never overlap. Each takes and gives back its own lock, which is
        // what makes a lock per wait safe rather than something that needs to
        // be counted.
        val guard = FakeWakeGuard()
        val action = DelayAction(FakeAlarmScheduler(), guard, durationMillis = 2_000)

        action.execute(event)
        action.execute(event)

        assertEquals(2, guard.acquired)
        assertEquals(2, guard.released)
        assertFalse(guard.isHeld)
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
        val factory = DelayActionFactory(FakeAlarmScheduler(), FakeWakeGuard())

        assertThrows(IllegalArgumentException::class.java) { factory.create(emptyMap()) }
    }

    @Test
    fun `the factory builds an action that waits the configured duration`() = runTest {
        val scheduler = FakeAlarmScheduler()
        val guard = FakeWakeGuard()
        val factory = DelayActionFactory(scheduler, guard)

        val action = factory.create(mapOf(DelayAction.CONFIG_DURATION_MILLIS to "120000"))
        val result = action.execute(event)

        assertEquals(ActionResult.Success(), result)
        assertEquals(listOf(120_000L), scheduler.waitForCalls)
        assertTrue("a two minute wait belongs on the scheduler", guard.calls.isEmpty())
    }

    @Test
    fun `the warning explains the queue and the kill risk`() {
        val warning = DelayActionFactory(FakeAlarmScheduler(), FakeWakeGuard()).warning

        assertFalse("must not read as empty documentation", warning.isBlank())
        assertTrue(warning.contains("wait"))
    }

    @Test
    fun `the warning states which waits are accurate and which drift`() {
        // The old text said only that "a long wait can be off by a few
        // minutes", which let a reader infer that a short one was exact. It
        // was not: every wait went through the scheduler's five second window
        // floor. Now that a short wait really is held to the second, the
        // warning has to say so, and say where the line is.
        val warning = DelayActionFactory(FakeAlarmScheduler(), FakeWakeGuard()).warning

        assertTrue("the promise needs a number a person can act on", warning.contains("30 seconds"))
        assertTrue("and it has to say the longer case still drifts", warning.contains("few minutes"))
    }
}
