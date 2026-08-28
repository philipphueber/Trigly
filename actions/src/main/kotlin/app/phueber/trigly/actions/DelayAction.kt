package app.phueber.trigly.actions

import app.phueber.trigly.core.Action
import app.phueber.trigly.core.ActionFactory
import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.AlarmScheduler
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.DurationUnit
import app.phueber.trigly.core.TriggerEvent

/**
 * Duration for a delay, required and capped.
 *
 * No default, unlike [vibrationDurationMillis] and [alertDurationMillis]: a
 * vibration and a chime both have a length that means something even unset, a
 * quick buzz, a short tone. A wait has no natural length of its own. Silently
 * picking a number for a person who left the field blank would hide the one
 * choice this action exists to make, so a missing value is refused the same
 * as an invalid one, the way `IntervalTriggerFactory.create` already refuses
 * a missing period rather than guessing one. The cap is still the same
 * reasoning [vibrationDurationMillis] and [alertDurationMillis] use: a
 * mistyped value must not be unstoppable short of disabling the rule. Pure so
 * both the requirement and the cap are tested rather than trusted.
 */
fun delayDurationMillis(raw: String?): Long {
    val trimmed = raw?.trim().orEmpty()
    require(trimmed.isNotEmpty()) {
        "${DelayAction.CONFIG_DURATION_MILLIS} is required"
    }
    val duration = trimmed.toLongOrNull()
    require(duration != null) {
        "${DelayAction.CONFIG_DURATION_MILLIS} must be a number, was '$trimmed'"
    }
    require(duration > 0) { "duration must be positive, was $duration" }
    return duration.coerceAtMost(DelayAction.MAX_DURATION_MILLIS)
}

/**
 * Waits, then lets the rule continue with the actions that come after it.
 *
 * **The wait is [AlarmScheduler.waitFor], never a plain coroutine `delay`.**
 * A plain `delay` is counted by the process's own clock, so it can sleep
 * through the whole wait once the device enters Doze; `docs/todo.md`'s T1 is
 * the record of that gap for five other callers in this codebase. A value
 * this action waits for is set by whoever built the rule, not by this
 * codebase, so it cannot be assumed short the way `TriggerEngine.resolveHolds`'s
 * few-second retry can be. Any duration this action offers has to survive
 * Doze, which rules out `delay` entirely.
 *
 * **Not [AlarmScheduler.waitForDurable], though.** That method exists for
 * `IntervalTrigger` and `SolarTrigger`, and what makes it work for them is
 * that a fresh collection of their `events()` after a killed process *is* a
 * correct resumption: "wait for the next occurrence" means exactly the same
 * thing whether this is the first call or a restart. This action has no
 * equivalent fresh start. It runs partway through one specific firing of one
 * specific rule, holding a position in that rule's remaining actions and
 * whatever outputs the earlier ones produced. None of that is saved anywhere,
 * see `ActionOutputs` in `:core`. If the process dies mid-wait, the coroutine
 * running this action dies with it, exactly as it would for any other action
 * already in flight, delay or not; see [Action]. A restarted engine calls
 * `TriggerEngine.sync`, which starts fresh jobs listening for the *next*
 * qualifying event. It has no way to jump back into the middle of the one
 * that was interrupted. Arming a durable backstop alarm for this wait would
 * therefore only ever wake a process that has nothing of this firing left to
 * resume, at the cost `AlarmManagerScheduler.awaitAlarmDurably`'s own KDoc
 * warns a needless one carries: a wake nobody needed, and on a device without
 * the battery exemption, a foreground-service start that is refused outright.
 * `waitFor` is the honest choice. It protects the wait against Doze for as
 * long as the process legitimately keeps running, which is exactly what
 * `AppForegroundTrigger` and `NotificationWatchdogTrigger` already ask of the
 * same method for the same reason, and it does not pretend to a durability
 * this action could never make use of.
 *
 * **What that costs.** `NotificationWatchdogTrigger`'s KDoc says it well: the
 * engine's foreground service is what moves a process dying mid-wait from
 * routine to unlikely, not a guarantee. A kill during this action's wait
 * loses this action and every action after it in this firing, with nothing
 * to retry it later. `docs/todo.md`'s R1 covers the one cause nothing in this
 * codebase can fix regardless: a user's force stop.
 *
 * **What it costs even when nothing is killed.** A rule runs its actions one
 * at a time, in one coroutine. This action's whole point is to hold that
 * coroutine, so the actions after it really do wait, and a second event
 * reaching the same rule while it waits never runs alongside this one. That
 * part is a promise: `TriggerEngine.startRule` collects the whole trigger
 * tree as one merged flow, and nothing collects that flow again until this
 * wait, and every action after it, returns.
 *
 * **"Queues behind this one" is not a promise, though, and must not be read
 * as one.** What actually holds a second event is whichever trigger
 * produced it, not this action, and every trigger's hold is bounded. A
 * bus-backed trigger's `ServiceEventBus` keeps 64 events and drops the
 * oldest past that; a broadcast-backed trigger keeps its own bounded buffer
 * and drops the new arrival instead once that fills. A wait long enough, or
 * a trigger bursty enough, empties either one, and the events past that
 * point are lost outright, not merely late. The factory's
 * [ActionFactory.warning] states the part that always holds, which is where
 * a person building the rule actually reads it: two events for one rule
 * never run at the same time.
 *
 * **Cancellation needs no handling of its own.** [AlarmScheduler] promises
 * that cancelling the coroutine calling [AlarmScheduler.waitFor] is the whole
 * cancellation contract: no separate cancel method exists, on any of its four
 * methods. Disabling this rule cancels `TriggerEngine`'s job for it, that
 * cancellation reaches this suspended call, and the scheduler's own
 * implementation releases the alarm it set. A wait never fires against a rule
 * that was switched off while it waited.
 */
