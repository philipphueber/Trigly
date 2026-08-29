package app.phueber.trigly.triggers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import app.phueber.trigly.core.AlarmScheduler
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerEvent
import app.phueber.trigly.core.TriggerFactory
import app.phueber.trigly.core.VariableKind
import app.phueber.trigly.core.VariableSpec
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.coroutines.resume

/**
 * Fires once at a chosen hour and minute, on whichever days of the week are
 * selected. "Every weekday at 8am" is one rule: an hour, a minute, and
 * Monday through Friday checked.
 *
 * `docs/triggers.md`'s "Time of day / day of week" entry sets the design
 * this follows. [SolarTrigger] is the pattern it copies: the same
 * [AlarmScheduler.waitUntilDurable] for the same T17 reason, and the same
 * [AlarmWakeEvents] catch-up, so a durable wake that already fired is not
 * skipped in favour of tomorrow. See [events].
 *
 * **The next occurrence is always computed from the wall-clock target,
 * never from an elapsed duration added to "now".** [nextOccurrenceMillis]
 * builds the candidate instant fresh, from [hour]:[minute] on a calendar
 * date. That matches how [SolarTrigger.nextOccurrenceMillis] builds its own
 * instant, from a calendar date, rather than by adding a fixed number of
 * milliseconds to the last one. The recurring-alarm bug this avoids is
 * real. A design that schedules "now plus 24 hours" compounds whatever
 * lateness the platform's own inexact alarm already added, so an 8am rule
 * drifts to 8:04, then to 8:09. Anchoring every occurrence to the fixed hour
 * and minute instead means a late firing is late once, not later forever.
 *
 * **Its own day selection, not the standalone day-of-week condition landing
 * alongside this.** A condition can only be asked once something else has
 * already woken the rule. It has no scheduler of its own, by design; see
 * `docs/conditions.md`. A rule that should run only at 8am on a weekday has
 * nothing to wake it at 8am on a Tuesday, if the day check lives outside
 * the trigger that owns the clock. Folding the day selection into this
 * trigger is what makes "every weekday at 8am" one rule, instead of a rule
 * plus a condition that could never fire it.
 *
 * **A live zone, unlike [SolarTrigger]'s fixed one.** [SolarTrigger] takes
 * an explicit [ZoneId] because its subject, a place, is typed once and does
 * not move when the phone does. A sunrise rule about home stays about home
 * after a trip. This trigger has no place at all. Its subject is "8am", and
 * the only honest reading of that with no location attached is "8am
 * wherever this phone currently is". So [zone] is a supplier, read fresh on
 * every loop pass, not a value fixed at construction. [timeZoneChanges]
 * also interrupts an in-flight wait the moment `ACTION_TIMEZONE_CHANGED`
 * arrives. Without it, a wait already armed for an instant computed under
 * the old zone would still fire at the wrong local time, hours late or
 * early depending on the direction of the move, until the next ordinary
 * loop pass corrected it.
 *
 * **Boot needs nothing extra.** `BootEvents` and the manifest receiver that
 * feeds it exist so a trigger can tell why the engine just started; this
 * one does not need to. `EngineService` builds every trigger fresh from its
 * stored config each time the engine starts, whether that start is the user
 * opening the app or `BootReceiver` starting it after a restart. The very
 * first pass through [events] computes `fireAt` from a fresh `now()` either
 * way. There is no state to carry across a restart for this trigger to
 * lose.
 *
 * **Scheduler form: [AlarmScheduler.waitUntilDurable], not an exact
 * alarm.** `docs/todo.md`'s T14 makes this a per-caller decision, not a
 * default, and this caller's answer is inexact. `SCHEDULE_EXACT_ALARM` is a
 * user-granted special access this project has not asked the maintainer to
 * require just for an 8am reminder. `AlarmManagerScheduler`'s own KDoc gives
 * the concrete cost of staying inexact: up to [MAX_WINDOW_MILLIS] of drift,
 * and the warning below says exactly that, the same honesty [SolarTrigger]'s
 * warning already gives for the same reason. A person who genuinely needs a
 * to-the-minute alarm should use their device's own alarm clock. It is built
 * for that, and it already holds the access this trigger deliberately does
 * not ask for.
 *
 * Days with none of the selected weekdays occurring within [SEARCH_DAYS]
 * (every flag turned off) end the flow rather than spin forever, the same
 * honest null [SolarTrigger] gives a polar day with no sunrise. The factory
 * refuses that configuration instead, in [TimeOfDayTriggerFactory.create]:
 * a rule with no day it could ever fire on is a mistake to report, not a
 * quiet rule to accept.
 */
