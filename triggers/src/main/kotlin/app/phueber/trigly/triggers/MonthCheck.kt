package app.phueber.trigly.triggers

import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerEvent
import app.phueber.trigly.core.TriggerFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.time.Instant
import java.time.Month
import java.time.ZoneId

/**
 * Whether [current] is one of [selectedMonths]. Pure and trivial on its own,
 * kept separate from [MonthCheck] for the same reason [dayOfWeekHolds] is
 * kept separate from [DayOfWeekCheck]: JVM-testable with a plain [Month] and
 * no clock or zone to construct.
 */
fun monthHolds(current: Month, selectedMonths: Set<Month>): Boolean =
    current in selectedMonths

/**
 * Whether the current month, in the device's own time zone, is one of the
 * months a person picked.
 *
 * Same shape as [DayOfWeekCheck], for the same reasons, so read that class's
 * KDoc for the fuller reasoning; this repeats only what differs.
 *
 * **Zone:** the device's own, [ZoneId.systemDefault], for the same reason as
 * [DayOfWeekCheck] — a month is not a place, so there is nothing to type a
 * zone about.
 *
 * **Twelve flags, not a from/to range.** "Holds in the months a person picks"
 * is an arbitrary subset (say, March, July and December for quarterly
 * reminders), not necessarily contiguous, so a `time_window`-style
 * start/end pair would be the wrong shape here: it can express "June through
 * August" but not "March, July and December" without either lying about the
 * months in between or turning into three separate conditions joined by an
 * `ANY` group, which is what a range would be a worse way of saying. Twelve
 * checkboxes say exactly what is meant, including any range someone does want
 * (check consecutive boxes) with no separate mechanism.
 *
 * **This also settles the wraparound question the task raises for a from/to
 * range**: it does not arise, because there is no from/to range to wrap.
 * "November through February" is four boxes checked, in any order, and
 * checking November, December, January and February needs no notion of which
 * one comes "first" the way a two-field range would.
 *
 * All twelve default to checked, matching [DayOfWeekCheck]'s "no restriction
 * until narrowed" default, and clearing all twelve is likewise read literally
 * as never holding, not as every month.
 */
class MonthCheck(
    private val selectedMonths: Set<Month>,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val now: () -> Long = System::currentTimeMillis,
) : Trigger {

    override fun events(): Flow<TriggerEvent> = emptyFlow()

    override suspend fun currentlyHolds(): Boolean {
        val current = Instant.ofEpochMilli(now()).atZone(zone).month
        return monthHolds(current, selectedMonths)
    }

    companion object {
        const val TYPE = "month"

        const val CONFIG_JANUARY = "january"
        const val CONFIG_FEBRUARY = "february"
        const val CONFIG_MARCH = "march"
        const val CONFIG_APRIL = "april"
        const val CONFIG_MAY = "may"
        const val CONFIG_JUNE = "june"
        const val CONFIG_JULY = "july"
        const val CONFIG_AUGUST = "august"
        const val CONFIG_SEPTEMBER = "september"
        const val CONFIG_OCTOBER = "october"
        const val CONFIG_NOVEMBER = "november"
        const val CONFIG_DECEMBER = "december"
    }
}

class MonthCheckFactory : TriggerFactory {
    override val type = MonthCheck.TYPE

    override val displayName = "Month"
    override val category = Category.TIME

    override val supportsCondition = true

    // Passive-only check, same as `time_window` and `day_of_week`.
    override val producesEvents = false

    override val configFields = listOf(
        ConfigField.Flag(
            key = MonthCheck.CONFIG_JANUARY,
            label = "January",
            default = true,
            help = "Which months this holds in. All twelve checked, the " +
                "default, means every month; this only narrows the rule " +
                "once some are cleared. Clearing all twelve means the " +
                "condition never holds, not that it holds every month.",
        ),
        ConfigField.Flag(key = MonthCheck.CONFIG_FEBRUARY, label = "February", default = true),
        ConfigField.Flag(key = MonthCheck.CONFIG_MARCH, label = "March", default = true),
        ConfigField.Flag(key = MonthCheck.CONFIG_APRIL, label = "April", default = true),
        ConfigField.Flag(key = MonthCheck.CONFIG_MAY, label = "May", default = true),
        ConfigField.Flag(key = MonthCheck.CONFIG_JUNE, label = "June", default = true),
        ConfigField.Flag(key = MonthCheck.CONFIG_JULY, label = "July", default = true),
        ConfigField.Flag(key = MonthCheck.CONFIG_AUGUST, label = "August", default = true),
        ConfigField.Flag(key = MonthCheck.CONFIG_SEPTEMBER, label = "September", default = true),
        ConfigField.Flag(key = MonthCheck.CONFIG_OCTOBER, label = "October", default = true),
        ConfigField.Flag(key = MonthCheck.CONFIG_NOVEMBER, label = "November", default = true),
        ConfigField.Flag(key = MonthCheck.CONFIG_DECEMBER, label = "December", default = true),
    )

    override fun create(config: Map<String, String>): Trigger {
        fun flag(key: String): Boolean = config[key]?.toBoolean() ?: true

        val selected = buildSet {
            if (flag(MonthCheck.CONFIG_JANUARY)) add(Month.JANUARY)
            if (flag(MonthCheck.CONFIG_FEBRUARY)) add(Month.FEBRUARY)
            if (flag(MonthCheck.CONFIG_MARCH)) add(Month.MARCH)
            if (flag(MonthCheck.CONFIG_APRIL)) add(Month.APRIL)
            if (flag(MonthCheck.CONFIG_MAY)) add(Month.MAY)
            if (flag(MonthCheck.CONFIG_JUNE)) add(Month.JUNE)
            if (flag(MonthCheck.CONFIG_JULY)) add(Month.JULY)
            if (flag(MonthCheck.CONFIG_AUGUST)) add(Month.AUGUST)
            if (flag(MonthCheck.CONFIG_SEPTEMBER)) add(Month.SEPTEMBER)
            if (flag(MonthCheck.CONFIG_OCTOBER)) add(Month.OCTOBER)
            if (flag(MonthCheck.CONFIG_NOVEMBER)) add(Month.NOVEMBER)
            if (flag(MonthCheck.CONFIG_DECEMBER)) add(Month.DECEMBER)
        }

        return MonthCheck(selected)
    }
}
