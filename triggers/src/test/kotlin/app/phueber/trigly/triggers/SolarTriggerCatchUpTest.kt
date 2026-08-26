package app.phueber.trigly.triggers

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneId

/**
 * The one behaviour [AlarmWakeEvents] exists to give [SolarTrigger]: once a
 * durable wait's alarm fired recently, a fresh collection searches from a
 * little before "now" instead of from "now" itself, so an occurrence that
 * was already due is found rather than skipped in favour of tomorrow's.
 * Without this, a process that dies mid-wait and comes back a couple of
 * minutes after the true sunset would silently miss it, which is
 * `docs/todo.md`'s T17.
 *
 * [SolarTriggerSchedulingTest] covers the ordinary case, where collection
 * always starts before the computed instant; this file is only about what
 * changes when it does not.
 *
 * [AlarmWakeEvents] is a process-wide object; each test clears it so one
 * case cannot leak a recorded wake into the next.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SolarTriggerCatchUpTest {

    private val zone = ZoneId.of("Europe/Berlin")
    private val start = 1_700_000_000_000L // an arbitrary but fixed instant

    @Before
    fun setUp() = AlarmWakeEvents.clear()

    @After
    fun tearDown() = AlarmWakeEvents.clear()

    private fun sunriseTrigger(scheduler: FakeAlarmScheduler, now: () -> Long) = SolarTrigger(
        latitude = 52.52,
        longitude = 13.405,
        event = SolarEvent.SUNRISE,
        scheduler = scheduler,
        zone = zone,
        now = now,
    )

    @Test
    fun `a durable wake pending recovers an occurrence already due`() = runTest {
        fun now() = start + currentTime

        val todaySunrise = checkNotNull(
            sunriseTrigger(FakeAlarmScheduler(), ::now).nextOccurrenceMillis(now())
        ) { "Berlin has a sunrise; the search window is not what this test is about" }

        // The process comes back a couple of minutes after the very sunrise
        // it was durably woken for, well inside the catch-up window.
        val wokenAt = todaySunrise + 2 * 60_000L
        AlarmWakeEvents.record(atMillis = wokenAt)

        val scheduler = FakeAlarmScheduler(now = { wokenAt })
        val trigger = sunriseTrigger(scheduler) { wokenAt }

        trigger.events().take(1).toList()

        assertEquals(
            "the sunrise already due must be found, not skipped for tomorrow's",
            todaySunrise,
            scheduler.waitUntilDurableCalls.single(),
        )
    }

    @Test
    fun `with nothing pending a late cold start skips to the next occurrence`() = runTest {
        fun now() = start + currentTime

        val todaySunrise = checkNotNull(
            sunriseTrigger(FakeAlarmScheduler(), ::now).nextOccurrenceMillis(now())
        ) { "Berlin has a sunrise; the search window is not what this test is about" }

        // No AlarmWakeEvents.record: an ordinary rule enabled by hand two
        // minutes after some unrelated sunrise, not a fresh collection woken
        // by this trigger's own durable wait.
        val enabledAt = todaySunrise + 2 * 60_000L
        val scheduler = FakeAlarmScheduler(now = { enabledAt })
        val trigger = sunriseTrigger(scheduler) { enabledAt }

        trigger.events().take(1).toList()

        assertTrue(
            "an ordinary cold start must not fire for a sunrise already two minutes gone",
            scheduler.waitUntilDurableCalls.single() > todaySunrise,
        )
    }
}