class TimeOfDayTrigger(
    private val hour: Int,
    private val minute: Int,
    private val days: Set<DayOfWeek>,
    private val scheduler: AlarmScheduler,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val timeZoneChanges: TimeZoneChanges = TimeZoneChanges.NONE,
    private val now: () -> Long = System::currentTimeMillis,
) : Trigger {

    init {
        require(hour in 0..23) { "hour must be 0-23, was $hour" }
        require(minute in 0..59) { "minute must be 0-59, was $minute" }
        require(days.isNotEmpty()) { "at least one day of the week must be selected" }
    }

    override fun events(): Flow<TriggerEvent> = flow {
        while (true) {
            val zoneNow = zone()
            // See SolarTrigger.events for why this searches from a little
            // before now, and only when a durable wait's alarm fired
            // recently. A fresh collection may exist only because that
            // alarm just restarted the process, and "now" can already be a
            // few minutes past the exact occurrence this trigger was
            // durably woken for.
            val searchFrom = if (AlarmWakeEvents.pending(now())) now() - CATCH_UP_MILLIS else now()
            val fireAt = nextOccurrenceMillis(searchFrom, zoneNow) ?: break

            // Waits for the computed instant, but gives up early if the
            // zone changes first. An instant computed under a zone that no
            // longer holds is not the right instant to keep waiting for.
            // Either way nothing here has fired yet, so a zone change never
            // itself counts as an occurrence. The loop just goes around
            // again and recomputes fireAt under the new zone.
            if (waitUntilOrZoneChange(fireAt)) continue

            emit(
                TriggerEvent(
                    triggerType = TYPE,
                    firedAtMillis = now(),
                    payload = mapOf(
                        PAYLOAD_DAY to Instant.ofEpochMilli(fireAt).atZone(zoneNow).dayOfWeek.configValue(),
                    ),
                )
            )
            // Past the emitted instant, so the same day cannot be scheduled
            // twice if the clock has not visibly advanced. See
            // SolarTrigger.events for why the plain waitFor is enough here.
            scheduler.waitFor(1_000)
        }
    }

    /**
     * Races [AlarmScheduler.waitUntilDurable] against [timeZoneChanges]'
     * next `ACTION_TIMEZONE_CHANGED`, and returns whether the zone change
     * won.
     *
     * Both are asked for at once, so neither can starve the other.
     * Whichever finishes first has its result kept. The other is
     * cancelled, which for the scheduler half is the only cancel
     * [AlarmScheduler] offers at all, per its own KDoc, and releases
     * whatever alarm was pending.
     */
    private suspend fun waitUntilOrZoneChange(fireAtMillis: Long): Boolean = coroutineScope {
        val wait = async { scheduler.waitUntilDurable(fireAtMillis) }
        val zoneChanged = async { timeZoneChanges.awaitChange() }
        val changedFirst = select<Boolean> {
            wait.onAwait { false }
            zoneChanged.onAwait { true }
        }
        wait.cancel()
        zoneChanged.cancel()
        changedFirst
    }

    /**
     * The next time [hour]:[minute] happens in [zone], strictly after
     * [fromMillis], on a selected day of the week, or null if no selected
     * day occurs within [SEARCH_DAYS].
     *
     * Built from a calendar date and a fixed [LocalTime] on each candidate
     * day, rather than from an elapsed duration, the same way
     * [SolarTrigger.nextOccurrenceMillis] is. See the class KDoc for why
     * that is what keeps this from drifting. Resolving through
     * [ZonedDateTime.of] also keeps a daylight-saving change correct for
     * free: the same local 08:00 resolves to a different UTC offset on
     * either side of the transition, because it asks the zone what 08:00
     * means on that specific date, instead of adding a fixed number of
     * hours to whatever 08:00 meant yesterday.
     *
     * [SEARCH_DAYS] only has to clear a week. The day-of-week pattern
     * repeats every seven days, so if none of the selected days appears in
     * the next week, none ever will from here.
     */
    internal fun nextOccurrenceMillis(fromMillis: Long, zone: ZoneId): Long? {
        val from = Instant.ofEpochMilli(fromMillis).atZone(zone).toLocalDate()

        for (offset in 0..SEARCH_DAYS) {
            val date = from.plusDays(offset.toLong())
            if (date.dayOfWeek !in days) continue
            val candidate = ZonedDateTime.of(date, LocalTime.of(hour, minute), zone)
            val millis = candidate.toInstant().toEpochMilli()
            if (millis > fromMillis) return millis
        }
        return null
    }

    companion object {
        const val TYPE = "time_of_day"
        const val CONFIG_HOUR = "hour"
        const val CONFIG_MINUTE = "minute"
        const val PAYLOAD_DAY = "day"

        /** A week clears every possible day-of-week pattern; see [nextOccurrenceMillis]. */
        const val SEARCH_DAYS = 7

        /** Same reasoning and the same value as [SolarTrigger]'s own catch-up window. */
        internal const val CATCH_UP_MILLIS = MAX_WINDOW_MILLIS + AlarmWakeEvents.DEFAULT_WINDOW_MILLIS

        /** The config key each day of the week is stored under, in week order. */
        val DAY_CONFIG_KEYS: List<Pair<String, DayOfWeek>> = listOf(
            "monday" to DayOfWeek.MONDAY,
            "tuesday" to DayOfWeek.TUESDAY,
            "wednesday" to DayOfWeek.WEDNESDAY,
            "thursday" to DayOfWeek.THURSDAY,
            "friday" to DayOfWeek.FRIDAY,
            "saturday" to DayOfWeek.SATURDAY,
            "sunday" to DayOfWeek.SUNDAY,
        )
    }
}

