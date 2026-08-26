package app.phueber.trigly.triggers

import app.phueber.trigly.core.AlarmScheduler
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.DurationUnit
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerEvent
import app.phueber.trigly.core.TriggerFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Fires every [periodMillis] while the rule is enabled.
 *
 * The wait is [AlarmScheduler.waitFor], not a plain coroutine `delay`. A
 * plain `delay` only wakes when something else wakes the device, and can
 * sleep through the whole period in Doze; `docs/todo.md`'s T1 is the record
 * of that gap and the fix. The trade is drift: expect the fire time to move
 * by up to a few minutes, for the reason `AlarmManagerScheduler`'s own KDoc
 * gives.
 *
 * **What this still does not survive.** A user's force-stop puts the app in
 * the stopped state and cancels every pending alarm. Nothing in this class,
 * or in the scheduler it calls, gets that back. See `docs/todo.md`'s R1.
 */
class IntervalTrigger(
    private val periodMillis: Long,
    private val scheduler: AlarmScheduler,
    private val now: () -> Long = System::currentTimeMillis,
) : Trigger {

    init {
        require(periodMillis > 0) { "periodMillis must be positive, was $periodMillis" }
    }

    override fun events(): Flow<TriggerEvent> = flow {
        while (true) {
            scheduler.waitFor(periodMillis)
            emit(TriggerEvent(TYPE, now()))
        }
    }

    companion object {
        const val TYPE = "interval"
        const val CONFIG_PERIOD_MILLIS = "periodMillis"
    }
}

class IntervalTriggerFactory(private val scheduler: AlarmScheduler) : TriggerFactory {
    override val type: String = IntervalTrigger.TYPE

    override val displayName = "Every so often"
    override val category = Category.TIME

    override val configFields = listOf(
        ConfigField.Duration(
            key = IntervalTrigger.CONFIG_PERIOD_MILLIS,
            label = "Repeat every",
            required = true,
            preferred = DurationUnit.MINUTES,
            help = "This trigger uses the system alarm clock, so it still fires " +
                "while the device sleeps. The fire time can drift by a few minutes.",
        ),
    )

    override val warning: String =
        "The fire time can drift by a few minutes, because this trigger does not " +
            "use an exact alarm. If you force stop Trigly, this trigger stops " +
            "until you open the app again."

    override fun create(config: Map<String, String>): Trigger {
        val raw = config[IntervalTrigger.CONFIG_PERIOD_MILLIS]
            ?: error("${IntervalTrigger.TYPE} needs '${IntervalTrigger.CONFIG_PERIOD_MILLIS}'")
        val period = raw.toLongOrNull()
            ?: error("${IntervalTrigger.CONFIG_PERIOD_MILLIS} must be a number, was '$raw'")
        return IntervalTrigger(period, scheduler)
    }
}
