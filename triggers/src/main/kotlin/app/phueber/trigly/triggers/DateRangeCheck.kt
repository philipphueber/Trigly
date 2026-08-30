package app.phueber.trigly.triggers

import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerEvent
import app.phueber.trigly.core.TriggerFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/**
 * Whether a month-and-day code falls between [startMonthDay] and
 * [endMonthDay], both ends **inclusive**. Every code is `month * 100 + day`
 * (see [DateRangeCheck.monthDayCode]), so codes compare correctly in the same
 * order the calendar does: `101` is 1 January, `1231` is 31 December, and
 * `229` (29 February) sorts between `228` and `301` whether or not the
 * current year happens to have a 29 February at all.
 *
 * Wraparound is the complement, the same construction [timeWindowHolds] uses
 * for a window that crosses midnight: an ordinary range (`start <= end`) is
 * "between them"; a wraparound range (`start > end`, e.g. 1 December to
 * 6 January) is everything outside the span between [endMonthDay] and
 * [startMonthDay], which is `>= start || <= end`.
 *
 * **`start == end` is a single day, not "no restriction".** This is the one
 * place this file deliberately disagrees with [timeWindowHolds]'s convention.
 * A zero-width *clock* window is useless taken literally, because no instant
 * is both inside and outside its own boundary, which is what justifies
 * reading it as "no restriction" instead. A zero-width *date* range has no
 * such problem: "24 December to 24 December" is a real, useful rule (meaning
 * "only on Christmas Eve"), and reading it as "no restriction" would silently
 * defeat the one setting that phrase was written to express. So here,
 * `start == end` takes the ordinary `<=` branch and resolves to
 * `current in start..start`, which is exactly that single day.
 */
fun dateRangeHolds(
    currentMonthDay: Int,
    startMonthDay: Int,
    endMonthDay: Int,
): Boolean = if (startMonthDay <= endMonthDay) {
    currentMonthDay in startMonthDay..endMonthDay
} else {
    currentMonthDay >= startMonthDay || currentMonthDay <= endMonthDay
}

/**
 * Whether today's date, in the device's own time zone, falls between a start
 * and an end date, both given as a month and a day.
 *
 * **Zone:** the device's own, [ZoneId.systemDefault], for the same reason as
 * [DayOfWeekCheck] and [MonthCheck]: a date range names no place of its own.
 *
 * **No year field, on purpose.** "Between 1 December and 6 January" is
 * normally meant to repeat every year; a one-off "between 2 March 2026 and
 * 9 March 2026" is a different question this component does not answer.
 * Rather than accept a year and then silently ignore it (a format deciding
 * the meaning instead of a person doing so), there is simply no year field
 * to fill in: [configFields][DateRangeCheckFactory.configFields] declares a
 * start month, a start day, an end month and an end day, four keys, and
 * nothing else. The range this component describes recurs every year by
 * construction, not by an unread part of a wider one.
 *
 * **Both ends inclusive.** Unlike [TimeWindowCheck], whose adjacent windows
 * are meant to abut without overlapping, two date ranges are not usually
 * defined back-to-back, and a person picking "1 December" to "6 January"
 * means both those days to be included along with everything between. Stated
 * in the last field's help text, in those words.
 *
 * **Wraparound is supported**, because a range spanning the turn of the year
 * is a real thing a person will write, and the pure function above handles it
 * the same way [timeWindowHolds] handles midnight.
 *
 * **A day is validated against its own month**, allowing 29 February as a
 * boundary (using a fixed reference leap year, [LEAP_YEAR], purely so the
 * validation itself does not depend on which year it happens to run in) since
 * a range ending on the leap day is a real setting for a person who wants
 * "the day after" to always mean 1 March. Whether 29 February exists in the
 * year the check actually runs is a fact about the current date, never about
 * the config, so no special case is needed at evaluation time: a non-leap
 * year simply never produces the date `229` for [currentlyHolds] to compare.
 */