class DelayAction(
    private val scheduler: AlarmScheduler,
    private val durationMillis: Long,
) : Action {

    override suspend fun execute(event: TriggerEvent): ActionResult {
        scheduler.waitFor(durationMillis)
        return ActionResult.Success()
    }

    companion object {
        const val TYPE = "delay"
        const val CONFIG_DURATION_MILLIS = "durationMillis"

        /**
         * An hour. This action cannot survive its host process dying, unlike
         * `interval` and `solar`, which durably do; see this class's own KDoc.
         * An hour is long enough to be useful for "wait, then follow up", and
         * short enough that holding a live process for the whole wait stays a
         * reasonable ask rather than a private, non-durable clone of
         * `interval` for whatever multi-hour job actually wants that trigger's
         * durability instead.
         */
        const val MAX_DURATION_MILLIS = 60 * 60_000L
    }
}

class DelayActionFactory(private val scheduler: AlarmScheduler) : ActionFactory {
    override val type = DelayAction.TYPE

    override val displayName = "Wait"
    override val category = ActionCategory.TIMING

    override val configFields = listOf(
        ConfigField.Duration(
            key = DelayAction.CONFIG_DURATION_MILLIS,
            label = "Wait for",
            required = true,
            maxMillis = DelayAction.MAX_DURATION_MILLIS,
            preferred = DurationUnit.MINUTES,
            help = "This value is capped at ${DelayAction.MAX_DURATION_MILLIS / 60_000} minutes.",
        ),
    )

    override val warning: String =
        "This action pauses this rule. The actions after it wait until the " +
            "delay ends. If this rule fires again while it is waiting, two " +
            "runs never happen at the same time. But Trigly can only hold so " +
            "many waiting events. If a long wait lets too many pile up, " +
            "Trigly drops some of them instead of running them all. A long " +
            "wait can be off by a few minutes. If the app is killed while " +
            "this action waits, the rest of the rule does not run, and " +
            "nothing retries it later. Turning off this rule cancels a wait " +
            "in progress."

    override fun create(config: Map<String, String>): Action = DelayAction(
        scheduler = scheduler,
        durationMillis = delayDurationMillis(config[DelayAction.CONFIG_DURATION_MILLIS]),
    )
}
