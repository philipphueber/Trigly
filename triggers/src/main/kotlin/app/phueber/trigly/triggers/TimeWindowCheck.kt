package app.phueber.trigly.triggers

import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerEvent
import app.phueber.trigly.core.TriggerFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.time.Instant
import java.time.ZoneId

/**
 * Whether the current time falls inside a start/end window, minutes-of-day
 * being the whole unit: this is deliberately extracted from [TimeWindowCheck]
 * so the wraparound logic is JVM-testable with three plain integers and no
 * clock, zone, or class to construct.
 *
 * **Boundary convention: inclusive start, exclusive end.** The window "owns"
 * the minute it starts on and hands the minute it ends on to whatever comes
 * next, the same way `09:00`–`10:00` and `10:00`–`11:00` are meant to abut
 * rather than overlap or leave a gap. Symmetric with [endMinuteOfDay] itself
 * never counting as "inside" is also what makes the ordinary and wraparound
 * cases the mirror image of each other below.
 *
 * **Wraparound is not a special case, it is the complement.** An ordinary
 * window (`start < end`) is "inside the span between them"; a wraparound
 * window (`start > end`, e.g. 22:00–07:00) is everything *outside* the span
 * between [endMinuteOfDay] and [startMinuteOfDay] — which is exactly `>=
 * start || < end`. No separate midnight-crossing arithmetic is needed because
 * minutes-of-day never leaves its 0..1439 range in the first place.
 *
 * **`start == end` is defined as the whole day.** A window with zero width
 * read literally would hold for no instant ever (inclusive start, exclusive
 * end leaves nothing between a point and itself) — which makes it useless as
 * a setting and indistinguishable from a mistake. The reading that is
 * actually useful, and the one this picks, is "no restriction": someone who
 * has not deliberately narrowed the window yet, or who sets both fields to
 * midnight meaning "any time", gets a condition that always holds rather
 * than one that silently never does. Documented here because the other
 * reading is equally defensible and there is no way to infer which was meant
 * from the stored config alone.
 */
fun timeWindowHolds(
    nowMinuteOfDay: Int,
    startMinuteOfDay: Int,
    endMinuteOfDay: Int,
): Boolean = when {
    startMinuteOfDay == endMinuteOfDay -> true
    startMinuteOfDay < endMinuteOfDay ->
        nowMinuteOfDay >= startMinuteOfDay && nowMinuteOfDay < endMinuteOfDay
    else ->
        nowMinuteOfDay >= startMinuteOfDay || nowMinuteOfDay < endMinuteOfDay
}

/**
 * Whether the current time of day, in the device's own time zone, falls
 * inside a start/end window.
 *
 * This is the pure form of the time trigger `docs/triggers.md` calls the
 * project's largest missing piece — but only as a *trigger*. A time
 * **trigger** has to wake the app at the right instant, which needs
 * `AlarmManager` and does not exist yet. A time **condition** only has to
 * answer "is it currently between X and Y", which is a clock read and
 * nothing else — see `docs/conditions.md`, "Passive-only checks". That gap is
 * why this type exists before the scheduler does.
 *
 * [events] is therefore an empty flow, not a stub waiting to be filled in:
 * there is no event a time window could ever fire on, because "inside the
 * window" is a level, not an edge, and a level has no instant to name. It
 * pairs with a trigger elsewhere in the gate — "when the doorbell rings, if
 * it is between 22:00 and 07:00" — never on its own.
 */
class TimeWindowCheck(
    private val startHour: Int,
    private val startMinute: Int,
    private val endHour: Int,
    private val endMinute: Int,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val now: () -> Long = System::currentTimeMillis,
) : Trigger {

    init {
        require(startHour in 0..23) { "startHour must be 0-23, was $startHour" }
        require(startMinute in 0..59) { "startMinute must be 0-59, was $startMinute" }
        require(endHour in 0..23) { "endHour must be 0-23, was $endHour" }
        require(endMinute in 0..59) { "endMinute must be 0-59, was $endMinute" }
    }

    override fun events(): Flow<TriggerEvent> = emptyFlow()

    override suspend fun currentlyHolds(): Boolean {
        val current = Instant.ofEpochMilli(now()).atZone(zone).toLocalTime()
        return timeWindowHolds(
            nowMinuteOfDay = current.hour * MINUTES_PER_HOUR + current.minute,
            startMinuteOfDay = startHour * MINUTES_PER_HOUR + startMinute,
            endMinuteOfDay = endHour * MINUTES_PER_HOUR + endMinute,
        )
    }

    companion object {
        const val TYPE = "time_window"
        const val CONFIG_START_HOUR = "start"
        const val CONFIG_START_MINUTE = "startMinute"
        const val CONFIG_END_HOUR = "end"
        const val CONFIG_END_MINUTE = "endMinute"

        private const val MINUTES_PER_HOUR = 60
    }
}

class TimeWindowCheckFactory : TriggerFactory {
    override val type = TimeWindowCheck.TYPE

    override val displayName = "Time of day"
    override val category = Category.TIME

    override val supportsCondition = true

    // The one component in the catalogue that cannot start a rule: there is no
    // scheduler, so `events()` is empty and this exists to be asked. Declared
    // rather than left for the editor to discover, because an empty flow and a
    // flow that has not emitted yet look identical from outside.
    override val producesEvents = false

    override val configFields = listOf(
        ConfigField.TimeOfDay(
            key = TimeWindowCheck.CONFIG_START_HOUR,
            label = "From",
            required = true,
            minuteKey = TimeWindowCheck.CONFIG_START_MINUTE,
        ),
        ConfigField.TimeOfDay(
            key = TimeWindowCheck.CONFIG_END_HOUR,
            label = "Until",
            required = true,
            minuteKey = TimeWindowCheck.CONFIG_END_MINUTE,
            help = "An end before the start wraps past midnight, e.g. 22:00 until " +
                "07:00 covers the night. The same time twice means no restriction.",
        ),
    )

    // No `warning` override: no AlarmManager, no scheduler, no permission —
    // this only ever reads the clock at the instant the gate asks, so there is
    // nothing to warn about.

    override fun create(config: Map<String, String>): Trigger {
        fun hour(key: String): Int {
            val raw = config[key] ?: error("$type needs '$key'")
            return raw.toIntOrNull() ?: error("$key must be a number, was '$raw'")
        }

        fun minute(key: String): Int = config[key]?.toIntOrNull() ?: 0

        return TimeWindowCheck(
            startHour = hour(TimeWindowCheck.CONFIG_START_HOUR),
            startMinute = minute(TimeWindowCheck.CONFIG_START_MINUTE),
            endHour = hour(TimeWindowCheck.CONFIG_END_HOUR),
            endMinute = minute(TimeWindowCheck.CONFIG_END_MINUTE),
        )
    }
}