class DateRangeCheck(
    private val startMonth: Int,
    private val startDay: Int,
    private val endMonth: Int,
    private val endDay: Int,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val now: () -> Long = System::currentTimeMillis,
) : Trigger {

    init {
        require(startMonth in 1..12) { "startMonth must be 1-12, was $startMonth" }
        require(endMonth in 1..12) { "endMonth must be 1-12, was $endMonth" }
        require(startDay in 1..daysIn(startMonth)) {
            "startDay must be 1-${daysIn(startMonth)} for month $startMonth, was $startDay"
        }
        require(endDay in 1..daysIn(endMonth)) {
            "endDay must be 1-${daysIn(endMonth)} for month $endMonth, was $endDay"
        }
    }

    override fun events(): Flow<TriggerEvent> = emptyFlow()

    override suspend fun currentlyHolds(): Boolean {
        val current = Instant.ofEpochMilli(now()).atZone(zone).toLocalDate()
        return dateRangeHolds(
            currentMonthDay = monthDayCode(current.monthValue, current.dayOfMonth),
            startMonthDay = monthDayCode(startMonth, startDay),
            endMonthDay = monthDayCode(endMonth, endDay),
        )
    }

    companion object {
        const val TYPE = "date_range"

        const val CONFIG_START_MONTH = "startMonth"
        const val CONFIG_START_DAY = "startDay"
        const val CONFIG_END_MONTH = "endMonth"
        const val CONFIG_END_DAY = "endDay"

        private const val MONTH_DAY_SCALE = 100

        /** Any leap year, used only so 29 February validates as a real day. */
        private const val LEAP_YEAR = 2024

        private fun daysIn(month: Int): Int = YearMonth.of(LEAP_YEAR, month).lengthOfMonth()

        fun monthDayCode(month: Int, day: Int): Int = month * MONTH_DAY_SCALE + day
    }
}

class DateRangeCheckFactory : TriggerFactory {
    override val type = DateRangeCheck.TYPE

    override val displayName = "Date range"
    override val category = Category.TIME

    override val supportsCondition = true

    // Passive-only check, same as `time_window`, `day_of_week` and `month`.
    override val producesEvents = false

    override val configFields = listOf(
        ConfigField.Number(
            key = DateRangeCheck.CONFIG_START_MONTH,
            label = "From (month)",
            default = 1,
            min = 1,
            max = 12,
        ),
        ConfigField.Number(
            key = DateRangeCheck.CONFIG_START_DAY,
            label = "From (day)",
            default = 1,
            min = 1,
            max = 31,
        ),
        ConfigField.Number(
            key = DateRangeCheck.CONFIG_END_MONTH,
            label = "Until (month)",
            default = 12,
            min = 1,
            max = 12,
        ),
        ConfigField.Number(
            key = DateRangeCheck.CONFIG_END_DAY,
            label = "Until (day)",
            default = 31,
            min = 1,
            max = 31,
            help = "Both dates are included, and only the month and day are " +
                "used: there is no year field, so the range repeats every " +
                "year. An end date before the start date wraps into the " +
                "next year, so 1 December until 6 January covers the turn " +
                "of the year. The same date in both fields means only " +
                "that one day, not every day: unlike a clock window, a " +
                "single calendar date is still a useful rule on its own.",
        ),
    )

    // No `warning` override, for the same reason as `time_window`: this only
    // ever reads the clock at the instant the gate asks.

    override fun create(config: Map<String, String>): Trigger {
        fun number(key: String, default: Int): Int = config[key]?.toIntOrNull() ?: default

        return DateRangeCheck(
            startMonth = number(DateRangeCheck.CONFIG_START_MONTH, 1),
            startDay = number(DateRangeCheck.CONFIG_START_DAY, 1),
            endMonth = number(DateRangeCheck.CONFIG_END_MONTH, 12),
            endDay = number(DateRangeCheck.CONFIG_END_DAY, 31),
        )
    }
}
