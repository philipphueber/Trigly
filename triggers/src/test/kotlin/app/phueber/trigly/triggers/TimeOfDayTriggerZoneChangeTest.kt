package app.phueber.trigly.triggers

import app.phueber.trigly.core.TriggerEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId

/**
 * The live half [TimeOfDayTriggerOccurrenceTest] cannot reach: a zone change
 * that arrives while [TimeOfDayTrigger.events] is already waiting on a
 * computed instant.
 *
 * [FakeTimeZoneChanges] stands in for `ACTION_TIMEZONE_CHANGED`, which no
 * JVM test can actually send; [AndroidTimeZoneChanges] is the real one, and
 * is not exercised here for the same reason [AlarmManagerScheduler] is not,
 * per its own KDoc.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimeOfDayTriggerZoneChangeTest {

    private val zone = ZoneId.of("Europe/Berlin")
    private val start = 1_700_000_000_000L // an arbitrary but fixed instant

    @Test
    fun `a zone change mid-wait is not an occurrence, and the wait is asked for again`() = runTest {
        fun now() = start + currentTime

        val scheduler = FakeAlarmScheduler(now = ::now)
        val zoneChanges = FakeTimeZoneChanges()
        val trigger = TimeOfDayTrigger(
            hour = 8,
            minute = 0,
            days = DayOfWeek.values().toSet(),
            scheduler = scheduler,
            zone = { zone },
            timeZoneChanges = zoneChanges,
            now = ::now,
        )

        val events = mutableListOf<TriggerEvent>()
        val collection = launch { trigger.events().collect { events += it } }

        // Let the collector reach its wait, then fire a zone change before
        // any real time passes.
        runCurrent()
        zoneChanges.fire()
        runCurrent()

        assertEquals(
            "a zone change must not itself be reported as the trigger firing",
            emptyList<TriggerEvent>(),
            events,
        )
        assertEquals(
            "the wait must be asked for the same instant again, not abandoned",
            listOf(scheduler.waitUntilDurableCalls[0], scheduler.waitUntilDurableCalls[0]),
            scheduler.waitUntilDurableCalls,
        )

        collection.cancel()
    }
}
