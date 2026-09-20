package app.phueber.trigly.actions

import app.phueber.trigly.core.Action
import app.phueber.trigly.core.ActionFactory
import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.AlarmScheduler
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.DurationUnit
import app.phueber.trigly.core.TriggerEvent
import app.phueber.trigly.core.WakeGuard
import kotlinx.coroutines.delay

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
 * **Two waits, picked by duration, and the short one is a plain coroutine
 * `delay`.** This file used to say that a plain `delay` was ruled out
 * entirely, on the grounds that the process's own clock stops counting once
 * the device suspends. That is true of a bare `delay` and it is not true of
 * one that runs while something holds the CPU awake, which is the whole of
 * what [WakeGuard] does.
 *
 * Above [AWAKE_WAIT_MAX_MILLIS] the wait is [AlarmScheduler.waitFor], as it
 * always was. An hour-long wait must survive the device suspending, holding
 * the CPU for an hour would be a battery fault, and a few minutes of drift on
 * an hour is a fair price.
 *
 * At or below it, the wait is a `delay` inside [WakeGuard.keepingAwake], and
 * the alarm is not involved at all. Two things were wrong with sending a short
 * wait through the scheduler, and only the second is about Doze.
 *
 * The first is this app's own arithmetic. `AlarmManagerScheduler` asks for a
 * window of a tenth of the wait with a floor of five seconds, which is right
 * for the poll loops it was written for and wrong here: a three second wait
 * becomes "somewhere between three and eight seconds", and the platform
 * batches toward the end of a window when it can. That is the error a person
 * sees with the screen on, before Doze is involved at all.
 *
 * The second is that `setWindow` is deferred by Doze, which only the
 * `AllowWhileIdle` family escapes, so the same three second wait can be held
 * until the next maintenance window once the phone has been locked for a
 * while. The exact alarms that would escape it are rate limited to about one
 * firing per app per nine minutes when idle, which is the wrong instrument for
 * a three second wait by two orders of magnitude, and they carry a permission
 * this app deliberately does not ask for. See `docs/todo.md` T14.
 *
 * A partial wake lock has neither problem. It is accurate to the millisecond,
 * because nothing is scheduling anything, and the device cannot suspend
 * underneath it. What it cannot do is survive the process dying, which is
 * true of this action either way, as the next paragraph explains.
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
    private val wake: WakeGuard,
    private val durationMillis: Long,
) : Action {

    override suspend fun execute(event: TriggerEvent): ActionResult {
        if (durationMillis <= AWAKE_WAIT_MAX_MILLIS) {
            wake.keepingAwake(TYPE, wakeTimeoutMillis(durationMillis)) { delay(durationMillis) }
        } else {
            scheduler.waitFor(durationMillis)
        }
        return ActionResult.Success()
    }

    companion object {
        const val TYPE = "delay"
        const val CONFIG_DURATION_MILLIS = "durationMillis"

        /**
         * The longest wait this action holds the CPU for instead of asking
         * `AlarmManager`. Thirty seconds.
         *
         * The number is not a preference. `AlarmManagerScheduler`'s window is
         * a tenth of the wait with a five second floor, so the floor is what
         * decides the error for anything short: five seconds on a thirty
         * second wait is a sixth of it, on a ten second wait it is half of it,
         * and on a three second wait it is nearly twice the wait. Thirty
         * seconds is where the scheduler's own arithmetic stops being an
         * approximation of the wait and starts being most of it.
         *
         * **The boundary is inclusive**, so exactly thirty seconds takes the
         * accurate path. Thirty is the round number a person types, and
         * `ConfigField.Duration` offers this field in minutes and seconds, so
         * it is a value the editor produces and reads back unchanged. Putting
         * the round number on the worse side of the line is how a component
         * earns a report that says thirty behaves badly while twenty-nine is
         * fine. `DelayActionTest` pins both sides.
         */
        const val AWAKE_WAIT_MAX_MILLIS = 30_000L

        /**
         * How much longer than the wait the wake lock's own timeout runs.
         *
         * The timeout is a backstop, not the mechanism: the hold ends when the
         * block ends, and this only bounds the damage if a path ever escapes
         * that while the process lives. It has to clear the wait itself, or
         * the backstop would start cutting real waits short, which would be a
         * silent return of the fault this path exists to fix. Five seconds
         * covers the dispatch either side of the wait and keeps the worst hold
         * a number that can be stated: thirty-five seconds.
         */
        const val WAKE_MARGIN_MILLIS = 5_000L

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

/**
 * How long the wake lock's timeout runs for a wait of [durationMillis].
 *
 * Pulled out as a function for the same reason
 * `AlarmManagerScheduler.windowLengthMillis` is: the rest of the wake path
 * calls `PowerManager` and cannot be tested on the JVM, while the arithmetic
 * can be, and a backstop that is quietly shorter than the work it backs would
 * fail in exactly the case nobody watches.
 */
fun wakeTimeoutMillis(durationMillis: Long): Long =
    durationMillis + DelayAction.WAKE_MARGIN_MILLIS

class DelayActionFactory(
    private val scheduler: AlarmScheduler,
    private val wake: WakeGuard,
) : ActionFactory {
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
            "Trigly drops some of them instead of running them all. A wait of " +
            "30 seconds or less is held to the second, with the screen off " +
            "too. A longer wait can be off by a few minutes, because Android " +
            "decides when to wake the phone for it. If the app is killed while " +
            "this action waits, the rest of the rule does not run, and " +
            "nothing retries it later. Turning off this rule cancels a wait " +
            "in progress."

    override fun create(config: Map<String, String>): Action = DelayAction(
        scheduler = scheduler,
        wake = wake,
        durationMillis = delayDurationMillis(config[DelayAction.CONFIG_DURATION_MILLIS]),
    )
}
