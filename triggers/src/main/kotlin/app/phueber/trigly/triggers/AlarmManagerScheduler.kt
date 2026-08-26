package app.phueber.trigly.triggers

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.phueber.trigly.core.AlarmScheduler
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * [AlarmScheduler] over [AlarmManager].
 *
 * **Lives in `:triggers`, not `:ui`.** The port exists for the five triggers
 * that call it, and this module already turns a system callback into a
 * suspend function for triggers the same way: `BroadcastTrigger` registers
 * and unregisters a receiver on collection and cancellation, and this class
 * registers and cancels an alarm the same way. `:ui` is the module that
 * assembles the app; `TriglyApp` wires one instance of this class into the
 * container, the same way it wires `ListenerNotificationController` and
 * `ServiceUiController`, and has no reason to know how the wake-up works.
 *
 * **`setWindow` with an `OnAlarmListener`, for a wait inside a live
 * coroutine.** [waitFor] and [waitUntil] both use it: no manifest entry, no
 * exact-alarm permission, delivered straight into this process on a plain
 * `Handler` while it is alive. That is the right shape for
 * `AppForegroundTrigger`, `NotificationWatchdogTrigger` and
 * `ListenerBinding`'s repair loop: each polls inside a process the engine is
 * already keeping alive, and none of the three needs to be woken in a
 * process the system has already killed. `docs/todo.md`'s T1 asks for
 * `setExactAndAllowWhileIdle` "only where a user asked for an exact time".
 * No caller in this codebase asks for that today, so this class does not
 * build that path; see below for why an exact alarm is not the answer T17
 * reaches for either.
 *
 * **`setWindow` with a `PendingIntent`, added for T17, for a wait that must
 * outlive this process.** [waitForDurable] and [waitUntilDurable] are the
 * second path, aimed at `AlarmWakeReceiver` in `:ui` through the action
 * named by [AlarmWakeEvents.ACTION_ALARM_WAKE]. `IntervalTrigger` and
 * `SolarTrigger` are the two callers that use it: their whole wait is
 * worthless if it only lives as long as the listener alarm does, since AOSP
 * deletes a listener alarm the moment the process holding it dies
 * (`AlarmManagerService.setImplLocked`'s `mListenerDeathRecipient`). Each of
 * the two new methods sets *both* forms for the same instant and lets
 * whichever fires first cancel the other, so a live process is never woken
 * twice for the one wait; see [awaitAlarmDurably].
 *
 * **Whether that second alarm can bring the engine back is a settled,
 * sourced question, not a hope.**
 * `ActiveServices.shouldAllowFgsStartForegroundNoBindingCheckLocked`
 * (`services/core/java/com/android/server/am/ActiveServices.java`, AOSP tag
 * `android-15.0.0_r1`) is what `EngineService.start`'s call to
 * `startForegroundService` is checked against, and it allows a background
 * start only for a caller on one of a short list of allowances, read out of
 * `ActivityManagerService.isAllowlistedForFgsStartLOSP`.
 * `AlarmManagerService.setImplLocked` (in
 * `apex/jobscheduler/service/java/com/android/server/alarm/AlarmManagerService.java`,
 * same tag) only puts the caller on that list, with
 * `TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_ALLOWED`, for an *exact*
 * alarm: `setExactAndAllowWhileIdle` or `setAlarmClock`. An ordinary
 * `setWindow` alarm gets nothing at all (`idleOptions` stays `null`), and
 * even the inexact `allowWhileIdle` member of the family gets
 * `TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_NOT_ALLOWED` outright, on a
 * modern target-SDK app. That is this whole class's family, including the
 * two new methods: nothing here is exact, and this file does not build an
 * exact path. `docs/todo.md`'s T17 itself gives the reason:
 * `SCHEDULE_EXACT_ALARM` is a user-granted special access, and
 * `USE_EXACT_ALARM` is Play-restricted to alarm-clock apps. Reaching for
 * either is the maintainer's call, not a byproduct of this fix.
 *
 * There is a second, unrelated way onto that same allowlist.
 * `ActivityManagerService.isAllowlistedForFgsStartLOSP` also passes any uid
 * already on `mDeviceIdleExceptIdleAllowlist`, which is exactly the list a
 * user joins by granting the battery-optimisation exemption
 * `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` asks for, the one
 * `docs/architecture.md` already documents Trigly asking for and nagging
 * about until `PowerManager.isIgnoringBatteryOptimizations` answers true. So
 * on a device where the user has granted what this app already asks for, an
 * inexact durable wake *does* bring the foreground engine back; on one where
 * they have not, `EngineService.start` is refused exactly as its own KDoc
 * already expects for a call site that is not one of the platform's own
 * exemptions, and the durable alarm still recorded that it fired, for
 * whichever restart happens to come next to read. `docs/architecture.md`
 * carries this same finding for a reader who has not opened AOSP source.
 *
 * **Drift is expected, not a bug.** `setWindow` can still be deferred to the
 * platform's next Doze maintenance window even though it asks for a wakeup
 * type, because only the `AllowWhileIdle` family is exempt from that
 * deferral. [windowLengthMillis] sizes the window from the wait itself:
 * short for a short wait, capped at a few minutes for a long one, and that
 * cap is the drift this class promises. It is an honest answer to "how late
 * can this be", not a guess, and it is why every trigger that calls this port
 * says "a few minutes" in its own warning text rather than promising a
 * precise time.
 *
 * **What this does not fix.** A user's force-stop puts the app in the
 * stopped state. The system cancels every alarm this class has pending, and
 * nothing here, or anywhere in this codebase, gets it back. See
 * `docs/todo.md`'s R1.
 */
class AlarmManagerScheduler(context: Context) : AlarmScheduler {

    // The application context, not whatever context happened to construct
    // this: the PendingIntent half of a durable wait can outlive an Activity
    // by hours, and must not hold one.
    private val appContext: Context = context.applicationContext

    private val alarmManager: AlarmManager? = context.getSystemService(AlarmManager::class.java)

    // The listener form of setWindow can run its callback on any Handler; the
    // main thread is the one every process already has, so nothing extra
    // needs to be started or torn down for this class to work.
    private val handler = Handler(Looper.getMainLooper())

    // Distinguishes one durable wait's PendingIntent from another's within
    // this process's lifetime, so IntervalTrigger's alarm and SolarTrigger's
    // alarm never collide as "the same" alarm and cancel one another. Reset
    // to zero on every fresh process, which is harmless: every trigger that
    // held a durable wait re-registers one on the very same restart, and a
    // request code only has to be unique among alarms this process itself
    // currently holds, not across a process's whole history.
    private val nextRequestCode = AtomicInteger()

    override suspend fun waitFor(durationMillis: Long) {
        val wait = durationMillis.coerceAtLeast(0)
        val manager = alarmManager ?: return delay(wait)
        awaitAlarm(
            manager = manager,
            type = AlarmManager.ELAPSED_REALTIME_WAKEUP,
            triggerAtMillis = SystemClock.elapsedRealtime() + wait,
            windowLengthMillis = windowLengthMillis(wait),
        )
    }

    override suspend fun waitUntil(atMillis: Long) {
        val wait = durationUntil(System.currentTimeMillis(), atMillis)
        val manager = alarmManager ?: return delay(wait)
        awaitAlarm(
            manager = manager,
            type = AlarmManager.RTC_WAKEUP,
            triggerAtMillis = atMillis,
            windowLengthMillis = windowLengthMillis(wait),
        )
    }

    override suspend fun waitForDurable(durationMillis: Long) {
        val wait = durationMillis.coerceAtLeast(0)
        val manager = alarmManager ?: return delay(wait)
        awaitAlarmDurably(
            manager = manager,
            type = AlarmManager.ELAPSED_REALTIME_WAKEUP,
            triggerAtMillis = SystemClock.elapsedRealtime() + wait,
            windowLengthMillis = windowLengthMillis(wait),
        )
    }

    override suspend fun waitUntilDurable(atMillis: Long) {
        val wait = durationUntil(System.currentTimeMillis(), atMillis)
        val manager = alarmManager ?: return delay(wait)
        awaitAlarmDurably(
            manager = manager,
            type = AlarmManager.RTC_WAKEUP,
            triggerAtMillis = atMillis,
            windowLengthMillis = windowLengthMillis(wait),
        )
    }

    private suspend fun awaitAlarm(
        manager: AlarmManager,
        type: Int,
        triggerAtMillis: Long,
        windowLengthMillis: Long,
    ) = suspendCancellableCoroutine<Unit> { continuation ->
        val listener = AlarmManager.OnAlarmListener {
            if (continuation.isActive) continuation.resume(Unit)
        }
        manager.setWindow(type, triggerAtMillis, windowLengthMillis, TAG, listener, handler)
        continuation.invokeOnCancellation { manager.cancel(listener) }
    }

    /**
     * [awaitAlarm], plus a second alarm that does not die with this process.
     *
     * The `PendingIntent` half targets `AlarmWakeReceiver` in `:ui` through
     * an implicit intent scoped to this app's own package with
     * [AlarmWakeEvents.ACTION_ALARM_WAKE]. It is implicit rather than naming
     * the receiver's class, because `:triggers` must not depend on `:ui`; see
     * [AlarmWakeEvents]. Both alarms ask for the same instant and the same
     * window, so on a device that stays awake and alive the two are
     * expected to land close together; whichever fires first wins, and the
     * `finally` block below always cancels the `PendingIntent` alarm once
     * this call is done with it one way or another, so a live process is
     * never asked twice for the one wait and no alarm is left pending for a
     * wait that already happened.
     */
    private suspend fun awaitAlarmDurably(
        manager: AlarmManager,
        type: Int,
        triggerAtMillis: Long,
        windowLengthMillis: Long,
    ) {
        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            nextRequestCode.getAndIncrement(),
            Intent(AlarmWakeEvents.ACTION_ALARM_WAKE).setPackage(appContext.packageName),
            PendingIntent.FLAG_IMMUTABLE,
        )
        manager.setWindow(type, triggerAtMillis, windowLengthMillis, pendingIntent)
        try {
            awaitAlarm(manager, type, triggerAtMillis, windowLengthMillis)
        } finally {
            manager.cancel(pendingIntent)
        }
    }

    companion object {
        private const val TAG = "trigly-scheduler"
    }
}

