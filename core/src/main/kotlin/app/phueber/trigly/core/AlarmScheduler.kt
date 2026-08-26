package app.phueber.trigly.core

/**
 * A wait that a coroutine `delay` cannot promise.
 *
 * A plain `delay` only fires when something else wakes the device. It can
 * sleep through the whole wait if the device enters Doze, because it is
 * counted by the process's own clock rather than asked of the system.
 * `docs/todo.md`'s T1 is the record of that gap: five places in this codebase
 * waited that way, and one of them was the repair path for a dead
 * notification listener, so the repair was itself asleep in Doze.
 *
 * This interface is the fix's whole port. `:core` must not depend on any
 * Android type, so the contract is kept to the two shapes every caller in
 * this codebase actually needs: a repeating wait counted from now, and a wait
 * until one wall-clock instant. The Android implementation lives in
 * `:triggers`, as `AlarmManagerScheduler`, over `AlarmManager`.
 *
 * There is no separate cancel method. Every caller reaches this port from
 * inside its own coroutine, and cancelling that coroutine is the cancel: an
 * implementation must release whatever it asked the system for when the
 * calling coroutine is cancelled, and must not invent a second way to stop.
 *
 * This port says nothing about a user's force-stop. A force-stop cancels
 * every pending alarm the system holds for the app, and no scheduler design
 * changes that. See `docs/todo.md`'s R1 and `AlarmManagerScheduler`'s own
 * KDoc.
 */
interface AlarmScheduler {

    /**
     * Suspends for [durationMillis], waking the device if it has gone to
     * sleep by the time the wait is due.
     *
     * Call it again for the next tick. This is the repeating wait every poll
     * loop in this codebase already needs: an interval trigger, an app's
     * foreground poll, a notification watchdog's poll, and the retry inside
     * the listener-rebind repair path.
     *
     * Expect drift of up to a few minutes. See `AlarmManagerScheduler`'s KDoc
     * for why, and for the trade against an exact alarm.
     */
    suspend fun waitFor(durationMillis: Long)

    /**
     * Suspends until the wall-clock instant [atMillis] (epoch milliseconds,
     * the same unit [System.currentTimeMillis] returns), waking the device if
     * it has gone to sleep by then. Returns at once if [atMillis] is already
     * in the past.
     *
     * Expect drift of up to a few minutes, for the reason [waitFor] gives.
     * Nothing here asks for an exact alarm: no caller in this codebase needs
     * one today. Add that only for a caller that does, per `docs/todo.md`'s
     * T1, guarded behind the platform's own exact-alarm permission.
     */
    suspend fun waitUntil(atMillis: Long)
}
