package app.phueber.trigly.triggers

import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.DurationUnit
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerEvent
import app.phueber.trigly.core.TriggerFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Fires every [periodMillis] while the rule is enabled.
 *
 * TODO(scheduling): this is a plain coroutine delay, so it only ticks while the
 *  hosting process is alive and not in Doze. Anything the user expects to fire
 *  at a wall-clock time, or after the process is killed, needs AlarmManager
 *  (exact/inexact) or WorkManager instead. Kept simple here because it is the
 *  one trigger that needs no system integration, which makes it the reference
 *  implementation of the [Trigger] contract.
 */
class IntervalTrigger(
    private val periodMillis: Long,
    private val now: () -> Long = System::currentTimeMillis,
) : Trigger {

    init {
        require(periodMillis > 0) { "periodMillis must be positive, was $periodMillis" }
    }

    override fun events(): Flow<TriggerEvent> = flow {
        while (true) {
            delay(periodMillis)
            emit(TriggerEvent(TYPE, now()))
        }
    }

    companion object {
        const val TYPE = "interval"
        const val CONFIG_PERIOD_MILLIS = "periodMillis"
    }
}

class IntervalTriggerFactory : TriggerFactory {
    override val type: String = IntervalTrigger.TYPE

    override val displayName = "Every so often"
    override val category = Category.TIME

    override val configFields = listOf(
        ConfigField.Duration(
            key = IntervalTrigger.CONFIG_PERIOD_MILLIS,
            label = "Repeat every",
            required = true,
            preferred = DurationUnit.MINUTES,
            help = "This trigger ticks only while Trigly runs. It pauses in Doze mode. " +
                "Do not use it for a task that must happen at an exact time.",
        ),
    )

    override val warning: String =
        "The system can stop this trigger when it suspends the app. " +
            "Use this trigger for convenience only, not for alarms."

    override fun create(config: Map<String, String>): Trigger {
        val raw = config[IntervalTrigger.CONFIG_PERIOD_MILLIS]
            ?: error("${IntervalTrigger.TYPE} needs '${IntervalTrigger.CONFIG_PERIOD_MILLIS}'")
        val period = raw.toLongOrNull()
            ?: error("${IntervalTrigger.CONFIG_PERIOD_MILLIS} must be a number, was '$raw'")
        return IntervalTrigger(period)
    }
}
