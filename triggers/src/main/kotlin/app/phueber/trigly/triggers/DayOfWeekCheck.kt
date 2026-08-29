package app.phueber.trigly.triggers

import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerEvent
import app.phueber.trigly.core.TriggerFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/**
 * Whether [current] is one of [selectedDays]. Pure and trivial on its own, but
 * kept separate from [DayOfWeekCheck] so the "which day is it" question is
 * JVM-testable with a plain [DayOfWeek] and no clock or zone to construct.
 */
fun dayOfWeekHolds(current: DayOfWeek, selectedDays: Set<DayOfWeek>): Boolean =
    current in selectedDays

/**
 * Whether today, in the device's own time zone, is one of the days a person
 * picked.
 *
 * **Zone: the device's own, not typed in.** Unlike [SolarTrigger], which takes
 * a zone explicitly because a sunrise it computes can belong to a place the
 * phone is not currently in, this condition has no place of its own to be
 * wrong about: "on weekdays" and "on weekends" are always a question about the
 * calendar the person holding the phone is living in right now, and there is
 * nothing here for them to type a location into in the first place. So this
 * reads [ZoneId.systemDefault] the same way [TimeWindowCheck] does, and for
 * the same reason.
 *
 * **Seven flags, not a set-valued field.** `:core`'s [ConfigField] has no
 * multi-select kind, and adding one for this alone would mean editing `:core`
 * for a feature that does not need it: a checkbox per day expresses
 * "weekdays", "weekends" and any individual day equally well, and Monday
 * through Sunday is a fixed, known-in-advance list, unlike (say) the open set
 * of installed apps that would actually justify a new field kind.
 *
 * **All seven default to checked**, so a freshly added condition holds every
 * day, the same "no restriction until deliberately narrowed" reading
 * [TimeWindowCheck] gives a start-equal-to-end window. Unlike that window,
 * though, unchecking every box here is not read back as "no restriction" —
 * see [DayOfWeekCheckFactory.create]: an explicit, all-unchecked config means
 * exactly what it shows, a condition that never holds. The two cases are not
 * the same shape: a window collapsed to zero width could never have been
 * meant literally, because no instant is both inside and outside its own
 * boundary; seven boxes unchecked is a real, reachable state a person can
 * arrive at on purpose, and reinterpreting it would be guessing.
 */
class DayOfWeekCheck(
    private val selectedDays: Set<DayOfWeek>,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val now: () -> Long = System::currentTimeMillis,
) : Trigger {

    override fun events(): Flow<TriggerEvent> = emptyFlow()

    override suspend fun currentlyHolds(): Boolean {
        val current = Instant.ofEpochMilli(now()).atZone(zone).dayOfWeek
        return dayOfWeekHolds(current, selectedDays)
    }

    companion object {
        const val TYPE = "day_of_week"

        const val CONFIG_MONDAY = "monday"
        const val CONFIG_TUESDAY = "tuesday"
        const val CONFIG_WEDNESDAY = "wednesday"
        const val CONFIG_THURSDAY = "thursday"
        const val CONFIG_FRIDAY = "friday"
        const val CONFIG_SATURDAY = "saturday"
        const val CONFIG_SUNDAY = "sunday"
    }
}

class DayOfWeekCheckFactory : TriggerFactory {
    override val type = DayOfWeekCheck.TYPE

    override val displayName = "Day of the week"
    override val category = Category.TIME

    override val supportsCondition = true

    // No scheduler either way: a passive-only check, same as `time_window`.
    // See that file's factory for why this is declared rather than left for
    // the editor to discover.
    override val producesEvents = false

    override val configFields = listOf(
        ConfigField.Flag(
            key = DayOfWeekCheck.CONFIG_MONDAY,
            label = "Monday",
            default = true,
            help = "Which days this holds on. All seven checked, the " +
                "default, means every day; this only narrows the rule once " +
                "some are cleared. Clearing all seven means the condition " +
                "never holds, not that it holds every day.",
        ),
        ConfigField.Flag(key = DayOfWeekCheck.CONFIG_TUESDAY, label = "Tuesday", default = true),
        ConfigField.Flag(key = DayOfWeekCheck.CONFIG_WEDNESDAY, label = "Wednesday", default = true),
        ConfigField.Flag(key = DayOfWeekCheck.CONFIG_THURSDAY, label = "Thursday", default = true),
        ConfigField.Flag(key = DayOfWeekCheck.CONFIG_FRIDAY, label = "Friday", default = true),
        ConfigField.Flag(key = DayOfWeekCheck.CONFIG_SATURDAY, label = "Saturday", default = true),
        ConfigField.Flag(key = DayOfWeekCheck.CONFIG_SUNDAY, label = "Sunday", default = true),
    )

    // No `warning` override, for the same reason as `time_window`: this only
    // ever reads the clock at the instant the gate asks.

    override fun create(config: Map<String, String>): Trigger {
        fun flag(key: String): Boolean = config[key]?.toBoolean() ?: true

        val selected = buildSet {
            if (flag(DayOfWeekCheck.CONFIG_MONDAY)) add(DayOfWeek.MONDAY)
            if (flag(DayOfWeekCheck.CONFIG_TUESDAY)) add(DayOfWeek.TUESDAY)
            if (flag(DayOfWeekCheck.CONFIG_WEDNESDAY)) add(DayOfWeek.WEDNESDAY)
            if (flag(DayOfWeekCheck.CONFIG_THURSDAY)) add(DayOfWeek.THURSDAY)
            if (flag(DayOfWeekCheck.CONFIG_FRIDAY)) add(DayOfWeek.FRIDAY)
            if (flag(DayOfWeekCheck.CONFIG_SATURDAY)) add(DayOfWeek.SATURDAY)
            if (flag(DayOfWeekCheck.CONFIG_SUNDAY)) add(DayOfWeek.SUNDAY)
        }

        return DayOfWeekCheck(selected)
    }
}
