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
 * plain coroutine `delay` the way it used to. T1 is what made it ask the
 * port at all; T17 is why it asks for the *durable* half of the port
 * ([AlarmScheduler.waitForDurable], not [AlarmScheduler.waitFor]), since an
 * interval rule's whole point is lost if its wait dies with this process.
 * Neither change touches the emitted event's shape.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IntervalTriggerTest {

    @Test
    fun `asks the scheduler's durable wait for periodMillis on every tick`() = runTest {
        val scheduler = FakeAlarmScheduler()
        val trigger = IntervalTrigger(periodMillis = 30_000L, scheduler = scheduler, now = { 42L })

        val events = trigger.events().take(3).toList()

        assertEquals(listOf(30_000L, 30_000L, 30_000L), scheduler.waitForDurableCalls)
        assertEquals(emptyList<Long>(), scheduler.waitForCalls)
        assertEquals(listOf(42L, 42L, 42L), events.map(TriggerEvent::firedAtMillis))
    }

    @Test
    fun `each emitted event carries the interval type`() = runTest {
        val trigger = IntervalTrigger(periodMillis = 1_000L, scheduler = FakeAlarmScheduler())

        val event = trigger.events().take(1).toList().single()

        assertEquals(IntervalTrigger.TYPE, event.triggerType)
    }
}
