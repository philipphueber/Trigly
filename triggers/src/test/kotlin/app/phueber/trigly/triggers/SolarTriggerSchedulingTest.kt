package app.phueber.trigly.triggers

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * Proves [SolarTrigger.events] waits for the computed sunrise instant, and
 * then for the short anti-double-fire buffer, through the scheduler port
 * rather than a plain coroutine `delay`. This is the one place T1 changes in
 * this class, per its own KDoc; the astronomy behind the computed instant is
 * [SolarTimeTest]'s job, not this file's.
 *
 * [now] is sourced from `runTest`'s own virtual clock rather than a fixed
 * value, so the second loop iteration sees a "now" that has genuinely moved
 * past the first fire, the same way it would on a device once the scheduler
 * actually waits.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SolarTriggerSchedulingTest {

    private val zone = ZoneId.of("Europe/Berlin")
    private val start = 1_700_000_000_000L // an arbitrary but fixed instant

    @Test
    fun `waits until the computed instant, then a short buffer, before the next one`() = runTest {
        fun now() = start + currentTime

        val scheduler = FakeAlarmScheduler(now = ::now)
        val trigger = SolarTrigger(
            latitude = 52.52,
            longitude = 13.405,
            event = SolarEvent.SUNRISE,
            scheduler = scheduler,
            zone = zone,
            now = ::now,
        )
        val firstFireAt = checkNotNull(trigger.nextOccurrenceMillis(now())) {
            "Berlin has a sunrise; the search window is not what this test is about"
        }

        val events = trigger.events().take(2).toList()

        assertEquals(2, events.size)
        assertEquals(listOf(SolarTrigger.TYPE, SolarTrigger.TYPE), events.map { it.triggerType })
        assertEquals(listOf(1_000L), scheduler.waitForCalls)
        assertEquals(firstFireAt, scheduler.waitUntilCalls[0])
        assertTrue(
            "the second occurrence must be strictly after the first",
            scheduler.waitUntilCalls[1] > scheduler.waitUntilCalls[0],
        )
    }
}
