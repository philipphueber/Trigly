package app.phueber.trigly.triggers

import app.phueber.trigly.core.TriggerEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Proves [IntervalTrigger.events] asks the scheduler port for the wait,
 * naming the configured period every time, rather than counting it with a
 * plain coroutine `delay` the way it used to. That is the entire change T1
 * makes to this class: the emitted event's shape does not change.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IntervalTriggerTest {

    @Test
    fun `asks the scheduler for periodMillis on every tick`() = runTest {
        val scheduler = FakeAlarmScheduler()
        val trigger = IntervalTrigger(periodMillis = 30_000L, scheduler = scheduler, now = { 42L })

        val events = trigger.events().take(3).toList()

        assertEquals(listOf(30_000L, 30_000L, 30_000L), scheduler.waitForCalls)
        assertEquals(listOf(42L, 42L, 42L), events.map(TriggerEvent::firedAtMillis))
    }

    @Test
    fun `each emitted event carries the interval type`() = runTest {
        val trigger = IntervalTrigger(periodMillis = 1_000L, scheduler = FakeAlarmScheduler())

        val event = trigger.events().take(1).toList().single()

        assertEquals(IntervalTrigger.TYPE, event.triggerType)
    }
}