/**
 * The size of the `setWindow` slack for a wait of [durationMillis], in
 * milliseconds.
 *
 * A short wait gets a short window, because the point of a short wait is to
 * notice soon. A long wait gets a window worth minutes, because the platform
 * can then batch this alarm with other apps' alarms and skip a wake-up
 * altogether, and a rule waiting an hour does not care whether it hears about
 * it one minute early or one minute late. [MIN_WINDOW_MILLIS] is the floor
 * and [MAX_WINDOW_MILLIS] is the ceiling; the ceiling is also the drift
 * [AlarmManagerScheduler]'s class KDoc promises.
 */
internal fun windowLengthMillis(durationMillis: Long): Long =
    (durationMillis / WINDOW_FRACTION).coerceIn(MIN_WINDOW_MILLIS, MAX_WINDOW_MILLIS)

/**
 * The gap between [nowMillis] and [atMillis], in milliseconds, never
 * negative. An instant already in the past waits zero: the alarm is set for
 * as soon as the platform can manage, not skipped and not thrown past.
 */
internal fun durationUntil(nowMillis: Long, atMillis: Long): Long =
    (atMillis - nowMillis).coerceAtLeast(0)

private const val WINDOW_FRACTION = 10L
internal const val MIN_WINDOW_MILLIS = 5_000L
internal const val MAX_WINDOW_MILLIS = 5 * 60_000L