/** The value [TimeOfDayTrigger.PAYLOAD_DAY] stores for this day, for example "monday". */
internal fun DayOfWeek.configValue(): String = name.lowercase()

/**
 * A live signal that the device's time zone just changed, for a wait that
 * must not keep counting down towards an instant computed under the zone
 * that no longer holds.
 *
 * Only [TimeOfDayTrigger] needs this. [SolarTrigger]'s zone is fixed by a
 * typed place, so a live zone change on the phone has nothing to correct
 * there. Kept to the one method this trigger actually calls, the same way
 * [AlarmScheduler] is kept to the shapes its callers need, rather than
 * wrapping the whole of `AlarmManager`, or here, the whole of
 * `BroadcastReceiver`.
 */
fun interface TimeZoneChanges {
    /** Suspends until the zone changes. Cancelling the caller is the only cancel. */
    suspend fun awaitChange()

    companion object {
        /** Nothing ever reaches this trigger; used wherever no live signal is wired up. */
        val NONE = TimeZoneChanges { awaitCancellation() }
    }
}

/**
 * [TimeZoneChanges] over `ACTION_TIMEZONE_CHANGED`.
 *
 * Registered at runtime, the same way [BroadcastTrigger] registers its own
 * receivers, and for the same reason. `ACTION_TIMEZONE_CHANGED` is one of
 * the platform's exempted implicit broadcasts, so a manifest receiver could
 * also reach it, but nothing here needs the process to still exist by the
 * time it arrives. A dead process self-corrects its zone on its own next
 * start, per [TimeOfDayTrigger]'s own KDoc. What a manifest receiver cannot
 * do is interrupt a wait that is already in progress in a process that is
 * very much alive, which is the one case this class exists for. Not
 * exercised by a JVM test, for the same reason `AlarmManagerScheduler` is
 * not: it calls live Android framework classes, per
 * `docs/architecture.md`'s testing posture.
 */
class AndroidTimeZoneChanges(private val context: Context) : TimeZoneChanges {
    override suspend fun awaitChange(): Unit = suspendCancellableCoroutine { continuation ->
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receivedContext: Context?, intent: Intent?) {
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_TIMEZONE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        continuation.invokeOnCancellation { context.unregisterReceiver(receiver) }
    }
}

class TimeOfDayTriggerFactory(
    private val scheduler: AlarmScheduler,
    private val timeZoneChanges: TimeZoneChanges,
) : TriggerFactory {
    override val type = TimeOfDayTrigger.TYPE

    override val displayName = "Time of day"
    override val category = Category.TIME

    override val configFields = listOf(
        ConfigField.TimeOfDay(
            key = TimeOfDayTrigger.CONFIG_HOUR,
            label = "Fires at",
            required = true,
            minuteKey = TimeOfDayTrigger.CONFIG_MINUTE,
        ),
    ) + TimeOfDayTrigger.DAY_CONFIG_KEYS.mapIndexed { index, (key, day) ->
        ConfigField.Flag(
            key = key,
            label = day.configValue().replaceFirstChar { it.uppercase() },
            default = true,
            help = if (index == 0) {
                "Which days this fires on. All seven are on by default. Turn off " +
                    "Saturday and Sunday for \"every weekday\"."
            } else {
                null
            },
        )
    }

    override val warning: String =
        "This trigger needs Trigly running to fire. The fire time can drift by " +
            "up to a few minutes, because this trigger does not use an exact " +
            "alarm. Do not use it as an alarm clock; use your device's own " +
            "alarm for that. If you force stop Trigly, this trigger stops " +
            "until you open the app again."

    override val variables = listOf(
        VariableSpec(
            key = TimeOfDayTrigger.PAYLOAD_DAY,
            label = "Day of week",
            kind = VariableKind.STATE,
            sample = DayOfWeek.MONDAY.configValue(),
            help = "One of ${DayOfWeek.values().joinToString { "'${it.configValue()}'" }}.",
        ),
    )

    override fun create(config: Map<String, String>): Trigger {
        fun hour(key: String): Int {
            val raw = config[key] ?: error("${TimeOfDayTrigger.TYPE} needs '$key'")
            return raw.toIntOrNull() ?: error("$key must be a number, was '$raw'")
        }

        val days = TimeOfDayTrigger.DAY_CONFIG_KEYS
            .filter { (key, _) -> config[key]?.toBoolean() ?: true }
            .map { (_, day) -> day }
            .toSet()

        // TimeOfDayTrigger's own init block, require(days.isNotEmpty()), is
        // what actually refuses this. Nothing is duplicated here.
        return TimeOfDayTrigger(
            hour = hour(TimeOfDayTrigger.CONFIG_HOUR),
            minute = config[TimeOfDayTrigger.CONFIG_MINUTE]?.toIntOrNull() ?: 0,
            days = days,
            scheduler = scheduler,
            timeZoneChanges = timeZoneChanges,
        )
    }
}
