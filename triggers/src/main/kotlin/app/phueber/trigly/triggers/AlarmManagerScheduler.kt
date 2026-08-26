package app.phueber.trigly.triggers

import android.app.AlarmManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.phueber.trigly.core.AlarmScheduler
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
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
 * **`setWindow`, always, never a `PendingIntent`.** Every call here uses
 * `AlarmManager.setWindow` with an `OnAlarmListener`. That listener form
 * delivers straight into this process while it is alive, on a plain
 * `Handler`, with no manifest entry and no exact-alarm permission. It is
 * exactly the shape every caller of this port needs: all five wait inside a
 * live coroutine, and none of them needs to be woken in a process the system
 * has already killed. `docs/todo.md`'s T1 asks for
 * `setExactAndAllowWhileIdle` "only where a user asked for an exact time".
 * No caller in this codebase asks for that today, so this class does not
 * build that path. It would need a `PendingIntent` and a registered
 * receiver, since the `AllowWhileIdle` family has no listener overload; add
 * it, guarded by `AlarmManager.canScheduleExactAlarms()`, for the caller that
 * first needs it, rather than carrying that surface unused.
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

    private val alarmManager: AlarmManager? = context.getSystemService(AlarmManager::class.java)

    // The listener form of setWindow can run its callback on any Handler; the
    // main thread is the one every process already has, so nothing extra
    // needs to be started or torn down for this class to work.
    private val handler = Handler(Looper.getMainLooper())

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
