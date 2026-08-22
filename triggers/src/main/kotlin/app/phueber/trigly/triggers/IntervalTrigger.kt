package app.phueber.trigly.triggers

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

    override fun create(config: Map<String, String>): Trigger {
        val raw = config[IntervalTrigger.CONFIG_PERIOD_MILLIS]
            ?: error("${IntervalTrigger.TYPE} needs '${IntervalTrigger.CONFIG_PERIOD_MILLIS}'")
        val period = raw.toLongOrNull()
            ?: error("${IntervalTrigger.CONFIG_PERIOD_MILLIS} must be a number, was '$raw'")
        return IntervalTrigger(period)
    }
}
